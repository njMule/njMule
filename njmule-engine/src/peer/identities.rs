// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Secure identification on every client connection: our key from `cryptkey.dat`, and how far each connection's user
//! hash can be trusted for credits. The keys peers proved to own are the credits' to keep.

use std::io;
use std::net::{IpAddr, Ipv4Addr};
use std::path::Path;
use std::sync::Arc;

use rand::CryptoRng;

use crate::crypto::rsa::{self, PrivateKey};
use crate::crypto::rsa_keys;
use crate::peer::identity_handshake::{Handled, Identity, IdentityHandshake, Outcome};
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::io::DecodeError;
use crate::protocol::packet::Packet;

pub const KEY_FILE: &str = "cryptkey.dat";

/// How far a connection's user hash is trusted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Trust {
    /// The peer does not take part and has no key pinned: credits apply, unsecured.
    Unsecured,
    /// Not proven yet.
    Needed,
    Identified,
    Failed,
}

impl Trust {
    /// Whether credits are given and counted.
    pub fn credits(self) -> bool {
        matches!(self, Self::Unsecured | Self::Identified)
    }
}

/// The identification over one connection.
pub struct Session {
    handshake: IdentityHandshake,
    trust: Trust,
}

impl Session {
    /// Handles the packet if it belongs to the identification: what to send and how it ended; none when the packet is
    /// another's.
    pub fn received(&mut self, packet: &Packet) -> Result<Option<Handled>, DecodeError> {
        let handled = self.handshake.received(packet)?;
        match handled.as_ref().and_then(|handled| handled.outcome.as_ref()) {
            Some(Outcome::Identified { .. }) => self.trust = Trust::Identified,
            Some(Outcome::Failed { .. }) => self.trust = Trust::Failed,
            None => {}
        }
        Ok(handled)
    }

    pub fn trust(&self) -> Trust {
        self.trust
    }

    /// Whether the outcome is known: identified, failed, or the peer does not take part.
    pub fn is_settled(&self) -> bool {
        self.trust != Trust::Needed
    }

    pub fn is_supported(&self) -> bool {
        self.handshake.is_supported()
    }
}

pub struct Identities {
    own: Arc<Identity>,
}

impl Identities {
    pub fn new(own: Identity) -> Self {
        Self { own: Arc::new(own) }
    }

    /// Our public key, as the credits pin it and peers get it.
    pub fn public_key(&self) -> &[u8] {
        self.own.public_key()
    }

    /// Starts the identification with a peer whose hello we have, asking it to sign our challenge; the session, and
    /// what to send. `pinned_key` is the key its user hash proved to own before, `local` our address on the connection,
    /// `our_public_address` ours once a server or Kad told it.
    pub fn start(
        &self,
        peer: &Hello,
        address: IpAddr,
        local: Option<IpAddr>,
        pinned_key: Option<&[u8]>,
        our_public_address: Option<Ipv4Addr>,
        random: &mut impl CryptoRng,
    ) -> (Session, Vec<Packet>) {
        let mut handshake =
            IdentityHandshake::new(Arc::clone(&self.own), peer, address, pinned_key, our_public_address, local);
        let trust = if handshake.is_supported() || pinned_key.is_some() { Trust::Needed } else { Trust::Unsecured };
        let send = handshake.start(random);
        (Session { handshake, trust }, send)
    }
}

/// Reads our key from `config_dir`, creating it on the first start or when the file cannot be read.
pub fn load_or_create_key(config_dir: &Path) -> io::Result<PrivateKey> {
    let file = config_dir.join(KEY_FILE);
    if file.metadata().is_ok_and(|metadata| metadata.len() > 0) {
        match rsa_keys::read_key_file(&file) {
            Ok(key) => return Ok(key),
            Err(e) if e.kind() == io::ErrorKind::InvalidData => {
                tracing::warn!("{} is unreadable, creating a new key: {e}", file.display());
            }
            Err(e) => return Err(e),
        }
    }
    let key = rsa::generate(rsa::NETWORK_KEY_BITS).map_err(io::Error::other)?;
    rsa_keys::write_key_file(&file, &key)?;
    Ok(key)
}
