// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! One source of one download at a time: the connection, the file request, the place in the source's upload queue and
//! the transfer of blocks. What it learns goes to the downloads as [`Note`]s.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::time::{Duration, Instant};

use indexmap::IndexMap;
use rand::Rng;
use rangemap::RangeSet;

use crate::api::file_comment::FileComment;
use crate::download::block_inflater::BlockInflater;
use crate::download::download_queue::DownloadQueue;
use crate::files::part_file::{self, Block};
use crate::peer::peer_link::LinkHandle;
use crate::protocol::ed2k::client_messages::{
    self, BLOCKS_PER_REQUEST, CompressedPart, FileName, FileStatus, HashSet, Range, RequestParts, SendingPart,
};
use crate::protocol::ed2k::file_description;
use crate::protocol::ed2k::hello::{Hello, MISC2_SOURCE_EXCHANGE2};
use crate::protocol::ed2k::source_exchange as exchange;
use crate::protocol::io::{DecodeError, PacketWriter};
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::util::white_space;

/// Time to connect, greet and learn the parts of the source.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);
/// A queued connection that stays silent this long is closed; our place in the queue stays.
pub const QUEUED_TIMEOUT: Duration = Duration::from_secs(60);
/// A slot whose blocks bring no data for this long is left; a silent connection closes after it too.
pub const DATA_TIMEOUT: Duration = Duration::from_secs(100);
/// A slot is kept this long with nothing to ask, while the data in flight is written and checked.
pub const IDLE_SLOT: Duration = Duration::from_secs(30);
/// Data of no block we asked for, this many times each within [`UNEXPECTED_GAP`], ends a slot.
pub const MAX_UNEXPECTED: u32 = 3;
pub const UNEXPECTED_GAP: Duration = Duration::from_secs(10);
/// Compressed blocks that do not inflate, in one session, that end it: the source is broken.
pub const MAX_CORRUPT_STREAMS: u32 = 3;
/// The longest name of a file a source gives that is kept, in characters.
pub const MAX_NAME: usize = 255;

/// How a session ended, which decides when the source is asked again.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// The source keeps us in its upload queue.
    Queued,
    /// Nothing to do with the source now; it may have more later.
    Later,
    /// The source could not be reached or greeted.
    Unreachable,
    /// The source lacks the file or broke the protocol.
    Gone,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Connecting,
    Greeting,
    Requesting,
    Queued,
    Downloading,
    Ended,
}

/// What a session tells the downloads.
#[derive(Debug)]
pub enum Note {
    /// The source sent `bytes` of the file, carried in `transferred` bytes.
    Received {
        bytes: u64,
        transferred: u64,
    },
    Ended {
        outcome: Outcome,
        reason: String,
    },
    /// The source told which parts it has.
    PartsKnown,
    AichAnswered {
        part: usize,
        master: [u8; 20],
        data: Vec<u8>,
    },
    AichUnavailable {
        part: usize,
    },
    /// The session let blocks go that other sources may now download.
    Released,
    /// The source announced the AICH master hash of the file.
    AichAnnounced([u8; 20]),
    /// The source told the name it gives the file, made fit to show.
    Named(String),
    /// The source has nothing for the download asked for: no part we need, or not the file at all. The downloads have
    /// the session ask for another ([`DownloadSession::ask_for`]) or give up ([`DownloadSession::give_up`]).
    NothingHere {
        has_file: bool,
    },
    /// The source told what it says of the file.
    Commented(FileComment),
    /// The source exchanges sources: the downloads may have the session ask it for those it knows
    /// ([`DownloadSession::ask_sources`]), the extended way when both of us take it.
    SourcesWanted {
        extended: bool,
    },
    /// The source told the sources it knows of a file the session asked it about.
    SourcesFound(exchange::Answer),
}

/// What a session works with.
pub struct Context<'a, R: Rng> {
    pub queue: &'a mut DownloadQueue,
    pub ours: &'a Hello,
    pub random: &'a mut R,
    pub now: Instant,
}

// One requested block and the bytes of it still to come.
struct Pending {
    block: Block,
    missing: RangeSet<u64>,
    inflater: Option<BlockInflater>,
    // Compressed bytes of the block's stream received so far.
    compressed: u64,
}

impl Pending {
    fn new(block: Block) -> Self {
        let mut missing = RangeSet::new();
        part_file::insert(&mut missing, block.start..block.end);
        Self { block, missing, inflater: None, compressed: 0 }
    }

    fn contains(&self, start: u64, end: u64) -> bool {
        start >= self.block.start && end <= self.block.end
    }
}

pub struct DownloadSession {
    download: [u8; 16],
    address: SocketAddr,
    state: State,
    link: Option<LinkHandle>,
    peer: Option<Hello>,
    source_parts: Option<Vec<bool>>,
    upload_requested: bool,
    accepted: bool,
    queue_rank: u32,
    // When the slot ran out of blocks to ask for.
    idle_since: Option<Instant>,
    pending: IndexMap<u64, Pending>,
    // The part whose AICH recovery data we asked for.
    aich_asked: Option<usize>,
    // When the slot last made progress: blocks asked for, or data of them received.
    progress_at: Instant,
    unexpected: u32,
    unexpected_at: Option<Instant>,
    corrupt_streams: u32,
    // The compressed bytes still to come of streams that failed, by block start: dropped as they arrive.
    broken: HashMap<u64, u64>,
    notes: Vec<Note>,
    // The downloads asked for on this connection, whose hash sets still count after a swap to another.
    asked: Vec<[u8; 16]>,
    // The files the source was asked for other sources of, whose answers count.
    sources_asked: Vec<[u8; 16]>,
}

impl DownloadSession {
    pub fn new(download: [u8; 16], address: SocketAddr, now: Instant) -> Self {
        Self {
            download,
            address,
            state: State::Connecting,
            link: None,
            peer: None,
            source_parts: None,
            upload_requested: false,
            accepted: false,
            queue_rank: 0,
            idle_since: None,
            pending: IndexMap::new(),
            aich_asked: None,
            progress_at: now,
            unexpected: 0,
            unexpected_at: None,
            corrupt_streams: 0,
            broken: HashMap::new(),
            notes: Vec::new(),
            asked: vec![download],
            sources_asked: Vec::new(),
        }
    }

    /// The session, for diagnostics: the source, where it stands and the blocks it waits for.
    pub fn describe(&self) -> String {
        let state = match self.state {
            State::Connecting => "CONNECTING",
            State::Greeting => "GREETING",
            State::Requesting => "REQUESTING",
            State::Queued => "QUEUED",
            State::Downloading => "DOWNLOADING",
            State::Ended => "ENDED",
        };
        let mut text = format!("{} {state}", self.address);
        for pending in self.pending.values() {
            let missing: Vec<String> =
                pending.missing.iter().map(|gap| format!("{}..{}", gap.start, gap.end)).collect();
            text.push_str(&format!(
                " [{}-{} missing {}{}]",
                pending.block.start,
                pending.block.end,
                missing.join(","),
                if pending.inflater.is_some() { " inflating" } else { "" }
            ));
        }
        text
    }

    pub fn take_notes(&mut self) -> Vec<Note> {
        std::mem::take(&mut self.notes)
    }

    pub fn address(&self) -> SocketAddr {
        self.address
    }

    pub fn state(&self) -> State {
        self.state
    }

    pub fn link(&self) -> Option<&LinkHandle> {
        self.link.as_ref()
    }

    /// The source's hello, once it greeted us.
    pub fn peer(&self) -> Option<&Hello> {
        self.peer.as_ref()
    }

    pub fn peer_hash(&self) -> Option<[u8; 16]> {
        self.peer.as_ref().map(|peer| peer.user_hash)
    }

    /// The connect options the source announced: supports, requests, requires obfuscation.
    pub fn peer_crypt_options(&self) -> u8 {
        self.peer.as_ref().map_or(0, |peer| u8::try_from(peer.misc_options2() >> 7 & 0x07).unwrap_or(0))
    }

    /// Our place in the source's queue, once it told us.
    pub fn queue_rank(&self) -> u32 {
        self.queue_rank
    }

    /// Whether the source was asked for an upload, or offered us one.
    pub fn upload_asked(&self) -> bool {
        self.upload_requested
    }

    /// The parts the source reported, if it did.
    pub fn source_parts(&self) -> Option<&[bool]> {
        self.source_parts.as_deref()
    }

    /// Whether the source can send AICH recovery data of `part`: it greeted us as able to and has the part.
    pub fn may_recover(&self, part: usize) -> bool {
        self.link.is_some()
            && self.peer.as_ref().is_some_and(Hello::supports_aich)
            && self.source_parts.as_deref().is_some_and(|parts| part_file::has(parts, part))
    }

    /// Asks the source for the AICH recovery data of `part`, under the trusted `master` hash.
    pub fn request_aich(&mut self, part: usize, master: &[u8; 20]) {
        let Ok(number) = u16::try_from(part) else {
            return;
        };
        self.aich_asked = Some(part);
        let mut out = PacketWriter::new();
        out.hash16(&self.download).u16(number).bytes(master);
        self.send(Packet::written(protocol::EMULE, extended::AICHREQUEST, out));
    }

    /// The connection is open: we say hello.
    pub fn opened(&mut self, link: LinkHandle, ours: &Hello) {
        if self.state != State::Connecting {
            link.close();
            return;
        }
        link.set_timeout(HANDSHAKE_TIMEOUT);
        self.state = State::Greeting;
        match ours.to_peer_at(&link.remote().ip()).encode(false) {
            Ok(hello) => {
                link.send(hello);
            }
            Err(e) => tracing::warn!("Cannot greet {}: {e}", self.address),
        }
        self.link = Some(link);
    }

    /// Asks the source for `download` instead, on the same connection, after it had nothing for the last one.
    pub fn ask_for<R: Rng>(&mut self, download: [u8; 16], ctx: &mut Context<'_, R>) {
        if self.state == State::Ended {
            return;
        }
        tracing::debug!("Asking {} for another download", self.address);
        self.source_parts = None;
        self.download = download;
        if !self.asked.contains(&download) {
            self.asked.push(download);
        }
        self.request_file(ctx);
    }

    /// Ends the session of a source that has nothing for the download, and no other to be asked for.
    pub fn give_up<R: Rng>(&mut self, has_file: bool, ctx: &mut Context<'_, R>) {
        if has_file {
            self.close("the source has no part we need", ctx);
        } else {
            self.end(Outcome::Gone, "the source does not have the file", ctx);
        }
    }

    /// The source answered our hello.
    pub fn greeted<R: Rng>(&mut self, hello: Hello, ctx: &mut Context<'_, R>) {
        if self.state != State::Greeting {
            return;
        }
        let ourselves = hello.user_hash == ctx.ours.user_hash;
        self.peer = Some(hello);
        if ourselves {
            self.end(Outcome::Gone, "the source is ourselves", ctx);
            return;
        }
        self.state = State::Requesting;
        self.request_file(ctx);
    }

    /// Takes the download side of a link whose client greeted us. `slot_offered` tells a source that connected to give
    /// us a slot from one that still has to be asked for an upload.
    pub fn adopt<R: Rng>(
        &mut self,
        link: LinkHandle,
        hello: Hello,
        known_parts: Option<Vec<bool>>,
        slot_offered: bool,
        ctx: &mut Context<'_, R>,
    ) {
        link.set_timeout(HANDSHAKE_TIMEOUT);
        self.link = Some(link);
        self.peer = Some(hello);
        self.state = State::Queued;
        self.upload_requested = slot_offered;
        match known_parts {
            Some(parts) => self.set_parts(parts, ctx),
            None => self.request_file(ctx),
        }
    }

    /// Ends the session; a source that gave us a slot is told we leave it.
    pub fn close<R: Rng>(&mut self, reason: &str, ctx: &mut Context<'_, R>) {
        if self.state == State::Downloading {
            self.send(Packet::empty(protocol::EDONKEY, client::CANCELTRANSFER));
        }
        self.end(Outcome::Later, reason, ctx);
    }

    /// Leaves a slot whose blocks brought no data for [`DATA_TIMEOUT`], and an idle slot whose time is over.
    pub fn check<R: Rng>(&mut self, ctx: &mut Context<'_, R>) {
        if self.state != State::Downloading {
            return;
        }
        if !self.pending.is_empty() && ctx.now.saturating_duration_since(self.progress_at) >= DATA_TIMEOUT {
            self.close(&format!("no data for {} s", DATA_TIMEOUT.as_secs()), ctx);
            return;
        }
        if let Some(since) = self.idle_since
            && self.pending.is_empty()
            && ctx.now.saturating_duration_since(since) >= IDLE_SLOT
        {
            self.idle_since = None;
            if self.has_held_part(ctx.queue) {
                // The slot stays while a part it has waits for its recovery data.
                self.request_blocks(ctx);
            } else {
                self.close("nothing more to download from this source", ctx);
            }
        }
    }

    /// The download changed (data written, a part found corrupt): ask for blocks if idle.
    pub fn wake<R: Rng>(&mut self, ctx: &mut Context<'_, R>) {
        if self.state == State::Downloading && self.pending.is_empty() {
            self.request_blocks(ctx);
        }
    }

    pub fn received<R: Rng>(&mut self, packet: &Packet, ctx: &mut Context<'_, R>) {
        if self.state == State::Ended {
            return;
        }
        let handled = match packet.protocol() {
            protocol::EMULE => self.received_extended(packet, ctx),
            protocol::EDONKEY => self.received_basic(packet, ctx),
            _ => Ok(()),
        };
        if let Err(e) = handled {
            tracing::debug!("Bad packet from {}: {e}", self.address);
            self.end(Outcome::Gone, &format!("protocol error: {e}"), ctx);
        }
    }

    /// The connection closed: a queued source keeps our place, a lost slot is gone.
    pub fn closed<R: Rng>(&mut self, cause: Option<&str>, ctx: &mut Context<'_, R>) {
        if self.state == State::Ended {
            return;
        }
        let outcome = match self.state {
            State::Queued => Outcome::Queued,
            State::Downloading => Outcome::Later,
            _ => Outcome::Unreachable,
        };
        self.link = None;
        let reason =
            cause.map_or_else(|| "the connection closed".to_owned(), |c| format!("the connection closed ({c})"));
        self.end(outcome, &reason, ctx);
    }

    /// The connection could not be opened.
    pub fn unreachable<R: Rng>(&mut self, reason: &str, ctx: &mut Context<'_, R>) {
        self.end(Outcome::Unreachable, &format!("cannot connect: {reason}"), ctx);
    }

    fn received_basic<R: Rng>(&mut self, packet: &Packet, ctx: &mut Context<'_, R>) -> Result<(), DecodeError> {
        match packet.opcode() {
            client::FILESTATUS => {
                let status = FileStatus::decode(packet)?;
                let Some(size) = ctx.queue.get(&self.download).map(|d| d.file.size()) else {
                    return Ok(());
                };
                if status.file != self.download {
                    return Ok(());
                }
                if status.fits(size) {
                    self.set_parts(status.data_parts(size), ctx);
                } else {
                    let reason = format!("the source sent {} parts for the file", status.part_count);
                    self.end(Outcome::Gone, &reason, ctx);
                }
            }
            client::FILEREQANSNOFIL => {
                if client_messages::file_of(packet)? == self.download {
                    self.nothing_here(false, ctx);
                }
            }
            client::HASHSETANSWER => self.hash_set(HashSet::decode(packet)?, ctx),
            client::ACCEPTUPLOADREQ => {
                self.accepted = true;
                self.start_downloading(ctx);
            }
            client::QUEUERANK => self.queued(client_messages::decode_queue_rank(packet)?),
            client::SENDINGPART => self.data(SendingPart::decode(packet)?, ctx),
            client::OUTOFPARTREQS => self.back_to_queue("the source ended our upload session", ctx),
            client::CANCELTRANSFER => self.back_to_queue("the source cancelled the transfer", ctx),
            client::REQFILENAMEANSWER => {
                let answer = FileName::decode(packet)?;
                if answer.file == self.download
                    && let Some(name) = shown(&answer.name)
                {
                    self.notes.push(Note::Named(name));
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn received_extended<R: Rng>(&mut self, packet: &Packet, ctx: &mut Context<'_, R>) -> Result<(), DecodeError> {
        match packet.opcode() {
            extended::QUEUERANKING => self.queued(client_messages::decode_queue_rank(packet)?),
            extended::SENDINGPART_I64 => self.data(SendingPart::decode(packet)?, ctx),
            extended::COMPRESSEDPART | extended::COMPRESSEDPART_I64 => {
                self.compressed(CompressedPart::decode(packet)?, ctx)?;
            }
            extended::AICHANSWER => self.aich_answer(packet)?,
            extended::ANSWERSOURCES2 => {
                let extended = self.peer.as_ref().is_some_and(Hello::supports_extended_source_exchange)
                    && packet.reader().u8()? == exchange::EXTENDED_VERSION;
                let answer = if extended {
                    exchange::Answer::decode_extended(packet)?
                } else {
                    exchange::Answer::decode(packet)?
                };
                // Only answers we asked for, about a file we asked for.
                if let Some(at) = self.sources_asked.iter().position(|file| *file == answer.file) {
                    self.sources_asked.swap_remove(at);
                    self.notes.push(Note::SourcesFound(answer));
                }
            }
            extended::FILEDESC => self.notes.push(Note::Commented(file_description::decode(packet)?)),
            extended::AICHFILEHASHANS => {
                let mut input = packet.reader();
                if input.hash16()? == self.download {
                    let master: [u8; 20] =
                        input.bytes(20)?.try_into().map_err(|_| DecodeError::new("short AICH hash"))?;
                    self.notes.push(Note::AichAnnounced(master));
                }
            }
            _ => {}
        }
        Ok(())
    }

    // An answer of 16 bytes says the source has no recovery data; only an answer we asked for counts.
    fn aich_answer(&mut self, packet: &Packet) -> Result<(), DecodeError> {
        let mut input = packet.reader();
        let file = input.hash16()?;
        let asked = self.aich_asked;
        let Some(asked) = asked.filter(|_| file == self.download) else {
            self.aich_asked = None;
            return Ok(());
        };
        if input.remaining() == 0 {
            self.aich_asked = None;
            self.notes.push(Note::AichUnavailable { part: asked });
            return Ok(());
        }
        let part = usize::from(input.u16()?);
        let master: [u8; 20] = input.bytes(20)?.try_into().map_err(|_| DecodeError::new("short AICH hash"))?;
        if part == asked {
            self.aich_asked = None;
            self.notes.push(Note::AichAnswered { part, master, data: input.rest().to_vec() });
        }
        Ok(())
    }

    fn request_file<R: Rng>(&mut self, ctx: &mut Context<'_, R>) {
        let Some(download) = ctx.queue.get(&self.download) else {
            return;
        };
        let (has_aich, has_hash_set) = (download.file.aich_hash().is_some(), download.file.has_hash_set());
        let file = self.download;
        self.send(client_messages::for_file(client::REQUESTFILENAME, &file));
        self.send(client_messages::for_file(client::SETREQFILEID, &file));
        // Without a trusted AICH hash, the sources' announcements may make one.
        if !has_aich && self.peer.as_ref().is_some_and(Hello::supports_aich) {
            let mut out = PacketWriter::new();
            out.hash16(&file);
            self.send(Packet::written(protocol::EMULE, extended::AICHFILEHASHREQ, out));
        }
        if !has_hash_set {
            self.send(client_messages::for_file(client::HASHSETREQUEST, &file));
        }
        let exchanges = self.peer.as_ref().is_some_and(|peer| peer.misc_options2() & MISC2_SOURCE_EXCHANGE2 != 0);
        if exchanges {
            let extended = self.peer.as_ref().is_some_and(Hello::supports_extended_source_exchange)
                && ctx.ours.supports_extended_source_exchange();
            self.notes.push(Note::SourcesWanted { extended });
        }
    }

    /// Asks the source for the other sources it knows of the file asked for, the extended way when both take it.
    pub fn ask_sources(&mut self, extended: bool) {
        let file = self.download;
        if !self.sources_asked.contains(&file) {
            self.sources_asked.push(file);
        }
        let version = if extended { exchange::EXTENDED_VERSION } else { exchange::VERSION };
        self.send(exchange::request_in_version(&file, version));
    }

    fn set_parts<R: Rng>(&mut self, parts: Vec<bool>, ctx: &mut Context<'_, R>) {
        let needed = ctx.queue.get(&self.download).is_some_and(|d| {
            (0..d.file.part_count()).any(|part| part_file::has(&parts, part) && !d.file.is_part_complete(part))
        });
        self.source_parts = Some(parts);
        self.notes.push(Note::PartsKnown);
        if !needed {
            self.nothing_here(true, ctx);
            return;
        }
        if !self.upload_requested {
            self.upload_requested = true;
            self.state = State::Queued;
            self.set_timeout(QUEUED_TIMEOUT);
            let file = self.download;
            self.send(client_messages::for_file(client::STARTUPLOADREQ, &file));
        }
        self.start_downloading(ctx);
    }

    // The hash set of a download asked for before a swap to another still counts, while the download is ours.
    fn hash_set<R: Rng>(&mut self, set: HashSet, ctx: &mut Context<'_, R>) {
        if !self.asked.contains(&set.file) {
            return;
        }
        let Some(download) = ctx.queue.get_mut(&set.file) else {
            return;
        };
        if !download.file.has_hash_set() && download.file.set_hash_set(set.parts) {
            ctx.queue.hash_set_known(&set.file);
        }
    }

    // One file request at a time: another download may be asked for until an upload is.
    fn nothing_here<R: Rng>(&mut self, has_file: bool, ctx: &mut Context<'_, R>) {
        if self.upload_requested || self.state == State::Ended {
            self.give_up(has_file, ctx);
        } else {
            self.notes.push(Note::NothingHere { has_file });
        }
    }

    fn queued(&mut self, rank: u32) {
        if self.state == State::Queued {
            self.queue_rank = rank;
            tracing::debug!("Queued at {rank} on {}", self.address);
        }
    }

    fn start_downloading<R: Rng>(&mut self, ctx: &mut Context<'_, R>) {
        if self.state != State::Queued || !self.accepted || self.source_parts.is_none() {
            return;
        }
        self.state = State::Downloading;
        self.queue_rank = 0;
        self.set_timeout(DATA_TIMEOUT);
        tracing::debug!("Downloading from {}", self.address);
        self.request_blocks(ctx);
    }

    fn request_blocks<R: Rng>(&mut self, ctx: &mut Context<'_, R>) {
        if self.state != State::Downloading {
            return;
        }
        let Some(download) = ctx.queue.get_mut(&self.download) else {
            self.close("the download is gone", ctx);
            return;
        };
        if !download.is_active() {
            self.close("the download is not active", ctx);
            return;
        }
        let wanted = BLOCKS_PER_REQUEST.saturating_sub(self.pending.len());
        let parts = self.source_parts.clone().unwrap_or_default();
        let mut next = if wanted > 0 { download.file.next_blocks(&parts, wanted, ctx.random) } else { Vec::new() };
        if next.is_empty() && wanted > 0 {
            // Every missing block is asked of a source already: near the end, some of theirs are asked of this one too.
            let own: Vec<Block> = self.pending.values().map(|pending| pending.block).collect();
            next = download.file.endgame_blocks(&parts, wanted, &own);
        }
        if next.is_empty() {
            if self.pending.is_empty() && self.idle_since.is_none() {
                self.idle_since = Some(ctx.now);
            }
            return;
        }
        self.idle_since = None;
        let mut ranges = [Range::default(); BLOCKS_PER_REQUEST];
        let mut wide = false;
        for (range, block) in ranges.iter_mut().zip(&next) {
            *range = Range { start: block.start, end: block.end };
            wide |= block.end > 0xFFFF_FFFF;
        }
        for block in next {
            self.pending.insert(block.start, Pending::new(block));
        }
        self.progress_at = ctx.now;
        match (RequestParts { file: self.download, ranges }).encode(wide) {
            Ok(packet) => self.send(packet),
            Err(e) => tracing::warn!("Cannot ask {} for blocks: {e}", self.address),
        }
    }

    fn data<R: Rng>(&mut self, part: SendingPart, ctx: &mut Context<'_, R>) {
        if part.file != self.download {
            self.unexpected(part.start, ctx);
            return;
        }
        let length = u64::try_from(part.data.len()).unwrap_or(u64::MAX);
        self.accept(part.start, &part.data, length, ctx);
    }

    fn compressed<R: Rng>(&mut self, part: CompressedPart, ctx: &mut Context<'_, R>) -> Result<(), DecodeError> {
        if self.state != State::Downloading {
            return Ok(());
        }
        let length = u64::try_from(part.chunk.len()).unwrap_or(u64::MAX);
        let ours = part.file == self.download;
        if ours && self.drops_broken(part.block_start, length) {
            return Ok(());
        }
        let Some(block) = self.pending.get_mut(&part.block_start).filter(|_| ours) else {
            self.unexpected(part.block_start, ctx);
            return Ok(());
        };
        let inflater =
            block.inflater.get_or_insert_with(|| BlockInflater::new(block.block.start, block.block.length()));
        // zlib may hold the output of a chunk back: the chunk alone is progress.
        self.progress_at = ctx.now;
        block.compressed = block.compressed.saturating_add(length);
        let position = inflater.position();
        let data = match inflater.inflate(&part.chunk) {
            Ok(data) => data,
            Err(e) => return self.corrupt(part.block_start, u64::from(part.compressed_size), e, ctx),
        };
        let ended = inflater.finished();
        self.accept(position, &data, length, ctx);
        // A stream that ended before its block did brings nothing more: the rest is asked for again now.
        if ended
            && self.state == State::Downloading
            && let Some(rest) = self.pending.shift_remove(&part.block_start)
        {
            if let Some(download) = ctx.queue.get_mut(&self.download) {
                for gap in rest.missing.iter() {
                    download.file.release(gap.start, gap.end);
                }
            }
            self.request_blocks(ctx);
        }
        Ok(())
    }

    // A stream that does not inflate costs its block, asked for again; a source that keeps sending broken ones is
    // broken itself.
    fn corrupt<R: Rng>(
        &mut self,
        block_start: u64,
        compressed_size: u64,
        cause: DecodeError,
        ctx: &mut Context<'_, R>,
    ) -> Result<(), DecodeError> {
        self.corrupt_streams = self.corrupt_streams.saturating_add(1);
        if self.corrupt_streams >= MAX_CORRUPT_STREAMS {
            return Err(cause);
        }
        tracing::debug!("Compressed block {block_start} from {}: {cause}", self.address);
        if let Some(block) = self.pending.shift_remove(&block_start) {
            if let Some(download) = ctx.queue.get_mut(&self.download) {
                for gap in block.missing.iter() {
                    download.file.release(gap.start, gap.end);
                }
            }
            let left = compressed_size.saturating_sub(block.compressed);
            if left > 0 {
                self.broken.insert(block_start, left);
            }
        }
        self.request_blocks(ctx);
        Ok(())
    }

    // Whether the chunk belongs to the rest of a stream that failed.
    fn drops_broken(&mut self, block_start: u64, length: u64) -> bool {
        let Some(left) = self.broken.get_mut(&block_start) else {
            return false;
        };
        if *left > length {
            *left = left.saturating_sub(length);
        } else {
            self.broken.remove(&block_start);
        }
        true
    }

    // Data counts only inside a block we asked for and still wait for.
    fn accept<R: Rng>(&mut self, start: u64, data: &[u8], transferred: u64, ctx: &mut Context<'_, R>) {
        if self.state != State::Downloading || data.is_empty() {
            return;
        }
        // Cancelled behind the session's back: nothing it receives can be kept.
        if ctx.queue.get(&self.download).is_none() {
            self.close("the download is gone", ctx);
            return;
        }
        let bytes = u64::try_from(data.len()).unwrap_or(u64::MAX);
        let end = start.saturating_add(bytes);
        let Some(target) = self.pending.values().position(|pending| pending.contains(start, end)) else {
            self.unexpected(start, ctx);
            return;
        };
        self.progress_at = ctx.now;
        ctx.queue.received(&self.download, start, data, Some(self.address.ip()));
        self.notes.push(Note::Received { bytes, transferred });
        let Some((&key, pending)) = self.pending.get_index_mut(target) else {
            return;
        };
        part_file::remove(&mut pending.missing, start..end);
        if pending.missing.is_empty() {
            self.pending.shift_remove(&key);
            self.request_blocks(ctx);
        }
    }

    // A few packets of data not asked for may still be on the way; more, close together, mean the source is off track.
    fn unexpected<R: Rng>(&mut self, start: u64, ctx: &mut Context<'_, R>) {
        if self.state != State::Downloading {
            return;
        }
        tracing::debug!("Data at {start} from {} was not requested", self.address);
        if self.unexpected_at.is_none_or(|at| ctx.now.saturating_duration_since(at) > UNEXPECTED_GAP) {
            self.unexpected = 0;
        }
        self.unexpected_at = Some(ctx.now);
        self.unexpected = self.unexpected.saturating_add(1);
        if self.unexpected >= MAX_UNEXPECTED {
            self.close("the source sends data we did not ask for", ctx);
        }
    }

    fn back_to_queue<R: Rng>(&mut self, reason: &str, ctx: &mut Context<'_, R>) {
        if self.state != State::Downloading {
            return;
        }
        tracing::debug!("{}: {reason}", self.address);
        let had_blocks = !self.pending.is_empty();
        self.release_blocks(ctx);
        self.idle_since = None;
        self.accepted = false;
        self.state = State::Queued;
        self.set_timeout(QUEUED_TIMEOUT);
        if had_blocks {
            self.notes.push(Note::Released);
        }
    }

    fn end<R: Rng>(&mut self, outcome: Outcome, reason: &str, ctx: &mut Context<'_, R>) {
        if self.state == State::Ended {
            return;
        }
        self.state = State::Ended;
        self.idle_since = None;
        self.release_blocks(ctx);
        if let Some(link) = self.link.take() {
            link.close();
        }
        self.notes.push(Note::Ended { outcome, reason: reason.to_owned() });
    }

    // The blocks still awaited go back to the download, for any source.
    fn release_blocks<R: Rng>(&mut self, ctx: &mut Context<'_, R>) {
        let blocks: Vec<Block> = self.pending.drain(..).map(|(_, pending)| pending.block).collect();
        if let Some(download) = ctx.queue.get_mut(&self.download) {
            for block in blocks {
                download.file.release(block.start, block.end);
            }
        }
    }

    fn has_held_part(&self, queue: &DownloadQueue) -> bool {
        let (Some(parts), Some(download)) = (&self.source_parts, queue.get(&self.download)) else {
            return false;
        };
        (0..download.file.part_count()).any(|part| {
            part_file::has(parts, part) && download.file.is_held(part) && !download.file.is_part_complete(part)
        })
    }

    fn set_timeout(&self, timeout: Duration) {
        if let Some(link) = &self.link {
            link.set_timeout(timeout);
        }
    }

    fn send(&self, packet: Packet) {
        if let Some(link) = &self.link {
            link.send(packet);
        }
    }
}

/// A name from the network as text to show: control characters made spaces, at most [`MAX_NAME`] characters.
pub fn shown(name: &str) -> Option<String> {
    let shown: String =
        white_space::strip(name).chars().take(MAX_NAME).map(|c| if c.is_control() { ' ' } else { c }).collect();
    let shown = white_space::strip(&shown);
    (!shown.is_empty()).then(|| shown.to_owned())
}
