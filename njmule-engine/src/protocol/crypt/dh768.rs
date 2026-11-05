// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Diffie-Hellman over the fixed 768-bit group of the server obfuscation (g = 2, 128-bit private exponents). Public
//! values travel as 96-byte big-endian numbers.

use std::fmt;
use std::sync::LazyLock;

use num_bigint::BigUint;
use rand::CryptoRng;

use crate::protocol::io::DecodeError;

pub const SIZE: usize = 96;

/// The group's prime, big-endian.
pub const P_BYTES: [u8; SIZE] = [
    0xF2, 0xBF, 0x52, 0xC5, 0x5F, 0x58, 0x7A, 0xDD, 0x53, 0x71, 0xA9, 0x36, //
    0xE8, 0x86, 0xEB, 0x3C, 0x62, 0x17, 0xA3, 0x3E, 0xC3, 0x4C, 0xB4, 0x0D, //
    0xC7, 0x3A, 0x41, 0xA6, 0x43, 0xAF, 0xFC, 0xE7, 0x21, 0xFC, 0x28, 0x63, //
    0x66, 0x53, 0x5B, 0xDB, 0xCE, 0x25, 0x9F, 0x22, 0x86, 0xDA, 0x4A, 0x91, //
    0xB2, 0x07, 0xCB, 0xAA, 0x52, 0x55, 0xD4, 0xF6, 0x1C, 0xCE, 0xAE, 0xD4, //
    0x5A, 0xD5, 0xE0, 0x74, 0x7D, 0xF7, 0x78, 0x18, 0x28, 0x10, 0x5F, 0x34, //
    0x0F, 0x76, 0x23, 0x87, 0xF8, 0x8B, 0x28, 0x91, 0x42, 0xFB, 0x42, 0x68, //
    0x8F, 0x05, 0x15, 0x0F, 0x54, 0x8B, 0x5F, 0x43, 0x6A, 0xF7, 0x0D, 0xF3, //
];

pub static P: LazyLock<BigUint> = LazyLock::new(|| BigUint::from_bytes_be(&P_BYTES));

static G: LazyLock<BigUint> = LazyLock::new(|| BigUint::from(2u8));

// Usable public values lie in [2, p - 2]: 0, 1 and p - 1 leave the secret to a guess. Big-endian arrays of one length
// compare as their numbers do.
const SMALLEST_PUBLIC: [u8; SIZE] = {
    let mut two = [0; SIZE];
    two[SIZE - 1] = 2;
    two
};
const LARGEST_PUBLIC: [u8; SIZE] = {
    let mut p_minus_two = P_BYTES;
    p_minus_two[SIZE - 1] -= 2;
    p_minus_two
};

const PRIVATE_BYTES: usize = 16;

/// One party's key pair.
pub struct Dh768 {
    private: BigUint,
    public_value: [u8; SIZE],
}

impl Dh768 {
    pub fn generate(random: &mut impl CryptoRng) -> Self {
        let mut exponent = [0; PRIVATE_BYTES];
        random.fill_bytes(&mut exponent);
        // The top bit set keeps the exponent at 128 bits, far from the degenerate 0 and 1.
        exponent[0] |= 0x80;
        let private = BigUint::from_bytes_be(&exponent);
        let public_value = to_bytes(&G.modpow(&private, &P));
        Self { private, public_value }
    }

    /// `g^a mod p`.
    pub fn public_value(&self) -> [u8; SIZE] {
        self.public_value
    }

    /// `B^a mod p`, after checking that `B` is a usable public value.
    pub fn shared_secret(&self, other_public: &[u8]) -> Result<[u8; SIZE], DecodeError> {
        let other: &[u8; SIZE] =
            other_public.try_into().map_err(|_| DecodeError::new("DH public values have 96 bytes"))?;
        if *other < SMALLEST_PUBLIC || *other > LARGEST_PUBLIC {
            return Err(DecodeError::new("degenerate DH public value"));
        }
        Ok(to_bytes(&BigUint::from_bytes_be(other).modpow(&self.private, &P)))
    }
}

impl fmt::Debug for Dh768 {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let [a, b, c, d, ..] = self.public_value;
        write!(f, "Dh768[public={a:02x}{b:02x}{c:02x}{d:02x}…]")
    }
}

/// `value` as a 96-byte big-endian number; a larger one keeps its low 96 bytes.
pub fn to_bytes(value: &BigUint) -> [u8; SIZE] {
    let mut out = [0; SIZE];
    for (to, from) in out.iter_mut().rev().zip(value.to_bytes_be().iter().rev()) {
        *to = *from;
    }
    out
}
