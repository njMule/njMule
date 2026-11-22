// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Tells when our public IPv4 address changed. Once the server we are on and Kad saw us at the same address, one of
//! them seeing another means we moved, and the other has to look again; each new address is acted on once. While they
//! never agreed, as behind a NAT that gives each connection its own address, nothing is done.

/// What an address change asks the network to do.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reaction {
    None,
    /// The server sees the new address: Kad checks its firewall again, and learns it.
    RecheckKad,
    /// Kad sees the new address: the server still knows us at the old one.
    ReconnectServer,
}

#[derive(Debug, Default)]
pub struct AddressWatch {
    agreed: u32,
    reacted_to: u32,
}

impl AddressWatch {
    /// The address the server and Kad last agreed on; 0 while they never did.
    pub fn agreed(&self) -> u32 {
        self.agreed
    }

    /// The addresses the server and Kad see us at now (host-order IPv4, 0 when unknown), and what to do.
    pub fn seen(&mut self, server_ip: u32, kad_ip: u32) -> Reaction {
        if server_ip != 0 && server_ip == kad_ip {
            self.agreed = server_ip;
            self.reacted_to = 0;
            return Reaction::None;
        }
        if self.agreed == 0 {
            return Reaction::None;
        }
        if kad_ip == self.agreed && self.is_new(server_ip) {
            self.reacted_to = server_ip;
            return Reaction::RecheckKad;
        }
        if server_ip == self.agreed && self.is_new(kad_ip) {
            self.reacted_to = kad_ip;
            return Reaction::ReconnectServer;
        }
        Reaction::None
    }

    fn is_new(&self, ip: u32) -> bool {
        ip != 0 && ip != self.agreed && ip != self.reacted_to
    }
}
