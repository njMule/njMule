// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A minisign key made for a test, which signs as minisign does: the hashed kind, with a trusted comment.

#![allow(dead_code, clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing)]

use blake2::{Blake2b512, Digest};
use data_encoding::BASE64;
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{Ed25519KeyPair, KeyPair};

use njmule_engine::update::minisign::{self, PublicKey};

pub struct TestSigning {
    secret: Ed25519KeyPair,
    id: [u8; 8],
}

impl TestSigning {
    pub fn new() -> Self {
        let random = SystemRandom::new();
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&random).unwrap();
        let mut id = [0u8; 8];
        random.fill(&mut id).unwrap();
        Self { secret: Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap(), id }
    }

    /// The public key as the line of a minisign public key file.
    pub fn public_key_line(&self) -> String {
        let mut key = vec![b'E', b'd'];
        key.extend_from_slice(&self.id);
        key.extend_from_slice(self.secret.public_key().as_ref());
        BASE64.encode(&key)
    }

    pub fn keys(&self) -> Vec<PublicKey> {
        minisign::public_keys(&self.public_key_line()).unwrap()
    }

    /// The signature file of `data`.
    pub fn sign(&self, data: &[u8]) -> String {
        self.sign_with(data, "timestamp:1793000000\tfile:njmule-update.json\thashed")
    }

    pub fn sign_with(&self, data: &[u8], trusted_comment: &str) -> String {
        let hash = Blake2b512::digest(data);
        let signature = self.secret.sign(&hash);
        let mut line = vec![b'E', b'D'];
        line.extend_from_slice(&self.id);
        line.extend_from_slice(signature.as_ref());
        let mut signed = signature.as_ref().to_vec();
        signed.extend_from_slice(trusted_comment.as_bytes());
        format!(
            "untrusted comment: signature from minisign secret key\n{}\ntrusted comment: {trusted_comment}\n{}\n",
            BASE64.encode(&line),
            BASE64.encode(self.secret.sign(&signed).as_ref())
        )
    }
}
