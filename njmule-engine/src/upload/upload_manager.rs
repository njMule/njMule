// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The clients that download from us: their sessions, the upload slots and the queue waiting for them, and the blocks
//! read from our files and sent. Credits, bans, the upload limit and partial files wait for their ports.

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;

use indexmap::IndexMap;
use tokio::sync::mpsc::UnboundedSender;

use crate::files::hash::ed2k;
use crate::files::shared_files::SharedFile;
use crate::peer::peer_link::{LinkHandle, LinkId};
use crate::protocol::ed2k::client_messages::SendingPart;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::packet::Packet;
use crate::upload::upload_session::{Block, Request, UploadSession};

/// Bytes a slot sends before its client goes back to the queue.
pub const SESSION_LIMIT: u64 = ed2k::PARTSIZE + 20_480;
/// Slots open at once, without an upload limit.
pub const MAX_SLOTS: usize = 10;

const CHUNK: usize = 10 * 1024;

/// A block sent to the client of a link.
#[derive(Debug)]
pub struct Sent {
    pub link: LinkId,
    pub bytes: u64,
}

pub struct UploadManager {
    sessions: HashMap<LinkId, UploadSession>,
    // The links with a slot, and the bytes each slot sent.
    slots: IndexMap<LinkId, u64>,
    // The clients waiting for a slot, by user hash, first come first.
    waiting: IndexMap<[u8; 16], LinkId>,
    // Links a block is being read and sent for.
    sending: HashSet<LinkId>,
    sent: UnboundedSender<Sent>,
}

impl UploadManager {
    pub fn new(sent: UnboundedSender<Sent>) -> Self {
        Self {
            sessions: HashMap::new(),
            slots: IndexMap::new(),
            waiting: IndexMap::new(),
            sending: HashSet::new(),
            sent,
        }
    }

    /// A packet of a link's upload side, whose client greeted us as `peer`.
    pub fn received(
        &mut self,
        link: &LinkHandle,
        peer: &Hello,
        packet: &Packet,
        ours: &Hello,
        find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>,
    ) {
        let session = self.sessions.entry(link.id()).or_insert_with(|| UploadSession::new(link.clone(), peer.clone()));
        match session.received(packet, ours, find) {
            Ok(Some(Request::Upload(_))) => self.requested(link.id()),
            Ok(Some(Request::Blocks)) => self.send_next(link.id(), find),
            Ok(Some(Request::Stopped)) => {
                self.slots.shift_remove(&link.id());
            }
            Ok(Some(Request::Misbehaved)) => {
                tracing::debug!("{} changed its user hash", link.remote());
                link.close();
            }
            Ok(None) => {}
            Err(e) => {
                tracing::debug!("Bad packet from {}: {e}", link.remote());
                link.close();
            }
        }
    }

    pub fn closed(&mut self, link: LinkId) {
        if let Some(session) = self.sessions.remove(&link) {
            self.waiting.shift_remove(&session.user_hash());
        }
        self.slots.shift_remove(&link);
        self.sending.remove(&link);
    }

    /// A block went out: the next follows, unless the slot has sent its share.
    pub fn block_sent(&mut self, sent: &Sent, find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>) {
        self.sending.remove(&sent.link);
        let Some(total) = self.slots.get_mut(&sent.link) else {
            return;
        };
        *total = total.saturating_add(sent.bytes);
        if *total >= SESSION_LIMIT {
            self.slots.shift_remove(&sent.link);
            if let Some(session) = self.sessions.get_mut(&sent.link) {
                let user = session.user_hash();
                self.waiting.insert(user, sent.link);
                let rank = u32::try_from(self.waiting.len()).unwrap_or(u32::MAX);
                session.end_slot(rank);
            }
            self.fill_slots();
            return;
        }
        self.send_next(sent.link, find);
    }

    /// The waiting clients take the slots that are free.
    pub fn fill_slots(&mut self) {
        while self.slots.len() < MAX_SLOTS {
            let Some((_, link)) = self.waiting.shift_remove_index(0) else {
                return;
            };
            if let Some(session) = self.sessions.get_mut(&link) {
                self.slots.insert(link, 0);
                session.accept();
            }
        }
    }

    fn requested(&mut self, link: LinkId) {
        let Some(session) = self.sessions.get_mut(&link) else {
            return;
        };
        if self.slots.contains_key(&link) {
            // Already uploading to it, maybe for another file.
            session.accept();
            return;
        }
        let user = session.user_hash();
        if self.waiting.is_empty() && self.slots.len() < MAX_SLOTS {
            self.slots.insert(link, 0);
            session.accept();
            return;
        }
        self.waiting.insert(user, link);
        let rank = self.waiting.get_index_of(&user).map_or(0, |at| at.saturating_add(1));
        session.send_rank(u32::try_from(rank).unwrap_or(u32::MAX));
    }

    // One block at a time per slot, read and sent off the engine's task.
    fn send_next(&mut self, link: LinkId, find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>) {
        if self.sending.contains(&link) || !self.slots.contains_key(&link) {
            return;
        }
        let Some(session) = self.sessions.get_mut(&link) else {
            return;
        };
        let Some(block) = session.next_block() else {
            return;
        };
        let Some(file) = find(&block.file) else {
            return;
        };
        self.sending.insert(link);
        let (handle, sent) = (session.link().clone(), self.sent.clone());
        tokio::task::spawn_blocking(move || {
            let bytes = match send_block(&handle, &file.path, &block) {
                Ok(bytes) => bytes,
                Err(e) => {
                    tracing::warn!("Cannot read {} for {}: {e}", file.path.display(), handle.remote());
                    handle.close();
                    0
                }
            };
            let _ = sent.send(Sent { link: handle.id(), bytes });
        });
    }
}

fn send_block(link: &LinkHandle, path: &PathBuf, block: &Block) -> std::io::Result<u64> {
    let mut file = File::open(path)?;
    file.seek(SeekFrom::Start(block.start))?;
    let length = usize::try_from(block.end.saturating_sub(block.start)).unwrap_or(0);
    let mut data = vec![0u8; length];
    file.read_exact(&mut data)?;
    let wide = block.end > 0xFFFF_FFFF;
    let mut start = block.start;
    for chunk in data.chunks(CHUNK) {
        let end = start.saturating_add(u64::try_from(chunk.len()).unwrap_or(0));
        let packet = (SendingPart { file: block.file, start, end, data: chunk.to_vec() })
            .encode(wide)
            .map_err(std::io::Error::other)?;
        if !link.send_data(packet) {
            break;
        }
        start = end;
    }
    Ok(u64::try_from(length).unwrap_or(0))
}
