// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Checks minisign signatures (https://jedisct1.github.io/minisign/) with the reference verifier's crate: Ed25519 over
//! the BLAKE2b-512 hash of the file, then over that signature and its trusted comment. Legacy signatures, over the file
//! itself, are refused as the format asks of new verifiers. Keys are told apart by the id signatures name them by.

use std::fmt;

use data_encoding::{BASE64, HEXLOWER};

const UNTRUSTED: &str = "untrusted comment: ";
const TRUSTED: &str = "trusted comment: ";
const ID_LENGTH: usize = 8;
const KEY_LENGTH: usize = 2 + ID_LENGTH + 32;
const SIGNATURE_LENGTH: usize = 64;
const SIGNATURE_LINE_LENGTH: usize = 2 + ID_LENGTH + SIGNATURE_LENGTH;
// A signature file is four short lines; anything much longer is not one.
const MAX_SIGNATURE_TEXT: usize = 4_096;

/// A public key, and the id signatures name it by in hexadecimal.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PublicKey {
    pub id: String,
    key: minisign_verify::PublicKey,
}

/// A parsed signature file, of the hashed kind.
#[derive(Clone)]
pub struct Signature {
    key_id: String,
    signature: minisign_verify::Signature,
}

/// A key or signature that is not one, or a signature that does not match.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MinisignError {
    message: String,
}

impl Signature {
    pub fn key_id(&self) -> &str {
        &self.key_id
    }
}

/// The keys of `text`: minisign public keys, one base64 line each, after their optional comment lines.
pub fn public_keys(text: &str) -> Result<Vec<PublicKey>, MinisignError> {
    let mut keys: Vec<PublicKey> = Vec::new();
    for line in text.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('#') || line.starts_with(UNTRUSTED) {
            continue;
        }
        let key = public_key(line)?;
        match keys.iter_mut().find(|known| known.id == key.id) {
            Some(known) => *known = key,
            None => keys.push(key),
        }
    }
    Ok(keys)
}

/// The key of a public key line.
pub fn public_key(base64: &str) -> Result<PublicKey, MinisignError> {
    let base64 = base64.trim();
    let bytes = BASE64.decode(base64.as_bytes()).map_err(|_| MinisignError::new("not a minisign public key"))?;
    let id = match bytes.get(..2 + ID_LENGTH) {
        Some([b'E', b'd', id @ ..]) if bytes.len() == KEY_LENGTH => HEXLOWER.encode(id),
        _ => return Err(MinisignError::new("not a minisign public key")),
    };
    let key =
        minisign_verify::PublicKey::from_base64(base64).map_err(|_| MinisignError::new("not a minisign public key"))?;
    Ok(PublicKey { id, key })
}

/// Reads a signature file, which must be of the hashed kind.
pub fn signature(text: &str) -> Result<Signature, MinisignError> {
    let not_one = || MinisignError::new("not a minisign signature");
    if text.len() > MAX_SIGNATURE_TEXT {
        return Err(not_one());
    }
    let lines: Vec<&str> = text.lines().collect();
    let [untrusted, signed, trusted, global, ..] = lines.as_slice() else {
        return Err(not_one());
    };
    if !untrusted.starts_with(UNTRUSTED) || !trusted.starts_with(TRUSTED) {
        return Err(not_one());
    }
    let line = decode(signed, SIGNATURE_LINE_LENGTH)?;
    decode(global, SIGNATURE_LENGTH)?;
    let key_id = match line.get(..2 + ID_LENGTH) {
        Some([b'E', b'D', id @ ..]) => HEXLOWER.encode(id),
        Some([b'E', b'd', ..]) => return Err(MinisignError::new("a legacy signature, over the file itself")),
        _ => return Err(MinisignError::new("not Ed25519")),
    };
    let lines = [*untrusted, signed.trim(), *trusted, global.trim()].join("\n");
    let signature = minisign_verify::Signature::decode(&lines).map_err(|_| not_one())?;
    Ok(Signature { key_id, signature })
}

/// Checks that one of `keys` signed `data` and the trusted comment; gives that comment.
pub fn verify(data: &[u8], signature: &Signature, keys: &[PublicKey]) -> Result<String, MinisignError> {
    let Some(key) = keys.iter().find(|key| key.id == signature.key_id) else {
        return Err(MinisignError::new(format!("signed with an unknown key {}", signature.key_id)));
    };
    key.key
        .verify(data, &signature.signature, false)
        .map_err(|_| MinisignError::new("the signature or its trusted comment does not match"))?;
    Ok(signature.signature.trusted_comment().to_owned())
}

fn decode(line: &str, length: usize) -> Result<Vec<u8>, MinisignError> {
    BASE64
        .decode(line.trim().as_bytes())
        .ok()
        .filter(|bytes| bytes.len() == length)
        .ok_or_else(|| MinisignError::new("not a minisign signature"))
}

impl MinisignError {
    fn new(message: impl Into<String>) -> Self {
        Self { message: message.into() }
    }
}

impl fmt::Display for MinisignError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for MinisignError {}
