// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A TCP port for an engine of a test to listen on. A port the system picks for a socket bound to 0 comes from the range
//! it gives outgoing connections too (32768-60999 on Linux, 49152-65535 on Windows and macOS): once released, another
//! test's connection may take it as its own before the engine listens, and the engine is left without one.

#![allow(dead_code)]

use std::net::{Ipv4Addr, TcpListener};

use rand::RngExt;

/// A port free now, below every system's range for outgoing connections, picked at random so that tests side by side
/// take different ones.
pub fn free_port() -> u16 {
    let mut random = rand::rng();
    loop {
        let port = random.random_range(20_000..32_000);
        if TcpListener::bind((Ipv4Addr::UNSPECIFIED, port)).is_ok() {
            return port;
        }
    }
}
