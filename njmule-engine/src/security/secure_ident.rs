// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The message of secure identification: the verifier's public key, its challenge and, from version 2, the address it
//! is bound to; the signer signs it with its own key, the verifier checks it with the signer's.

use std::net::IpAddr;

use crate::crypto::rsa::{self, PrivateKey, PublicKey};

/// Which address a version 2 signature is bound to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IpKind {
    /// The verifier's public IP (as seen by the signer).
    RemoteClient,
    /// The signer's own IP.
    LocalClient,
    /// No address (0).
    None,
}

impl IpKind {
    pub fn code(self) -> u8 {
        match self {
            Self::RemoteClient => 10,
            Self::LocalClient => 20,
            Self::None => 30,
        }
    }

    pub fn of(code: u8) -> Option<Self> {
        [Self::RemoteClient, Self::LocalClient, Self::None].into_iter().find(|kind| kind.code() == code)
    }
}

/// The address part of a version 2 signature: 4 bytes for IPv4, 16 for IPv6.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IpBinding {
    pub address: IpAddr,
    pub kind: IpKind,
}

/// The verifier's public key, its challenge little-endian, then the address and its kind when bound.
pub fn message(verifier_public_key: &[u8], challenge: u32, binding: Option<&IpBinding>) -> Vec<u8> {
    let mut message = verifier_public_key.to_vec();
    message.extend_from_slice(&challenge.to_le_bytes());
    if let Some(binding) = binding {
        match binding.address {
            IpAddr::V4(v4) => message.extend_from_slice(&v4.octets()),
            IpAddr::V6(v6) => message.extend_from_slice(&v6.octets()),
        }
        message.push(binding.kind.code());
    }
    message
}

/// Signs the challenge of a verifier whose public key is `verifier_public_key`.
pub fn sign(
    own_key: &PrivateKey,
    verifier_public_key: &[u8],
    challenge: u32,
    binding: Option<&IpBinding>,
) -> Result<Vec<u8>, String> {
    rsa::sign(own_key, &message(verifier_public_key, challenge, binding))
}

/// Checks a signature of our challenge made with the peer's key.
pub fn verify(
    peer_key: &PublicKey,
    signature: &[u8],
    own_public_key: &[u8],
    challenge: u32,
    binding: Option<&IpBinding>,
) -> bool {
    rsa::verify(peer_key, &message(own_public_key, challenge, binding), signature)
}
