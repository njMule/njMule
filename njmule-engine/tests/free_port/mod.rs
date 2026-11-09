// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A TCP port for an engine of a test to listen on. A port the system picks for a socket bound to 0 comes from the range
//! it gives outgoing connections too (32768-60999 on Linux, 49152-65535 on Windows and macOS): once released, another
//! test's connection may take it as its own before the engine listens, and the engine is left without one.

#![allow(dead_code)]

use std::collections::HashSet;
use std::net::{Ipv4Addr, TcpListener};
use std::sync::{Mutex, PoisonError};

use rand::RngExt;

// The ports given so far: a port is free only until the test it went to listens on it, so no other test gets it.
static GIVEN: Mutex<Option<HashSet<u16>>> = Mutex::new(None);

/// A port free now, below every system's range for outgoing connections, picked at random and given to one test only.
pub fn free_port() -> u16 {
    let mut random = rand::rng();
    let mut given = GIVEN.lock().unwrap_or_else(PoisonError::into_inner);
    let given = given.get_or_insert_with(HashSet::new);
    loop {
        let port = random.random_range(20_000..32_000);
        if !given.contains(&port) && TcpListener::bind((Ipv4Addr::UNSPECIFIED, port)).is_ok() {
            given.insert(port);
            return port;
        }
    }
}
