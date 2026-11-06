// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The greeting that opens every connection between clients: HELLO, answered with HELLOANSWER. Until it is over a
//! peer may send only small frames, so an unknown connection cannot make us allocate much.

use std::io;
use std::net::SocketAddr;
use std::time::Duration;

use tokio::time::timeout;

use crate::api::peer_info::{Identification, PeerInfo};
use crate::net::tcp_connector;
use crate::peer::peer_software;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::packet::frame_limits;
use crate::protocol::packet::opcodes::client;
use crate::protocol::packet::{Packet, protocol};

/// How long a peer has to greet, or to answer our greeting.
pub const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a connection may stay silent.
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(40);
/// How long a client greeted through the API has, after the hellos, to prove its user hash.
pub const IDENTIFY_WAIT: Duration = Duration::from_secs(10);

// The bits of the first misc options a client sets when it can prove its user hash.
const SECURE_IDENT_SHIFT: u32 = 16;

/// A client we called back on a server's request: we greet it first, answer its greeting, and keep the connection
/// until it goes quiet.
pub async fn called_back(connection: tcp_connector::Connection, ours: Hello) -> io::Result<()> {
    let tcp_connector::Connection { mut reader, mut writer, remote, .. } = connection;
    writer.write_packet(&encode(&ours.to_peer_at(&remote.ip()), false)?).await?;
    let mut greeted = false;
    loop {
        let limit = if greeted { frame_limits::client } else { frame_limits::client_handshake };
        let Ok(read) = timeout(IDLE_TIMEOUT, reader.read_packet(&limit)).await else {
            return Ok(());
        };
        let Some(packet) = read? else {
            return Ok(());
        };
        if is(&packet, client::HELLO) {
            writer.write_packet(&encode(&ours.to_peer_at(&remote.ip()), true)?).await?;
        }
        greeted |= is(&packet, client::HELLOANSWER) || is(&packet, client::HELLO);
    }
}

/// What `peer` said of itself at `address`; a peer that offers to prove its user hash is pending.
pub fn info(address: SocketAddr, peer: &Hello, obfuscated: bool) -> PeerInfo {
    let proves = peer.misc_options1() >> SECURE_IDENT_SHIFT & 0xF != 0;
    PeerInfo {
        address: address.to_string(),
        user_hash: peer.user_hash,
        nick: peer.nick().map(str::to_owned),
        software: peer_software::describe(peer),
        obfuscated,
        identification: if proves { Identification::Pending } else { Identification::NotSupported },
    }
}

fn is(packet: &Packet, opcode: u8) -> bool {
    packet.protocol() == protocol::EDONKEY && packet.opcode() == opcode
}

fn encode(hello: &Hello, answer: bool) -> io::Result<Packet> {
    hello.encode(answer).map_err(io::Error::other)
}
