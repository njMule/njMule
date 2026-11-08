// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Password hashes for the web interface: PBKDF2 with HMAC-SHA-512, a random salt and many rounds, written as
//! `pbkdf2-sha512$<rounds>$<salt>$<hash>` in base64 without padding, as the Java engine writes them.

use data_encoding::BASE64_NOPAD;
use rand::Rng;
use sha2::Sha512;
use subtle::ConstantTimeEq;

/// Rounds of new hashes.
pub const ROUNDS: u32 = 210_000;
/// The shortest password accepted for the web interface, in characters.
pub const MIN_LENGTH: usize = 8;

const SCHEME: &str = "pbkdf2-sha512";
const SALT_BYTES: usize = 16;
const HASH_BYTES: usize = 64;
// More rounds than any hash of ours holds: a hash that asks for them would only hold the caller up.
const MAX_ROUNDS: u32 = 10_000_000;

/// The hash of `password` with a new random salt.
pub fn hash(password: &str) -> String {
    let mut salt = [0u8; SALT_BYTES];
    rand::rng().fill_bytes(&mut salt);
    hash_with(password, &salt, ROUNDS)
}

/// The hash of `password` with `salt` and `rounds`.
pub fn hash_with(password: &str, salt: &[u8], rounds: u32) -> String {
    format!("{SCHEME}${rounds}${}${}", BASE64_NOPAD.encode(salt), BASE64_NOPAD.encode(&derive(password, salt, rounds)))
}

/// Whether `password` matches `stored`; a malformed hash matches nothing.
pub fn matches(password: &str, stored: &str) -> bool {
    let parts: Vec<&str> = stored.split('$').collect();
    let [scheme, rounds, salt, expected] = parts.as_slice() else {
        return false;
    };
    let Ok(rounds) = rounds.parse::<u32>() else {
        return false;
    };
    if *scheme != SCHEME || rounds == 0 || rounds > MAX_ROUNDS {
        return false;
    }
    // Padding is optional, as for the Java engine's decoder.
    let (Ok(salt), Ok(expected)) = (decode(salt), decode(expected)) else {
        return false;
    };
    bool::from(derive(password, &salt, rounds).as_slice().ct_eq(&expected))
}

fn decode(text: &str) -> Result<Vec<u8>, data_encoding::DecodeError> {
    BASE64_NOPAD.decode(text.trim_end_matches('=').as_bytes())
}

fn derive(password: &str, salt: &[u8], rounds: u32) -> [u8; HASH_BYTES] {
    let mut derived = [0u8; HASH_BYTES];
    pbkdf2::pbkdf2_hmac::<Sha512>(password.as_bytes(), salt, rounds, &mut derived);
    derived
}
