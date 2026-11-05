// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! RC4 key stream of the eD2K protocol obfuscation (not for security), on RustCrypto's `rc4`.

use std::fmt;

use ::rc4::{KeyInit, StreamCipher};

/// Bytes of key stream discarded by the TCP obfuscation.
pub const TCP_DISCARD: usize = 1024;

const SKIP_CHUNK: usize = 4096;

/// One key stream; each direction of a connection owns one.
pub struct Rc4 {
    cipher: ::rc4::Rc4,
}

impl Rc4 {
    /// The key stream of `key`, whose 1 to 256 bytes are checked when compiled.
    #[expect(clippy::expect_used, reason = "the key's length is checked when compiled")]
    pub fn new<const N: usize>(key: &[u8; N]) -> Self {
        const { assert!(matches!(N, 1..=256), "RC4 keys have 1 to 256 bytes") };
        Self { cipher: ::rc4::Rc4::new_from_slice(key).expect("1 to 256 bytes") }
    }

    /// XORs the key stream into `data` in place.
    pub fn apply(&mut self, data: &mut [u8]) {
        self.cipher.apply_keystream(data);
    }

    /// Advances the key stream by `length` bytes.
    pub fn skip(&mut self, length: usize) {
        let mut scratch = [0; SKIP_CHUNK];
        let mut left = length;
        while left > 0 {
            let chunk = left.min(SKIP_CHUNK);
            if let Some(part) = scratch.get_mut(..chunk) {
                self.cipher.write_keystream(part);
            }
            left = left.saturating_sub(chunk);
        }
    }
}

impl fmt::Debug for Rc4 {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Rc4").finish_non_exhaustive()
    }
}
