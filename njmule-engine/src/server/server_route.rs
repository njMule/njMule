// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Where a datagram for a server goes, and the server's UDP key it is obfuscated with (0: sent plain).

use std::net::{Ipv4Addr, SocketAddr};

use crate::config::obfuscation_mode::ObfuscationMode;
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::server_datagrams;
use crate::server::server_pinger::PORT_OFFSET;
use crate::util::ipv4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServerRoute {
    pub target: SocketAddr,
    pub key: u32,
}

impl ServerRoute {
    /// Obfuscated to the server's obfuscated UDP port unless obfuscation is off, when the server takes obfuscated
    /// datagrams and its key was given for the address we have now (`our_ip`, 0 when unknown); else plain to its TCP
    /// port + 4, unless obfuscation is required: then none. None too for a server whose UDP port would pass 65535.
    pub fn of(server: &Server, our_ip: u32, mode: ObfuscationMode) -> Option<Self> {
        let route = Self::chosen(server, our_ip, mode != ObfuscationMode::Disabled)?;
        (mode != ObfuscationMode::Required || route.is_obfuscated()).then_some(route)
    }

    fn chosen(server: &Server, our_ip: u32, obfuscate: bool) -> Option<Self> {
        let key = server.udp_key().and_then(|key| u32::try_from(key).ok()).unwrap_or(0);
        let port = server.obfuscated_udp_port().and_then(|port| u16::try_from(port).ok()).unwrap_or(0);
        let takes_obfuscated = server.udp_flags().unwrap_or(0) & u64::from(server_datagrams::UDP_OBFUSCATION) != 0;
        let address = Ipv4Addr::from(server.ip);
        if obfuscate
            && takes_obfuscated
            && key != 0
            && port > 0
            && our_ip != 0
            && server.udp_key_ip().unwrap_or(0) == u64::from(ipv4::to_ed2k(our_ip))
        {
            return Some(Self { target: SocketAddr::from((address, port)), key });
        }
        let plain = server.port.checked_add(PORT_OFFSET)?;
        Some(Self { target: SocketAddr::from((address, plain)), key: 0 })
    }

    pub fn is_obfuscated(&self) -> bool {
        self.key != 0
    }
}
