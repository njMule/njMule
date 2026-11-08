// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The secret callers of the remote-control API send as a bearer token, kept in `api-token` of the config folder,
//! readable by its owner only where the file system can say so. A command line on the same computer reads it from
//! there; other computers are given it by the user.

use std::fs;
use std::io::{self, ErrorKind};
use std::path::Path;

use rand::Rng;
use subtle::ConstantTimeEq;

use crate::persist::atomic_files;

pub const FILE_NAME: &str = "api-token";

const BYTES: usize = 32;
// 32 bytes in URL-safe base64 without padding.
const LENGTH: usize = 43;

/// The token kept in `config`, if there is a well-formed one.
pub fn load(config: &Path) -> io::Result<Option<String>> {
    match fs::read_to_string(config.join(FILE_NAME)) {
        Ok(text) => {
            let text = text.trim();
            let formed =
                text.len() == LENGTH && text.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
            Ok(formed.then(|| text.to_owned()))
        }
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e),
    }
}

/// The token, made when there is none yet.
pub fn load_or_create(config: &Path) -> io::Result<String> {
    match load(config)? {
        Some(token) => Ok(token),
        None => renew(config),
    }
}

/// Replaces the token: whoever holds the old one is refused from now on.
pub fn renew(config: &Path) -> io::Result<String> {
    let mut secret = [0u8; BYTES];
    rand::rng().fill_bytes(&mut secret);
    let token = data_encoding::BASE64URL_NOPAD.encode(&secret);
    atomic_files::write_with(&config.join(FILE_NAME), false, true, |out| {
        out.write_all(format!("{token}\n").as_bytes())
    })?;
    Ok(token)
}

/// Whether `given` is `token`, compared in constant time.
pub fn matches(token: &str, given: &str) -> bool {
    bool::from(token.as_bytes().ct_eq(given.as_bytes()))
}
