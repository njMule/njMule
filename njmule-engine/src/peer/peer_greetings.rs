// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The greeting that opens every connection between clients: HELLO, answered with HELLOANSWER. Until it is over a
//! peer may send only small frames, so an unknown connection cannot make us allocate much.

use std::io::{self, ErrorKind};
use std::net::SocketAddr;
use std::time::Duration;

use tokio::net::TcpStream;
use tokio::time::timeout;

use crate::api::peer_info::{Identification, PeerInfo};
use crate::net::tcp_connection::{read_packet, write_packet};
use crate::peer::peer_software;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::packet::frame_limits;
use crate::protocol::packet::opcodes::client;
use crate::protocol::packet::{Packet, protocol};

/// How long a peer has to greet, or to answer our greeting.
pub const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a connection may stay silent.
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(40);

// The bits of the first misc options a client sets when it can prove its user hash.
const SECURE_IDENT_SHIFT: u32 = 16;

/// A client that connected to us: answers its HELLO with ours, then keeps the connection until it goes quiet.
pub async fn accepted(mut stream: TcpStream, ours: Hello) -> io::Result<()> {
    let remote = stream.peer_addr()?;
    let mut first = [0u8; 1];
    if stream.peek(&mut first).await? == 1 && !protocol::is_tcp(first[0]) {
        // An obfuscated stream: not taken yet.
        tracing::debug!("Closing an obfuscated connection from {remote}");
        return Ok(());
    }
    let Some(packet) = within(HELLO_TIMEOUT, read_packet(&mut stream, &frame_limits::client_handshake)).await? else {
        return Ok(());
    };
    if !is(&packet, client::HELLO) {
        return Err(io::Error::new(ErrorKind::InvalidData, "a connection must start with a hello"));
    }
    let peer = Hello::decode(&packet).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))?;
    tracing::debug!("Hello from {remote}: {}", peer_software::describe(&peer));
    write_packet(&mut stream, &encode(&ours.to_peer_at(&remote.ip()), true)?).await?;
    while let Ok(Ok(Some(_))) = timeout(IDLE_TIMEOUT, read_packet(&mut stream, &frame_limits::client)).await {}
    Ok(())
}

/// Says hello to the client at `address` and answers what it said of itself.
pub async fn hello(address: SocketAddr, ours: Hello) -> io::Result<PeerInfo> {
    let mut stream = within(HELLO_TIMEOUT, TcpStream::connect(address)).await?;
    write_packet(&mut stream, &encode(&ours.to_peer_at(&address.ip()), false)?).await?;
    loop {
        let packet = within(HELLO_TIMEOUT, read_packet(&mut stream, &frame_limits::client_handshake))
            .await?
            .ok_or_else(|| io::Error::new(ErrorKind::UnexpectedEof, "closed before the hello"))?;
        if is(&packet, client::HELLOANSWER) {
            let peer = Hello::decode(&packet).map_err(|e| io::Error::new(ErrorKind::InvalidData, e))?;
            return Ok(info(address, &peer, false));
        }
    }
}

/// What `peer` said of itself at `address`. Proving user hashes is not done yet: a peer that offers to is pending.
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

async fn within<T>(limit: Duration, work: impl Future<Output = io::Result<T>>) -> io::Result<T> {
    timeout(limit, work).await.map_err(|_| io::Error::new(ErrorKind::TimedOut, "no answer in time"))?
}
