// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The user hash, which peers know us by, kept in `preferences.dat`.

use std::io;
use std::path::Path;

use rand::CryptoRng;

use crate::persist::atomic_files;
use crate::persist::format::preferences_dat::{self, Contents};

pub const FILE_NAME: &str = "preferences.dat";

/// Loads the hash, or creates and saves a new one when the file is missing or unusable. A file that cannot be read
/// fails: the hash in it is the one peers know.
pub fn load_or_create(config_dir: &Path, random: &mut impl CryptoRng) -> io::Result<[u8; 16]> {
    let file = config_dir.join(FILE_NAME);
    atomic_files::cleanup_interrupted_write(&file);
    let read = atomic_files::read_unless_held(&file, |data| {
        preferences_dat::read(data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
    });
    match read {
        Ok(contents) if is_usable(&contents.user_hash) => return Ok(contents.user_hash),
        Ok(_) => tracing::warn!("Unusable user hash in {}, creating a new one", file.display()),
        Err(e) if e.kind() == io::ErrorKind::NotFound => tracing::info!("Creating the user hash in {}", file.display()),
        Err(e) if e.kind() == io::ErrorKind::InvalidData => {
            tracing::warn!("Corrupt {}, creating a new user hash: {e}", file.display());
        }
        Err(e) => return Err(e),
    }
    let hash = generate(random);
    let contents = Contents { version: preferences_dat::VERSION, user_hash: hash, window_placement: Vec::new() };
    atomic_files::write_secret(&file, &preferences_dat::write(&contents))?;
    Ok(hash)
}

/// A random hash with the marker bytes clients set in theirs.
pub fn generate(random: &mut impl CryptoRng) -> [u8; 16] {
    loop {
        let mut hash = [0; 16];
        random.fill_bytes(&mut hash);
        hash[5] = 14;
        hash[14] = 111;
        if is_usable(&hash) {
            return hash;
        }
    }
}

/// Whether anything but the marker bytes is set.
pub fn is_usable(hash: &[u8; 16]) -> bool {
    hash.iter().enumerate().any(|(i, byte)| i != 5 && i != 14 && *byte != 0)
}
