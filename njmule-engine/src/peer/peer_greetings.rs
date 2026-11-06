// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The greeting that opens every connection between clients: HELLO, answered with HELLOANSWER. Until it is over a
//! peer may send only small frames, so an unknown connection cannot make us allocate much.

use std::net::SocketAddr;
use std::time::Duration;

use crate::api::peer_info::{Identification, PeerInfo};
use crate::peer::{identity_handshake, peer_software};
use crate::protocol::ed2k::hello::Hello;

/// How long a peer has to greet, or to answer our greeting.
pub const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
/// How long a connection may stay silent.
pub const IDLE_TIMEOUT: Duration = Duration::from_secs(40);
/// How long a client greeted through the API has, after the hellos, to prove its user hash.
pub const IDENTIFY_WAIT: Duration = Duration::from_secs(10);

/// What `peer` said of itself at `address`; a peer that offers to prove its user hash is pending.
pub fn info(address: SocketAddr, peer: &Hello, obfuscated: bool) -> PeerInfo {
    let proves = identity_handshake::versions(peer) != 0;
    PeerInfo {
        address: address.to_string(),
        user_hash: peer.user_hash,
        nick: peer.nick().map(str::to_owned),
        software: peer_software::describe(peer),
        obfuscated,
        identification: if proves { Identification::Pending } else { Identification::NotSupported },
    }
}
