// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Offers our shared files to the server we are on: in batches, the files it was not offered yet, as many as it takes.

use std::collections::HashSet;
use std::time::{Duration, Instant};

use crate::files::file_types;
use crate::files::hash::ed2k;
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::file_entry::FileEntry;
use crate::protocol::ed2k::server_messages::{self, IdChange};
use crate::protocol::packet::{Packet, packing};
use crate::protocol::tag::{Tag, TagName, file_tag};

/// Files offered at once at most.
pub const BATCH: usize = 200;
/// The wait between two offers.
pub const INTERVAL: Duration = Duration::from_secs(60);

// Ids and ports that servers with compression read as "this client, complete or not".
const COMPLETE_ID: u32 = 0xFBFB_FBFB;
const COMPLETE_PORT: u16 = 0xFBFB;
const PARTIAL_ID: u32 = 0xFCFC_FCFC;
const PARTIAL_PORT: u16 = 0xFCFC;

/// A file to offer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Offer {
    pub hash: [u8; 16],
    pub name: String,
    pub size: u64,
    pub complete: bool,
}

#[derive(Debug, Default)]
pub struct FilePublisher {
    offered: HashSet<[u8; 16]>,
    needed: bool,
    next: Option<Instant>,
}

impl FilePublisher {
    pub fn connected(&mut self, now: Instant) {
        self.offered.clear();
        self.needed = true;
        self.next = Some(now);
    }

    pub fn disconnected(&mut self) {
        self.offered.clear();
        self.needed = false;
    }

    /// Our files changed; a completed one is offered again.
    pub fn changed(&mut self, completed: Option<[u8; 16]>) {
        if let Some(hash) = completed {
            self.offered.remove(&hash);
        }
        self.needed = true;
    }

    /// The offer due now, if any: the files not offered yet, in a packet for the server we are on.
    pub fn tick(
        &mut self,
        now: Instant,
        id: Option<IdChange>,
        server: Option<&Server>,
        listen_port: u16,
        files: &[Offer],
    ) -> Option<(Packet, Vec<[u8; 16]>)> {
        let id = id?;
        if !self.needed || self.next.is_none_or(|next| now < next) {
            return None;
        }
        let large = id.has(server_messages::TCP_LARGEFILES);
        let room = BATCH.min(self.room(server));
        let mut batch = Vec::new();
        let mut hashes = Vec::new();
        for offer in files {
            if batch.len() >= room {
                break;
            }
            if self.offered.contains(&offer.hash) || (offer.size > ed2k::OLD_MAX_FILE_SIZE && !large) {
                continue;
            }
            batch.push(entry(offer, &id, listen_port));
            hashes.push(offer.hash);
        }
        if batch.is_empty() {
            self.needed = false;
            return None;
        }
        let new_tags = id.has(server_messages::TCP_NEWTAGS);
        let mut packet = server_messages::offer_files(&batch, new_tags).ok()?;
        if id.has(server_messages::TCP_COMPRESSION) {
            packet = packing::pack(packet.clone()).unwrap_or(packet);
        }
        Some((packet, hashes))
    }

    /// The offer went out.
    pub fn sent(&mut self, now: Instant, hashes: Vec<[u8; 16]>) {
        self.offered.extend(hashes);
        self.next = now.checked_add(INTERVAL);
    }

    // A server takes up to its soft limit of files from each client, when it told one; offering more is wasted on it
    // and, past its hard limit, gets the client dropped.
    fn room(&self, server: Option<&Server>) -> usize {
        let limit = server
            .and_then(Server::soft_files)
            .filter(|soft| *soft > 0)
            .and_then(|soft| usize::try_from(soft).ok())
            .unwrap_or(usize::MAX);
        limit.saturating_sub(self.offered.len())
    }
}

fn entry(offer: &Offer, id: &IdChange, listen_port: u16) -> FileEntry {
    let (client_id, port) = if id.has(server_messages::TCP_COMPRESSION) {
        if offer.complete { (COMPLETE_ID, COMPLETE_PORT) } else { (PARTIAL_ID, PARTIAL_PORT) }
    } else if id.is_low_id() {
        (0, 0)
    } else {
        (id.client_id, listen_port)
    };
    let mut tags = vec![
        Tag::String { name: TagName::id(file_tag::FILENAME), value: offer.name.clone() },
        Tag::Int { name: TagName::id(file_tag::FILESIZE), value: offer.size & 0xFFFF_FFFF },
    ];
    if offer.size > 0xFFFF_FFFF {
        tags.push(Tag::Int { name: TagName::id(file_tag::FILESIZE_HI), value: offer.size >> 32 });
    }
    if let Some(kind) = file_types::of(&offer.name) {
        let tag = match file_types::code(kind) {
            Some(code) if id.has(server_messages::TCP_TYPE_TAG_INTEGER) => {
                Tag::Int { name: TagName::id(file_tag::FILETYPE), value: u64::from(code) }
            }
            _ => Tag::String { name: TagName::id(file_tag::FILETYPE), value: kind.to_owned() },
        };
        tags.push(tag);
    }
    FileEntry { hash: offer.hash, client_id, port, tags }
}
