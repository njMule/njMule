// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Asks a client for the list of the files it shares, over a connection of our own: a hello, then the request, then the
//! list or the refusal.

use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::time::Duration;

use rand::CryptoRng;

use crate::api::search_result::{self, SearchResult};
use crate::net::routes::Routes;
use crate::net::tcp_connector;
use crate::protocol::ed2k::file_entry::{self, FileEntry};
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::io::DecodeError;
use crate::protocol::packet::opcodes::client;
use crate::protocol::packet::{Packet, frame_limits, packing, protocol};

/// A client answers within this time or not at all.
pub const TIMEOUT: Duration = Duration::from_secs(60);

// Entries a list may hold; more than any client shares.
const MAX_ENTRIES: usize = 100_000;

/// The files the client listening at `to` shares; fails when it refuses, cannot be reached or is silent.
pub async fn browse(
    to: SocketAddr,
    ours: &Hello,
    routes: &Routes,
    random: &mut (impl CryptoRng + Send),
) -> io::Result<Vec<SearchResult>> {
    let hello = ours.encode(false).map_err(io::Error::other)?;
    tokio::time::timeout(TIMEOUT, async {
        let connection = tcp_connector::connect_to_client(to, None, TIMEOUT, routes, random).await?;
        let (mut reader, mut writer) = (connection.reader, connection.writer);
        writer.write_packet(&hello).await?;
        let mut greeted = false;
        loop {
            let read = if greeted {
                reader.read_packet(&frame_limits::client).await?
            } else {
                reader.read_packet(&frame_limits::client_handshake).await?
            };
            let packet = read.ok_or_else(|| io::Error::new(ErrorKind::UnexpectedEof, "the connection closed"))?;
            let packet = packing::unpack(packet, frame_limits::SHARED_FILES_MAX).map_err(invalid)?;
            // Extended packets reuse the opcode of the refusal (a file comment); a packed list comes unpacked as one.
            let basic = packet.protocol() == protocol::EDONKEY;
            if basic && packet.opcode() == client::HELLOANSWER && !greeted {
                greeted = true;
                writer.write_packet(&Packet::empty(protocol::EDONKEY, client::ASKSHAREDFILES)).await?;
            } else if (basic || packet.protocol() == protocol::EMULE) && packet.opcode() == client::ASKSHAREDFILESANSWER
            {
                return files(&packet).map_err(invalid);
            } else if basic && packet.opcode() == client::ASKSHAREDDENIEDANS {
                return Err(io::Error::new(ErrorKind::PermissionDenied, "the client does not show its files"));
            }
        }
    })
    .await
    .map_err(|_| io::Error::new(ErrorKind::TimedOut, "the client did not answer"))?
}

/// The files of a shared files answer, `<u32 count>` then the entries, leaving out those that cannot be downloaded.
pub fn files(packet: &Packet) -> Result<Vec<SearchResult>, DecodeError> {
    let mut input = packet.reader();
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), file_entry::MIN_SIZE, MAX_ENTRIES)?;
    let mut files = Vec::with_capacity(count);
    for _ in 0..count {
        let entry = FileEntry::read(&mut input)?;
        if let (Some(name), Some(size)) = (entry.name(), entry.size())
            && search_result::is_downloadable(name, size)
        {
            files.push(SearchResult::new(entry.hash, name, size, 1, None, entry.file_type()));
        }
    }
    Ok(files)
}

fn invalid(e: DecodeError) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, e)
}
