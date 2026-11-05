// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! DER encodings of RSA keys: public keys as X.509 SubjectPublicKeyInfo (what peers exchange, at most 80 bytes for
//! network keys), private keys as PKCS#8; the bare PKCS#1 forms are read too. Key files hold the DER in Base64.

use std::io;
use std::path::Path;

use ::rsa::pkcs1::{DecodeRsaPrivateKey, DecodeRsaPublicKey};
use ::rsa::pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePrivateKey, EncodePublicKey};
use ::rsa::traits::PublicKeyParts;
use ::rsa::{BigUint, RsaPrivateKey, RsaPublicKey};
use num_bigint_dig::prime::probably_prime;
use num_integer::Integer;

use crate::crypto::rsa::{PrivateKey, PublicKey};
use crate::persist::atomic_files;
use crate::protocol::io::DecodeError;

/// Largest public key a peer may send.
pub const MAX_PUBLIC_KEY_SIZE: usize = 80;

const MAX_KEY_BITS: usize = 4096;
const MAX_ENCODED_SIZE: usize = 4096;
const BASE64_LINE: usize = 72;
// The primes below this make the product a modulus may share no factor with.
const SMALL_PRIMES_BELOW: u32 = 752;
const PRIME_ROUNDS: usize = 20;

pub fn encode_public(key: &PublicKey) -> Vec<u8> {
    key.inner().to_public_key_der().map(|der| der.as_bytes().to_vec()).unwrap_or_default()
}

/// Decodes a SubjectPublicKeyInfo or a bare PKCS#1 RSAPublicKey, of a key a signature check can use.
pub fn decode_public(der: &[u8]) -> Result<PublicKey, DecodeError> {
    check_size(der)?;
    // The crate refuses moduli over 4,096 bits, exponents over 2^33 - 1 and even ones.
    let key = RsaPublicKey::from_public_key_der(der)
        .or_else(|_| RsaPublicKey::from_pkcs1_der(der))
        .map_err(|e| DecodeError::new(format!("malformed RSA key: {e}")))?;
    // What the signature check refuses besides: moduli that are prime or have small factors.
    if probably_prime(key.n(), PRIME_ROUNDS) || !key.n().gcd(&small_primes()).eq(&BigUint::from(1u32)) {
        return Err(DecodeError::new("implausible RSA public key"));
    }
    Ok(PublicKey::of(key))
}

pub fn encode_private(key: &PrivateKey) -> Vec<u8> {
    key.inner().to_pkcs8_der().map(|der| der.as_bytes().to_vec()).unwrap_or_default()
}

/// Decodes a PKCS#8 PrivateKeyInfo or a PKCS#1 RSAPrivateKey.
pub fn decode_private(der: &[u8]) -> Result<PrivateKey, DecodeError> {
    check_size(der)?;
    let key = RsaPrivateKey::from_pkcs8_der(der)
        .or_else(|_| RsaPrivateKey::from_pkcs1_der(der))
        .map_err(|e| DecodeError::new(format!("malformed RSA key: {e}")))?;
    if key.n().bits() > MAX_KEY_BITS {
        return Err(DecodeError::new("implausible RSA private key"));
    }
    Ok(PrivateKey::of(key))
}

/// Reads a Base64 key file such as `cryptkey.dat`; line breaks are ignored.
pub fn read_key_file(file: &Path) -> io::Result<PrivateKey> {
    let text: String = std::fs::read_to_string(file)?.chars().filter(|c| !c.is_whitespace()).collect();
    let der = data_encoding::BASE64.decode(text.as_bytes()).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidData, format!("key file is not Base64: {}", file.display()))
    })?;
    decode_private(&der).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
}

/// Writes the key as Base64 in lines of 72 characters, each ended, crash-safely and for its owner only.
pub fn write_key_file(file: &Path, key: &PrivateKey) -> io::Result<()> {
    let encoded = data_encoding::BASE64.encode(&encode_private(key));
    let mut text = String::with_capacity(encoded.len().saturating_mul(2));
    for line in encoded.as_bytes().chunks(BASE64_LINE) {
        text.push_str(std::str::from_utf8(line).unwrap_or_default());
        text.push('\n');
    }
    atomic_files::write_secret(file, text.as_bytes())
}

// The parser reads only from the slice: lengths inside the DER cannot make it allocate more.
fn check_size(der: &[u8]) -> Result<(), DecodeError> {
    if der.len() > MAX_ENCODED_SIZE {
        return Err(DecodeError::new(format!("RSA key of {} bytes is too large", der.len())));
    }
    Ok(())
}

// The product of the odd primes below SMALL_PRIMES_BELOW.
fn small_primes() -> BigUint {
    (3..SMALL_PRIMES_BELOW)
        .step_by(2)
        .filter(|n| (3..*n).step_by(2).take_while(|d| d.saturating_mul(*d) <= *n).all(|d| n.checked_rem(d) != Some(0)))
        .map(BigUint::from)
        .product()
}
