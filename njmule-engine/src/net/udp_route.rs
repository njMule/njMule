// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where the datagrams of a UDP socket go: straight to the network, through the relay of a SOCKS5 proxy, or nowhere
//! while that relay is being reached, so that none leaves outside the proxy.

use std::fmt;
use std::net::SocketAddr;
use std::sync::Arc;

use crate::net::socks5_udp::{self, Socks5Udp};

#[derive(Debug, Clone)]
pub enum UdpRoute {
    /// Straight to the network, and from it.
    Direct,
    /// Nothing goes or comes.
    Held,
    /// Through the relay of an association; nothing goes once it has ended.
    Through(Arc<Socks5Udp>),
}

impl UdpRoute {
    pub fn is_relayed(&self) -> bool {
        matches!(self, Self::Through(_))
    }

    /// Where the datagram that carries `data` to `to` (an IPv4 address) goes, and what it holds, if one may go now.
    pub fn outgoing(&self, to: SocketAddr, data: &[u8]) -> Option<(SocketAddr, Vec<u8>)> {
        match self {
            Self::Direct => Some((to, data.to_vec())),
            Self::Held => None,
            Self::Through(relay) => match to {
                SocketAddr::V4(v4) if relay.is_open() => Some((relay.relay(), socks5_udp::wrap(v4, data))),
                _ => None,
            },
        }
    }

    /// The sender and payload of `datagram`, just received from `from`; none for one to drop, such as one that reached
    /// the socket outside the relay, whose answer would leave outside the proxy.
    pub fn incoming<'d>(&self, from: SocketAddr, datagram: &'d [u8]) -> Option<(SocketAddr, &'d [u8])> {
        match self {
            Self::Direct => Some((from, datagram)),
            Self::Held => None,
            Self::Through(relay) => (from == relay.relay()).then(|| socks5_udp::unwrap(datagram)).flatten(),
        }
    }
}

impl fmt::Display for UdpRoute {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Direct => f.write_str("direct"),
            Self::Held => f.write_str("held"),
            Self::Through(relay) => write!(f, "through {}", relay.relay()),
        }
    }
}
