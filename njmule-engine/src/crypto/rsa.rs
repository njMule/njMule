// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! RSA keys and RSASSA-PKCS1-v1_5 signatures with SHA-1, as secure identification uses them, on the `rsa` crate.

use ::rsa::rand_core::OsRng;
use ::rsa::traits::PublicKeyParts;
use ::rsa::{BigUint, Pkcs1v15Sign, RsaPrivateKey, RsaPublicKey};
use sha1::{Digest, Sha1};

/// Key size used on the eD2K network.
pub const NETWORK_KEY_BITS: usize = 384;

// The DigestInfo of SHA-1 that the signature carries before the hash (RFC 8017, section 9.2).
const SHA1_PREFIX: [u8; 15] =
    [0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14];
const SHA1_LENGTH: usize = 20;

/// A public key.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PublicKey {
    key: RsaPublicKey,
}

impl PublicKey {
    pub(crate) fn of(key: RsaPublicKey) -> Self {
        Self { key }
    }

    pub(crate) fn inner(&self) -> &RsaPublicKey {
        &self.key
    }

    /// The modulus, big-endian, without leading zeros.
    pub fn modulus(&self) -> Vec<u8> {
        self.key.n().to_bytes_be()
    }

    pub fn exponent(&self) -> u64 {
        to_u64(self.key.e())
    }

    pub fn modulus_bits(&self) -> usize {
        self.key.n().bits()
    }

    /// The size of a signature: the bytes of the modulus.
    pub fn size_bytes(&self) -> usize {
        self.key.size()
    }
}

/// A private key with its CRT parameters.
#[derive(Clone, PartialEq, Eq)]
pub struct PrivateKey {
    key: RsaPrivateKey,
}

impl std::fmt::Debug for PrivateKey {
    // Nothing secret in logs.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "PrivateKey[{} bits]", self.key.n().bits())
    }
}

impl PrivateKey {
    pub(crate) fn of(key: RsaPrivateKey) -> Self {
        Self { key }
    }

    pub(crate) fn inner(&self) -> &RsaPrivateKey {
        &self.key
    }

    pub fn public_key(&self) -> PublicKey {
        PublicKey { key: self.key.to_public_key() }
    }
}

/// A new key of `bits`, with the public exponent 65537, from the system's random source.
pub fn generate(bits: usize) -> Result<PrivateKey, String> {
    RsaPrivateKey::new(&mut OsRng, bits).map(PrivateKey::of).map_err(|e| e.to_string())
}

/// RSASSA-PKCS1-v1_5 with SHA-1; the signature has the size of the modulus. Blinded, so that its timing tells
/// nothing of the key.
pub fn sign(key: &PrivateKey, message: &[u8]) -> Result<Vec<u8>, String> {
    key.key.sign_with_rng(&mut OsRng, scheme(), &Sha1::digest(message)).map_err(|e| e.to_string())
}

/// Whether `signature` is `key`'s of `message`; a signature of another size is refused at once.
pub fn verify(key: &PublicKey, message: &[u8], signature: &[u8]) -> bool {
    signature.len() == key.size_bytes() && key.key.verify(scheme(), &Sha1::digest(message), signature).is_ok()
}

fn scheme() -> Pkcs1v15Sign {
    Pkcs1v15Sign { hash_len: Some(SHA1_LENGTH), prefix: SHA1_PREFIX.into() }
}

fn to_u64(value: &BigUint) -> u64 {
    value.to_bytes_le().iter().rev().fold(0u64, |sum, byte| sum.wrapping_shl(8) | u64::from(*byte))
}
