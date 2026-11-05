// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Asks the known servers for their status over UDP, one every few seconds while we are logged in, then for their
//! description; a server that answers loses its failures, one that failed too often leaves the list.

use std::collections::HashMap;
use std::net::{Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use rand::{Rng, RngExt};

use crate::persist::format::server_met::{self, Server};
use crate::protocol::ed2k::server_datagrams::{self, Description, Status};
use crate::protocol::packet::Packet;
use crate::protocol::packet::opcodes::server_udp;
use crate::protocol::tag::{Tag, TagName, server_tag};
use crate::server::server_list::{self, Address, ServerList};
use crate::util::ipv4;
use crate::util::white_space::strip;

pub const INTERVAL: Duration = Duration::from_secs(5);
/// How long a server answered stays unasked.
pub const REPING_AFTER: Duration = Duration::from_secs(270 * 60);
/// Servers listen for UDP on their TCP port plus this.
pub const PORT_OFFSET: u16 = 4;

const ANSWER_TIMEOUT: Duration = Duration::from_secs(30);
const CHALLENGE_BASE: u32 = 0x55AA_0000;
// Servers that announce obfuscation without ports use these defaults.
const OBFUSCATED_UDP_OFFSET: u16 = 12;
const MAX_VERSION: usize = 64;

struct Pending {
    challenge: u32,
    sent: Instant,
}

/// What a ping asks the engine to do.
#[derive(Debug, Clone)]
pub enum Effect {
    Send(SocketAddr, Packet),
    /// The server answered: it changed.
    Updated(Server),
    /// The server failed too often: it leaves the list.
    Dead(Server),
}

#[derive(Default)]
pub struct ServerPinger {
    pending: HashMap<Address, Pending>,
    descriptions: HashMap<Address, Pending>,
    cursor: usize,
}

impl ServerPinger {
    pub fn stop(&mut self) {
        self.pending.clear();
        self.descriptions.clear();
    }

    /// Asks the next server due, if any.
    pub fn ping_next(&mut self, servers: &mut ServerList, dead_retries: u32, random: &mut impl Rng) -> Vec<Effect> {
        self.pending.retain(|_, pending| pending.sent.elapsed() <= ANSWER_TIMEOUT);
        self.descriptions.retain(|_, pending| pending.sent.elapsed() <= ANSWER_TIMEOUT);
        let now = epoch_seconds();
        let candidates = servers.candidates();
        let count = candidates.len();
        for i in 0..count {
            let index = self.cursor.wrapping_add(i).checked_rem(count).unwrap_or(0);
            let Some(server) = candidates.get(index) else {
                continue;
            };
            let address = server_list::address_of(server);
            // A last ping in the future (a clock set back, a list from elsewhere) is no recent ping.
            let since = now.checked_sub(server.last_ping().unwrap_or(0));
            let recent = since.is_some_and(|since| since < REPING_AFTER.as_secs());
            let udp_port = server.port.checked_add(PORT_OFFSET);
            if server.ip == 0 || udp_port.is_none() || recent || self.pending.contains_key(&address) {
                continue;
            }
            self.cursor = index.saturating_add(1);
            if server.failed_count().unwrap_or(0) >= u64::from(dead_retries) && !servers.is_static(&address) {
                return vec![Effect::Dead(server.clone())];
            }
            let challenge = CHALLENGE_BASE | u32::from(random.random::<u16>());
            let target = SocketAddr::from((Ipv4Addr::from(server.ip), udp_port.unwrap_or(server.port)));
            self.pending.insert(address.clone(), Pending { challenge, sent: Instant::now() });
            // Recorded on sending, so a silent server is not asked again at every start; the answer takes the
            // failure back.
            servers.update(&address, |s| {
                s.with(int(server_tag::LASTPING, now))
                    .with(int(server_tag::FAIL, s.failed_count().unwrap_or(0).saturating_add(1)))
            });
            return vec![Effect::Send(target, server_datagrams::status_request(challenge))];
        }
        Vec::new()
    }

    /// A datagram from a server's UDP port: a status or a description we asked for.
    pub fn received(
        &mut self,
        from: SocketAddr,
        packet: &Packet,
        servers: &mut ServerList,
        our_ip: u32,
        random: &mut impl Rng,
    ) -> Vec<Effect> {
        let SocketAddr::V4(from) = from else {
            return Vec::new();
        };
        if packet.opcode() == server_udp::SERVER_DESC_RES {
            return self.described(from, packet, servers);
        }
        let Ok(status) = Status::decode(packet) else {
            tracing::debug!("Malformed status from {from}");
            return Vec::new();
        };
        let Some((address, server)) = asked(&self.pending, from, status.challenge, servers) else {
            tracing::debug!("Unrequested status from {from}");
            return Vec::new();
        };
        let ping = self.pending.remove(&address).map_or(0, |sent| sent.sent.elapsed().as_millis());
        servers.update(&address, |s| {
            apply(s, &status, our_ip).with(int(server_tag::PING, u64::try_from(ping).unwrap_or(u64::MAX)))
        });
        let mut effects = Vec::new();
        if let Some(updated) = servers.get(&address) {
            effects.push(Effect::Updated(updated.clone()));
        }
        let challenge = server_datagrams::description_challenge(random.next_u32());
        if let Some(port) = server.port.checked_add(PORT_OFFSET) {
            let target = SocketAddr::from((Ipv4Addr::from(server.ip), port));
            self.descriptions.insert(address, Pending { challenge, sent: Instant::now() });
            effects.push(Effect::Send(target, server_datagrams::description_request(challenge)));
        }
        effects
    }

    fn described(&mut self, from: std::net::SocketAddrV4, packet: &Packet, servers: &mut ServerList) -> Vec<Effect> {
        let Ok(description) = Description::decode(packet) else {
            tracing::debug!("Malformed description from {from}");
            return Vec::new();
        };
        let Some((address, _)) = asked(&self.descriptions, from, description.challenge, servers) else {
            tracing::debug!("Unrequested description from {from}");
            return Vec::new();
        };
        self.descriptions.remove(&address);
        servers.update(&address, |s| describe(s, &description.tags));
        servers.get(&address).map(|server| vec![Effect::Updated(server.clone())]).unwrap_or_default()
    }
}

// The server asked, from where the answer came, with its challenge: only the few waiting ones are looked at.
fn asked(
    waiting: &HashMap<Address, Pending>,
    from: std::net::SocketAddrV4,
    challenge: u32,
    servers: &ServerList,
) -> Option<(Address, Server)> {
    let ip = u32::from(*from.ip());
    if !server_list::accepts(ip) {
        return None;
    }
    waiting.iter().filter(|(_, pending)| pending.challenge == challenge).find_map(|(address, _)| {
        servers
            .get(address)
            .filter(|server| server.ip == ip && is_udp_port_of(server, from.port()))
            .map(|server| (address.clone(), server.clone()))
    })
}

fn is_udp_port_of(server: &Server, port: u16) -> bool {
    server.port.checked_add(PORT_OFFSET) == Some(port)
        || server.port.checked_add(OBFUSCATED_UDP_OFFSET) == Some(port)
        || server.obfuscated_udp_port() == Some(u64::from(port))
}

/// The server with what its status says.
pub fn apply(server: &Server, status: &Status, our_ip: u32) -> Server {
    let mut tcp_port = u64::from(status.obfuscated_tcp_port);
    let mut udp_port = u64::from(status.obfuscated_udp_port);
    if status.has(server_datagrams::UDP_TCP_OBFUSCATION) && tcp_port == 0 {
        tcp_port = u64::from(server.port);
    }
    if status.has(server_datagrams::UDP_OBFUSCATION) && udp_port == 0 {
        udp_port = u64::from(server.port.saturating_add(OBFUSCATED_UDP_OFFSET));
    }
    let named = |name: &[u8], value: u32| Tag::Int { name: TagName::of_bytes(name), value: u64::from(value) };
    let mut updated = server
        .with(named(server_tag::USERS, status.users))
        .with(named(server_tag::FILES, status.files))
        .with(int(server_tag::MAXUSERS, u64::from(status.max_users)))
        .with(int(server_tag::SOFTFILES, u64::from(status.soft_files)))
        .with(int(server_tag::HARDFILES, u64::from(status.hard_files)))
        .with(int(server_tag::UDPFLAGS, u64::from(status.udp_flags)))
        .with(int(server_tag::LOWIDUSERS, u64::from(status.low_id_users)))
        .with(int(server_tag::TCPPORTOBFUSCATION, tcp_port))
        .with(int(server_tag::UDPPORTOBFUSCATION, udp_port))
        .with(int(server_tag::FAIL, 0));
    if status.udp_key != 0 {
        updated = updated
            .with(int(server_tag::UDPKEY, u64::from(status.udp_key)))
            .with(int(server_tag::UDPKEYIP, u64::from(ipv4::to_ed2k(our_ip))));
    }
    updated
}

// Names and descriptions are shown and printed: without control characters; blank ones keep what was known.
pub fn describe(server: &Server, tags: &[Tag]) -> Server {
    let mut described = server.clone();
    for tag in tags {
        let Some(id) = tag.name().as_id() else {
            continue;
        };
        let text = match tag {
            Tag::String { value, .. } if id == server_tag::SERVERNAME || id == server_tag::DESCRIPTION => {
                strip(&server_list::without_control(value)).to_owned()
            }
            Tag::String { value, .. } if id == server_tag::VERSION => {
                strip(&server_list::without_control(value)).chars().take(MAX_VERSION).collect()
            }
            Tag::Int { value, .. } if id == server_tag::VERSION => server_met::version_of(value >> 16, value & 0xFFFF),
            _ => String::new(),
        };
        if !text.is_empty() {
            described = described.with(server_list::string(id, &text));
        }
    }
    described
}

fn int(id: u8, value: u64) -> Tag {
    server_list::int(id, value)
}

fn epoch_seconds() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |since| since.as_secs())
}
