// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The known eD2K servers, kept in `server.met`, and the static ones of `staticservers.dat`.
//!
//! Servers without a usable address (port 0, a reserved IP) are not added; those known by a host name are kept by it
//! and offered for connecting only, as they are resolved then.

use std::fmt;
use std::io;
use std::net::Ipv4Addr;
use std::path::Path;

use indexmap::{IndexMap, IndexSet};

use crate::persist::atomic_files;
use crate::persist::format::server_met::{self, Server};
use crate::persist::format::text_files::{self, StaticServer};
use crate::protocol::ed2k::server_datagrams;
use crate::protocol::tag::{Tag, TagName, server_tag};
use crate::util::ipv4;

pub const FILE_NAME: &str = "server.met";
pub const STATIC_FILE_NAME: &str = "staticservers.dat";

/// Values of the preference tag.
pub const PRIORITY_NORMAL: u64 = 0;
pub const PRIORITY_HIGH: u64 = 1;
pub const PRIORITY_LOW: u64 = 2;

/// The priority a server is tried with.
#[derive(Debug, Clone, Copy, PartialEq, Eq, strum::VariantArray)]
pub enum Priority {
    High,
    Normal,
    Low,
}

impl Priority {
    pub fn name(self) -> &'static str {
        match self {
            Self::High => "high",
            Self::Normal => "normal",
            Self::Low => "low",
        }
    }
}

/// A server address: `ip` in [`crate::util::ipv4`] form, or for a server known by host name that name (lower case)
/// and ip 0, whatever it resolves to.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Address {
    pub ip: u32,
    pub port: u16,
    pub host: String,
}

impl Address {
    pub fn new(ip: u32, port: u16) -> Self {
        Self { ip, port, host: String::new() }
    }

    /// `host:port`, an IPv4 address or a host name.
    pub fn parse(text: &str) -> Option<Self> {
        let (host, port) = text.trim().rsplit_once(':')?;
        let port: u16 = port.parse().ok().filter(|port| *port != 0)?;
        match host.parse::<Ipv4Addr>() {
            Ok(ip) => Some(Self::new(u32::from(ip), port)),
            Err(_) if is_host_name(host) => Some(Self { ip: 0, port, host: host.to_lowercase() }),
            Err(_) => None,
        }
    }
}

impl fmt::Display for Address {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.host.is_empty() {
            write!(f, "{}:{}", Ipv4Addr::from(self.ip), self.port)
        } else {
            write!(f, "{}:{}", self.host, self.port)
        }
    }
}

#[derive(Debug, Default)]
pub struct ServerList {
    servers: IndexMap<Address, Server>,
    statics: IndexSet<Address>,
    // Static servers not taken in the list (unusable): written back as they were.
    untaken: Vec<StaticServer>,
    blocked: Blocked,
}

// The addresses the IP filter blocks.
struct Blocked(Box<dyn Fn(u32) -> bool + Send + Sync>);

impl Default for Blocked {
    fn default() -> Self {
        Self(Box::new(|_| false))
    }
}

impl std::fmt::Debug for Blocked {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Blocked")
    }
}

impl ServerList {
    /// Reads `file`; a missing file gives an empty list.
    pub fn load(file: &Path) -> io::Result<Self> {
        let mut list = Self::default();
        match atomic_files::read(file, |data| {
            server_met::read(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
        }) {
            Ok(servers) => {
                list.add_all(servers);
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(e) => return Err(e),
        }
        Ok(list)
    }

    pub fn save(&self, file: &Path) -> io::Result<()> {
        let data = server_met::write(&self.all()).map_err(io::Error::other)?;
        atomic_files::write(file, &data)
    }

    /// Adds the servers not known yet; returns how many were added.
    pub fn add_all(&mut self, others: impl IntoIterator<Item = Server>) -> usize {
        others.into_iter().filter(|server| self.add(server.clone())).count()
    }

    /// Adds a server unless its address is known already or cannot be one of a server. Its name and description come
    /// from other clients' lists and are shown: they lose their control characters.
    /// Adds a server unless its address is already known, blocked or cannot be one of a server.
    pub fn add(&mut self, server: Server) -> bool {
        if server.port == 0
            || !(is_usable(server.ip) || server.dynamic_ip().is_some())
            || (server.ip != 0 && (self.blocked.0)(server.ip))
        {
            return false;
        }
        let address = address_of(&server);
        if self.servers.contains_key(&address) {
            return false;
        }
        self.servers.insert(address, shown(server));
        true
    }

    pub fn get(&self, address: &Address) -> Option<&Server> {
        self.servers.get(address)
    }

    pub fn update(&mut self, address: &Address, change: impl FnOnce(&Server) -> Server) {
        if let Some(server) = self.servers.get_mut(address) {
            *server = change(server);
        }
    }

    pub fn remove(&mut self, address: &Address) -> bool {
        self.statics.shift_remove(address);
        self.servers.shift_remove(address).is_some()
    }

    /// Reads the static servers of `file` (`host:port,priority,name`): missing ones are added, and each takes the
    /// priority written there.
    pub fn load_statics(&mut self, file: &Path) -> io::Result<()> {
        let entries = match atomic_files::read(file, |data| Ok(text_files::static_servers(data))) {
            Ok(entries) => entries,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(()),
            Err(e) => return Err(e),
        };
        self.untaken.clear();
        for entry in entries {
            let server = server_of(&entry);
            let address = address_of(&server);
            self.add(server);
            match self.servers.get_mut(&address) {
                Some(known) => {
                    let mut kept = known.with(int(server_tag::PREFERENCE, u64::from(entry.priority)));
                    if kept.name().is_none() && !entry.name.trim().is_empty() {
                        kept = kept.with(string(server_tag::SERVERNAME, &entry.name));
                    }
                    *known = kept;
                    self.statics.insert(address);
                }
                None => self.untaken.push(entry),
            }
        }
        Ok(())
    }

    // One server a line: names and hosts come from servers and lists, so line breaks and other control characters go.
    pub fn save_statics(&self, file: &Path) -> io::Result<()> {
        let mut entries = Vec::new();
        for address in &self.statics {
            if let Some(server) = self.servers.get(address) {
                let host = without_control(&server.dynamic_ip().map_or_else(|| ip_text(server.ip), str::to_owned));
                let priority = u8::try_from(server.preference().unwrap_or(PRIORITY_NORMAL)).unwrap_or(0);
                let name = without_control(server.name().unwrap_or(&host));
                entries.push(StaticServer { host, port: server.port, priority, name });
            }
        }
        for entry in &self.untaken {
            if !self.statics.contains(&address_of(&server_of(entry))) {
                entries.push(entry.clone());
            }
        }
        atomic_files::write(file, &text_files::write_static_servers(&entries))
    }

    pub fn is_static(&self, address: &Address) -> bool {
        self.statics.contains(address)
    }

    /// Puts a known server in the static list or takes it out; false when unknown or already so.
    pub fn set_static(&mut self, address: &Address, wanted: bool) -> bool {
        if !self.servers.contains_key(address) {
            return false;
        }
        if wanted { self.statics.insert(address.clone()) } else { self.statics.shift_remove(address) }
    }

    /// Sets the priority a known server is tried with; false when unknown.
    pub fn set_priority(&mut self, address: &Address, wanted: Priority) -> bool {
        let value = match wanted {
            Priority::High => PRIORITY_HIGH,
            Priority::Normal => PRIORITY_NORMAL,
            Priority::Low => PRIORITY_LOW,
        };
        match self.servers.get_mut(address) {
            Some(server) => {
                *server = server.with(int(server_tag::PREFERENCE, value));
                true
            }
            None => false,
        }
    }

    pub fn all(&self) -> Vec<Server> {
        self.servers.values().cloned().collect()
    }

    pub fn len(&self) -> usize {
        self.servers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.servers.is_empty()
    }

    /// The servers that failed `retries` times in a row or more; static servers are never dead.
    pub fn dead(&self, retries: u32) -> Vec<Address> {
        self.servers
            .iter()
            .filter(|(address, server)| {
                server.failed_count().unwrap_or(0) >= u64::from(retries) && !self.statics.contains(*address)
            })
            .map(|(address, _)| address.clone())
            .collect()
    }

    /// The servers at a usable address, to ask over UDP: high priority first, then those that failed least.
    pub fn candidates(&self) -> Vec<Server> {
        self.sorted(|server| self.accepts(server.ip))
    }

    /// As [`Self::candidates`], with the servers known by host name too, resolved when connecting.
    pub fn connectable(&self) -> Vec<Server> {
        self.sorted(|server| self.accepts(server.ip) || server.dynamic_ip().is_some())
    }

    /// Applies the IP filter: blocked servers are no longer added nor offered.
    pub fn filter(&mut self, blocks: impl Fn(u32) -> bool + Send + Sync + 'static) {
        self.blocked = Blocked(Box::new(blocks));
    }

    /// Whether a server may be at `ip`: a usable address the filters let through.
    pub fn accepts(&self, ip: u32) -> bool {
        is_usable(ip) && !(self.blocked.0)(ip)
    }

    fn sorted(&self, wanted: impl Fn(&Server) -> bool) -> Vec<Server> {
        let mut usable: Vec<Server> = self.servers.values().filter(|server| wanted(server)).cloned().collect();
        usable.sort_by_key(|server| (rank(server), server.failed_count().unwrap_or(0)));
        usable
    }
}

/// Whether the server announced TCP obfuscation and a port it takes it on.
pub fn supports_obfuscation(server: &Server) -> bool {
    let flags = server.udp_flags().unwrap_or(0);
    let port = server.obfuscated_tcp_port().unwrap_or(0);
    (1..=0xFFFF).contains(&port)
        && flags & u64::from(server_datagrams::UDP_OBFUSCATION | server_datagrams::UDP_TCP_OBFUSCATION) != 0
}

/// A server known by host name keeps its address whatever the name resolves to.
pub fn address_of(server: &Server) -> Address {
    match server.dynamic_ip() {
        Some(host) => Address { ip: 0, port: server.port, host: host.to_lowercase() },
        None => Address::new(server.ip, server.port),
    }
}

pub fn priority(server: &Server) -> Priority {
    match server.preference().unwrap_or(PRIORITY_NORMAL) {
        PRIORITY_HIGH => Priority::High,
        PRIORITY_LOW => Priority::Low,
        _ => Priority::Normal,
    }
}

/// Usable as a peer address: not 0.x, loopback, multicast, broadcast or reserved.
pub fn is_usable(ip: u32) -> bool {
    ipv4::is_unicast_public_candidate(ip)
}

/// A private address: 0.x, 10.x, 172.16-31.x, 192.168.x.
pub fn is_lan(ip: u32) -> bool {
    ipv4::is_lan(ip)
}

pub fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

pub fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

/// Text that is shown and printed: without control characters.
pub fn without_control(text: &str) -> String {
    text.chars().filter(|c| !c.is_control()).collect()
}

fn shown(server: Server) -> Server {
    let mut shown = server;
    if let Some(name) = shown.name().filter(|name| name.chars().any(char::is_control)).map(without_control) {
        shown = shown.with(string(server_tag::SERVERNAME, &name));
    }
    if let Some(description) =
        shown.description().filter(|text| text.chars().any(char::is_control)).map(without_control)
    {
        shown = shown.with(string(server_tag::DESCRIPTION, &description));
    }
    shown
}

fn server_of(entry: &StaticServer) -> Server {
    let mut tags = vec![string(server_tag::SERVERNAME, &entry.name)];
    let ip = match entry.host.parse::<Ipv4Addr>() {
        Ok(ip) => u32::from(ip),
        Err(_) => {
            // A host name: resolved when connecting.
            tags.push(string(server_tag::DYNIP, &entry.host));
            0
        }
    };
    Server { ip, port: entry.port, tags }
}

fn rank(server: &Server) -> u8 {
    match priority(server) {
        Priority::High => 0,
        Priority::Normal => 1,
        Priority::Low => 2,
    }
}

fn ip_text(ip: u32) -> String {
    Ipv4Addr::from(ip).to_string()
}

fn is_host_name(host: &str) -> bool {
    !host.is_empty() && host.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
}
