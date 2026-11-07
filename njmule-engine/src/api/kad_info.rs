// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The state of our Kad node: `firewalled` when other nodes cannot connect to our TCP port, `public_ip` as they see us
//! (empty when unknown), `udp_firewalled` when they cannot send to our UDP port (the last outcome while a test runs),
//! `udp_verified` once a UDP test came to an outcome, and how many `users` and `files` the network holds, as estimated.

/// Our Kad buddy: none, one we connect to or have while firewalled, or a firewalled node we serve.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Buddy {
    None,
    Connecting,
    Connected,
    Serving,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KadInfo {
    pub connected: bool,
    pub contacts: usize,
    pub verified_contacts: usize,
    pub firewalled: bool,
    pub id: String,
    pub public_ip: String,
    pub udp_port: u16,
    pub udp_firewalled: bool,
    pub udp_verified: bool,
    pub buddy: Buddy,
    pub users: u64,
    pub files: u64,
}
