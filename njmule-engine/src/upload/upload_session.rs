// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A client that may download from us, over the upload side of its link: it asks for a file, its parts and hash set
//! and a place in our queue; with a slot it asks for blocks.

use std::collections::{HashSet, VecDeque};
use std::net::IpAddr;

use crate::files::file_types;
use crate::files::hash::ed2k;
use crate::files::shared_files::SharedFile;
use crate::peer::peer_link::LinkHandle;
use crate::protocol::ed2k::client_messages::{self, FileName, FileStatus, RequestParts};
use crate::protocol::ed2k::file_entry::FileEntry;
use crate::protocol::ed2k::hello::{self, Hello};
use crate::protocol::io::{DecodeError, PacketWriter, StringEncoding};
use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::{Ed2kTagFormat, Tag, TagName, file_tag};

/// Blocks a client may have waiting at once.
pub const MAX_PENDING_BLOCKS: usize = 32;
/// The largest block a client may ask for.
pub const MAX_BLOCK: u64 = 3 * ed2k::EMBLOCKSIZE;
/// Files neither shared nor downloaded a client may ask us for on one connection before it is dropped.
pub const UNKNOWN_FILES_LIMIT: usize = 6;
/// The answer to a client browsing our files stays below this size, which every client takes.
pub const MAX_SHARED_LIST: usize = 1_000_000;
/// The pseudo-directory a client browsing by directory sees our files in.
pub const FILES_DIRECTORY: &str = "!Other";

/// The shared files a client may browse; none when the preferences do not let it look.
pub type Visible<'a> = &'a dyn Fn() -> Option<Vec<SharedFile>>;

/// One requested block of a file.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Block {
    pub file: [u8; 16],
    pub start: u64,
    pub end: u64,
}

/// What a session asks of the uploads.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Request {
    /// The client asks for an upload of the file.
    Upload([u8; 16]),
    /// The client asked for blocks.
    Blocks,
    /// The client does not want more data now.
    Stopped,
    /// The client broke the rules; the connection is closed.
    Misbehaved,
    /// The client asked for too many files we do not have on its connection, which closes.
    UnknownFiles,
}

pub struct UploadSession {
    link: LinkHandle,
    peer: Hello,
    file: Option<[u8; 16]>,
    pending: VecDeque<Block>,
    // Blocks sent in this slot are not sent again.
    sent: HashSet<Block>,
    unknown: HashSet<[u8; 16]>,
    // Our files were listed to the client: once a connection, as the list can take a megabyte.
    listed: bool,
    slot: bool,
    // Whether credits apply to the client: its identity proven, or it does not take part.
    trusted: bool,
    // The client asked for too many files we do not have; told once.
    flooded: bool,
}

impl UploadSession {
    pub fn new(link: LinkHandle, peer: Hello) -> Self {
        Self {
            link,
            peer,
            file: None,
            pending: VecDeque::new(),
            sent: HashSet::new(),
            unknown: HashSet::new(),
            listed: false,
            slot: false,
            trusted: false,
            flooded: false,
        }
    }

    pub fn user_hash(&self) -> [u8; 16] {
        self.peer.user_hash
    }

    pub fn link(&self) -> &LinkHandle {
        &self.link
    }

    pub fn file(&self) -> Option<[u8; 16]> {
        self.file
    }

    pub fn has_slot(&self) -> bool {
        self.slot
    }

    pub fn peer(&self) -> &Hello {
        &self.peer
    }

    pub fn is_trusted(&self) -> bool {
        self.trusted
    }

    pub fn set_trusted(&mut self, trusted: bool) {
        self.trusted = trusted;
    }

    pub fn has_pending_blocks(&self) -> bool {
        !self.pending.is_empty()
    }

    /// The next requested block, if any.
    pub fn next_block(&mut self) -> Option<Block> {
        let block = self.pending.pop_front()?;
        self.sent.insert(block.clone());
        Some(block)
    }

    /// Gives the client its slot.
    pub fn accept(&mut self) {
        self.slot = true;
        self.pending.clear();
        self.sent.clear();
        // What tells the client where it stands goes in order with the data: a client drops the data that comes after
        // the end of its slot.
        self.link.send_data(Packet::empty(protocol::EDONKEY, client::ACCEPTUPLOADREQ));
    }

    /// Tells the client its place in our queue.
    pub fn send_rank(&self, rank: u32) {
        let mut out = PacketWriter::new();
        out.u16(u16::try_from(rank).unwrap_or(u16::MAX)).bytes(&[0; 10]);
        self.link.send_data(Packet::written(protocol::EMULE, extended::QUEUERANKING, out));
    }

    /// Takes the slot away; the client goes back to waiting at `rank`.
    pub fn end_slot(&mut self, rank: u32) {
        if self.slot {
            self.slot = false;
            self.pending.clear();
            self.link.send_data(Packet::empty(protocol::EDONKEY, client::OUTOFPARTREQS));
        }
        self.send_rank(rank);
    }

    /// Handles a packet of the upload side; `find` gives our complete file of a hash, `visible` the files the client
    /// may browse.
    pub fn received(
        &mut self,
        packet: &Packet,
        ours: &Hello,
        find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>,
        visible: Visible<'_>,
    ) -> Result<Option<Request>, DecodeError> {
        let request = self.request(packet, ours, find, visible)?;
        if self.flooded {
            self.flooded = false;
            return Ok(Some(Request::UnknownFiles));
        }
        Ok(request)
    }

    fn request(
        &mut self,
        packet: &Packet,
        ours: &Hello,
        find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>,
        visible: Visible<'_>,
    ) -> Result<Option<Request>, DecodeError> {
        match (packet.protocol(), packet.opcode()) {
            (protocol::EDONKEY, client::HELLO) => {
                let again = Hello::decode(packet)?;
                // Becoming another client on the same connection would take over that client's place and slot.
                if again.user_hash != self.peer.user_hash {
                    return Ok(Some(Request::Misbehaved));
                }
                self.peer = again;
                if let Ok(answer) = ours.to_peer_at(&self.link.remote().ip()).encode(true) {
                    self.link.send(answer);
                }
            }
            (protocol::EDONKEY, client::REQUESTFILENAME) => {
                let hash = client_messages::file_of(packet)?;
                if let Some(file) = self.lookup(&hash, find) {
                    self.send_or_log((FileName { file: hash, name: file.name }).encode());
                }
            }
            (protocol::EDONKEY, client::SETREQFILEID) => {
                let hash = client_messages::file_of(packet)?;
                self.file = self.lookup(&hash, find).map(|file| file.hash);
                if self.file.is_some() {
                    // A complete file: no bits.
                    self.send_or_log((FileStatus { file: hash, part_count: 0, parts: Vec::new() }).encode());
                } else {
                    self.link.send(client_messages::for_file(client::FILEREQANSNOFIL, &hash));
                }
            }
            (protocol::EDONKEY, client::HASHSETREQUEST) => {
                let hash = client_messages::file_of(packet)?;
                if let Some(file) = self.lookup(&hash, find).filter(|file| !file.part_hashes.is_empty()) {
                    self.send_or_log((client_messages::HashSet { file: hash, parts: file.part_hashes }).encode());
                }
            }
            (protocol::EDONKEY, client::STARTUPLOADREQ) => {
                if packet.length() >= 16 {
                    let hash = client_messages::file_of(packet)?;
                    if let Some(file) = self.lookup(&hash, find) {
                        self.file = Some(file.hash);
                    }
                }
                return Ok(self.file.map(Request::Upload));
            }
            (protocol::EDONKEY, client::REQUESTPARTS) | (protocol::EMULE, extended::REQUESTPARTS_I64) => {
                return Ok(self.blocks(&RequestParts::decode(packet)?, find));
            }
            (protocol::EDONKEY, client::CANCELTRANSFER | client::END_OF_DOWNLOAD) => {
                self.slot = false;
                self.pending.clear();
                return Ok(Some(Request::Stopped));
            }
            (protocol::EDONKEY, client::ASKSHAREDFILES) => self.shared_files_asked(None, visible),
            (protocol::EDONKEY, client::ASKSHAREDDIRS) => self.shared_directories_asked(visible),
            (protocol::EDONKEY, client::ASKSHAREDFILESDIR) => {
                let mut input = packet.reader();
                let length = usize::from(input.u16()?);
                let directory = input.bytes(length)?.to_vec();
                self.shared_files_asked(Some(directory), visible);
            }
            (protocol::EMULE, extended::PUBLICIP_REQ) => {
                if let IpAddr::V4(ip) = self.link.remote().ip() {
                    self.link.send(Packet::of(protocol::EMULE, extended::PUBLICIP_ANSWER, ip.octets().to_vec()));
                }
            }
            (protocol::EMULE, extended::AICHFILEHASHREQ) => {
                let hash = client_messages::file_of(packet)?;
                if let Some(master) = self.lookup(&hash, find).and_then(|file| file.aich_hash) {
                    let mut out = PacketWriter::new();
                    out.hash16(&hash).bytes(&master);
                    self.link.send(Packet::written(protocol::EMULE, extended::AICHFILEHASHANS, out));
                }
            }
            // Without the AICH tree of the file at hand, the answer is the file hash alone.
            (protocol::EMULE, extended::AICHREQUEST) => {
                let mut input = packet.reader();
                let mut out = PacketWriter::new();
                out.hash16(&input.hash16()?);
                self.link.send(Packet::written(protocol::EMULE, extended::AICHANSWER, out));
            }
            _ => {}
        }
        Ok(None)
    }

    // Real paths stay private: every file shows in one pseudo-directory, the one of single shared files.
    fn shared_directories_asked(&self, visible: Visible<'_>) {
        if visible().is_none() {
            self.link.send(Packet::empty(protocol::EDONKEY, client::ASKSHAREDDENIEDANS));
            return;
        }
        let mut out = PacketWriter::new();
        out.u32(1);
        if out.string16(FILES_DIRECTORY, StringEncoding::Utf8).is_ok() {
            self.link.send(Packet::written(protocol::EDONKEY, client::ASKSHAREDDIRSANS, out));
        }
    }

    // The files the preferences let this client see, all of them or those of `directory`, echoed as asked. A client not
    // allowed gets an empty list or, for a directory, a refusal.
    fn shared_files_asked(&mut self, directory: Option<Vec<u8>>, visible: Visible<'_>) {
        let files = visible();
        if files.is_none() && directory.is_some() {
            self.link.send(Packet::empty(protocol::EDONKEY, client::ASKSHAREDDENIEDANS));
            return;
        }
        if self.listed {
            tracing::debug!("{} asked for our files again", self.link.remote());
            return;
        }
        self.listed = true;
        let mut files = files.unwrap_or_default();
        if directory.as_deref().is_some_and(|directory| directory != FILES_DIRECTORY.as_bytes()) {
            files.clear();
        }
        let list = listing(&files, self.peer.misc_options2() & hello::MISC2_LARGE_FILES != 0);
        tracing::debug!("Our files listed to {}", self.peer.nick().unwrap_or("?"));
        match directory {
            None => {
                self.link.send(Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESANSWER, list));
            }
            Some(directory) => {
                let mut out = PacketWriter::new();
                out.u16(u16::try_from(directory.len()).unwrap_or(0)).bytes(&directory).bytes(&list.into_byte_array());
                self.link.send(Packet::written(protocol::EDONKEY, client::ASKSHAREDFILESDIRANS, out));
            }
        }
    }

    // Only blocks of the file in upload, of at most three block sizes, are taken.
    fn blocks(&mut self, request: &RequestParts, find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>) -> Option<Request> {
        let file = self.file.filter(|file| self.slot && *file == request.file)?;
        let size = find(&file)?.size;
        for range in request.ranges.iter().filter(|range| !range.is_empty()) {
            let block = Block { file, start: range.start, end: range.end };
            if range.end.saturating_sub(range.start) > MAX_BLOCK || range.end > size {
                tracing::debug!("Refusing block {}-{} to {}", range.start, range.end, self.link.remote());
                continue;
            }
            if !self.pending.contains(&block) && !self.sent.contains(&block) && self.pending.len() < MAX_PENDING_BLOCKS
            {
                self.pending.push_back(block);
            }
        }
        (!self.pending.is_empty()).then_some(Request::Blocks)
    }

    // Files above 4 GB only for clients that can take them; a client asking for many files we do not have floods us.
    fn lookup(&mut self, hash: &[u8; 16], find: &dyn Fn(&[u8; 16]) -> Option<SharedFile>) -> Option<SharedFile> {
        let found = find(hash);
        if found.is_none() && self.unknown.insert(*hash) && self.unknown.len() >= UNKNOWN_FILES_LIMIT {
            tracing::debug!("{} asked for {} files we do not have", self.link.remote(), self.unknown.len());
            self.link.close();
            self.flooded = true;
        }
        let large = self.peer.misc_options2() & hello::MISC2_LARGE_FILES != 0;
        found.filter(|file| large || file.size <= ed2k::OLD_MAX_FILE_SIZE)
    }

    fn send_or_log(&self, packet: Result<Packet, crate::protocol::io::EncodeError>) {
        match packet {
            Ok(packet) => {
                self.link.send(packet);
            }
            Err(e) => tracing::warn!("Cannot answer {}: {e}", self.link.remote()),
        }
    }
}

/// `<u32 count>` and the entries of `files`, large ones only for a client that takes them; the list stops growing
/// past [`MAX_SHARED_LIST`] bytes.
pub fn listing(files: &[SharedFile], large: bool) -> PacketWriter {
    let mut entries = PacketWriter::new();
    let mut count = 0u32;
    for file in files {
        if (file.size > ed2k::OLD_MAX_FILE_SIZE && !large) || entries.size() > MAX_SHARED_LIST {
            continue;
        }
        let mut tags = vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: file.name.clone() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: file.size },
        ];
        if let Some(kind) = file_types::of(&file.name) {
            tags.push(Tag::String { name: TagName::id(file_tag::FILETYPE), value: kind.to_owned() });
        }
        let entry = FileEntry { hash: file.hash, client_id: 0, port: 0, tags };
        if entry.write(&mut entries, Ed2kTagFormat::New).is_ok() {
            count = count.saturating_add(1);
        }
    }
    let mut out = PacketWriter::new();
    out.u32(count).bytes(&entries.into_byte_array());
    out
}
