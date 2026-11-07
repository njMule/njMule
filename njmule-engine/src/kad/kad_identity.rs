// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Who we are on Kad: a random 128-bit id kept in `preferencesKad.dat`, and a secret from which the verify key we give
//! each address derives, kept in `kadudpkey.dat`.

use std::fs;
use std::io;
use std::path::Path;

use rand::{Rng, RngExt};

use crate::kad::uint128::UInt128;
use crate::persist::atomic_files;
use crate::persist::format::preferences_kad_dat::{self, Contents};
use crate::protocol::crypt::datagram_obfuscation;

pub const FILE_NAME: &str = "preferencesKad.dat";
pub const KEY_FILE_NAME: &str = "kadudpkey.dat";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KadIdentity {
    id: UInt128,
    udp_key: u32,
}

impl KadIdentity {
    /// Our identity; none for the zero id, which no node may have.
    pub fn new(id: UInt128, udp_key: u32) -> Option<Self> {
        (!id.is_zero()).then_some(Self { id, udp_key })
    }

    /// Reads our identity from `config_dir`, creating what is missing.
    pub fn load_or_create(config_dir: &Path, random: &mut impl Rng) -> io::Result<Self> {
        let file = config_dir.join(FILE_NAME);
        let mut id = UInt128::ZERO;
        if file.exists() {
            match fs::read(&file).map_err(|e| e.to_string()).and_then(|data| {
                preferences_kad_dat::read(&data).map(|contents| contents.kad_id).map_err(|e| e.to_string())
            }) {
                Ok(read) => id = read,
                Err(e) => tracing::warn!("{} is unreadable, creating a new Kad id: {e}", file.display()),
            }
        }
        if id.is_zero() {
            while id.is_zero() {
                id = UInt128::random(random);
            }
            atomic_files::write(&file, &preferences_kad_dat::write(&Contents { last_ip: 0, kad_id: id }))?;
        }
        let key_file = config_dir.join(KEY_FILE_NAME);
        let mut key = match fs::read(&key_file) {
            Ok(data) => <[u8; 4]>::try_from(data.as_slice()).map_or(0, u32::from_le_bytes),
            Err(_) => 0,
        };
        if key == 0 {
            while key == 0 {
                key = random.random();
            }
            atomic_files::write_secret(&key_file, &key.to_le_bytes())?;
        }
        Ok(Self { id, udp_key: key })
    }

    pub fn id(&self) -> UInt128 {
        self.id
    }

    /// The verify key we give the node at `ip` (host order).
    pub fn verify_key(&self, ip: u32) -> u32 {
        datagram_obfuscation::kad_verify_key(self.udp_key, ip)
    }
}
