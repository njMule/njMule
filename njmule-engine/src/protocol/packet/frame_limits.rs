// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Frame size limits per connection state. Before the hello exchange a peer may only greet, in a small frame, so an
//! unauthenticated connection cannot make us allocate much; afterwards the caps follow what each packet can legitimately
//! carry.

use crate::protocol::packet::opcodes::{client, extended};
use crate::protocol::packet::protocol;

/// Hello and hello answer.
pub const HANDSHAKE_MAX: usize = 4 * 1024;
/// Any other packet between clients.
pub const CLIENT_MAX: usize = 512 * 1024;
/// Hash sets of the largest files (MD4 and AICH part hashes).
pub const HASHSET_MAX: usize = 2 * 1024 * 1024;
/// Shared file lists.
pub const SHARED_FILES_MAX: usize = 4 * 1024 * 1024;
/// Any packet from a server (search results, source and server lists).
pub const SERVER_MAX: usize = 1024 * 1024;
/// File data: hash, 64-bit offsets and one block of 180 KiB.
pub const DATA_MAX: usize = 16 + 8 + 8 + 184_320;

/// Until hello and hello answer are exchanged.
pub fn client_handshake(protocol: u8, opcode: u8) -> Option<usize> {
    (protocol == protocol::EDONKEY && matches!(opcode, client::HELLO | client::HELLOANSWER)).then_some(HANDSHAKE_MAX)
}

/// An identified client.
pub fn client(protocol: u8, opcode: u8) -> Option<usize> {
    Some(match (protocol, opcode) {
        (protocol::EDONKEY, client::SENDINGPART) => DATA_MAX,
        (protocol::EDONKEY, client::ASKSHAREDFILESANSWER | client::ASKSHAREDFILESDIRANS) => SHARED_FILES_MAX,
        (protocol::EDONKEY, client::HASHSETANSWER) => HASHSET_MAX,
        (protocol::EMULE, extended::SENDINGPART_I64 | extended::COMPRESSEDPART | extended::COMPRESSEDPART_I64) => {
            DATA_MAX
        }
        (protocol::EMULE, extended::HASHSETANSWER2) => HASHSET_MAX,
        _ => CLIENT_MAX,
    })
}

/// A server connection.
pub fn server(_protocol: u8, _opcode: u8) -> Option<usize> {
    Some(SERVER_MAX)
}
