// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The other servers asked over UDP for the sources of our downloads, once connected to one: a server a second, in
//! passes over the list with a pause between them.

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::time::{Duration, Instant};

use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::server_datagrams::{self, SourceQuery};
use crate::protocol::ed2k::server_messages::FoundSources;
use crate::protocol::packet::Packet;
use crate::server::server_list::{self, Address, ServerList};
use crate::server::server_pinger::PORT_OFFSET;
use crate::util::instants;

pub const INTERVAL: Duration = Duration::from_secs(1);
pub const PASS_PAUSE: Duration = Duration::from_secs(30 * 60);
pub const MAX_FILES_PER_SERVER: usize = 35;

const ANSWER_TIMEOUT: Duration = Duration::from_secs(30);

pub struct GlobalSources {
    // By the address and UDP port asked at: the files asked and when.
    asked: HashMap<SocketAddrV4, (HashSet<[u8; 16]>, Instant)>,
    left: VecDeque<Server>,
    requests: VecDeque<(SocketAddr, Packet)>,
    running: bool,
    passing: bool,
    next_pass: Instant,
    // Where the next server's files start, so that each server is asked for other files when there are many.
    offset: usize,
}

impl Default for GlobalSources {
    fn default() -> Self {
        Self {
            asked: HashMap::new(),
            left: VecDeque::new(),
            requests: VecDeque::new(),
            running: false,
            passing: false,
            next_pass: Instant::now(),
            offset: 0,
        }
    }
}

impl GlobalSources {
    /// Starts asking, once connected to a server: the first pass starts at once unless one ended lately.
    pub fn start(&mut self) {
        self.running = true;
    }

    pub fn stop(&mut self, now: Instant) {
        self.running = false;
        self.left.clear();
        self.requests.clear();
        self.asked.clear();
        if self.passing {
            self.passing = false;
            self.next_pass = instants::later(now, PASS_PAUSE);
        }
    }

    /// A source answer received from `from`: the HighID sources of the files asked of it.
    pub fn received(&mut self, from: SocketAddr, packet: &Packet) -> Vec<FoundSources> {
        let SocketAddr::V4(from) = from else {
            return Vec::new();
        };
        let Some((files, _)) = self.asked.get(&from) else {
            tracing::debug!("Unrequested sources from {from}");
            return Vec::new();
        };
        let found = match server_datagrams::decode_found_sources(packet) {
            Ok(found) => found,
            Err(e) => {
                tracing::debug!("Malformed sources from {from}: {e}");
                return Vec::new();
            }
        };
        found
            .into_iter()
            .filter(|file| files.contains(&file.file))
            .map(|file| FoundSources {
                file: file.file,
                sources: file.sources.into_iter().filter(|source| !source.is_low_id()).collect(),
            })
            .filter(|file| !file.sources.is_empty())
            .collect()
    }

    /// Once a [`INTERVAL`]: the next request to send, if any is due. Nothing is asked while the sources it finds could
    /// not be used (`allowed` false): every connection must be obfuscated, which needs user hashes servers do not give.
    pub fn tick(
        &mut self,
        now: Instant,
        servers: &ServerList,
        connected: Option<&Address>,
        dead_retries: u32,
        wanting: &[SourceQuery],
        allowed: bool,
    ) -> Option<(SocketAddr, Packet)> {
        self.asked.retain(|_, (_, sent)| now.saturating_duration_since(*sent) <= ANSWER_TIMEOUT);
        if !self.running
            || !allowed
            || (self.requests.is_empty() && !self.queue_next_server(now, servers, connected, dead_retries, wanting))
        {
            return None;
        }
        self.requests.pop_front()
    }

    // Queues the requests for the next server to ask; false when there is none now.
    fn queue_next_server(
        &mut self,
        now: Instant,
        servers: &ServerList,
        connected: Option<&Address>,
        dead_retries: u32,
        wanting: &[SourceQuery],
    ) -> bool {
        loop {
            let Some(server) = self.left.pop_front() else {
                if self.passing {
                    self.passing = false;
                    self.next_pass = instants::later(now, PASS_PAUSE);
                    return false;
                }
                if now < self.next_pass || wanting.is_empty() {
                    return false;
                }
                self.left.extend(servers.candidates());
                self.passing = !self.left.is_empty();
                if !self.passing {
                    return false;
                }
                continue;
            };
            let askable = server.ip != 0
                && server.failed_count().unwrap_or(0) < u64::from(dead_retries)
                && connected != Some(&server_list::address_of(&server));
            let Some(port) = server.port.checked_add(PORT_OFFSET).filter(|_| askable) else {
                continue;
            };
            let files = self.next_files(wanting);
            if files.is_empty() {
                // Nothing is wanted any more: the pass ends here.
                self.left.clear();
                continue;
            }
            let flags = u32::try_from(server.udp_flags().unwrap_or(0)).unwrap_or(0);
            let packets = server_datagrams::source_requests(&files, flags);
            if packets.is_empty() {
                continue;
            }
            let target = SocketAddrV4::new(Ipv4Addr::from(server.ip), port);
            self.asked.insert(target, (files.iter().map(|file| file.file).collect(), now));
            self.requests.extend(packets.into_iter().map(|packet| (SocketAddr::V4(target), packet)));
            return true;
        }
    }

    fn next_files(&mut self, wanting: &[SourceQuery]) -> Vec<SourceQuery> {
        if wanting.len() <= MAX_FILES_PER_SERVER {
            return wanting.to_vec();
        }
        let files = (0..MAX_FILES_PER_SERVER)
            .filter_map(|i| wanting.get(self.offset.saturating_add(i).checked_rem(wanting.len()).unwrap_or(0)))
            .cloned()
            .collect();
        self.offset = self.offset.saturating_add(MAX_FILES_PER_SERVER).checked_rem(wanting.len()).unwrap_or(0);
        files
    }
}
