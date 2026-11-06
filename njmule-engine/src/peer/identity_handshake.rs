// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Secure identification over one connection, run by both sides independently: each asks the other to sign a random
//! challenge (sending its key first when the other may not know it) and checks the signature with the key it pinned for
//! that user hash. It answers what to send and how the identification ended; the caller sends.

use std::net::{IpAddr, Ipv4Addr};
use std::sync::Arc;

use rand::{CryptoRng, RngExt};

use crate::crypto::rsa::PrivateKey;
use crate::crypto::rsa_keys;
use crate::protocol::ed2k::hello::Hello;
use crate::protocol::io::{DecodeError, PacketWriter};
use crate::protocol::packet::opcodes::extended;
use crate::protocol::packet::{Packet, protocol};
use crate::security::secure_ident::{self, IpBinding, IpKind};

/// Both versions, announced in the hello.
pub const SUPPORTED: u32 = 3;

const STATE_SIGNATURE: u8 = 1;
const STATE_KEY_AND_SIGNATURE: u8 = 2;
const MIN_PACKET: usize = 10;
const MAX_PACKET: usize = 250;
const V1: u32 = 1;
const V2: u32 = 2;

/// Our own key, and its public part as the peers get it.
pub struct Identity {
    key: PrivateKey,
    public_key: Vec<u8>,
}

impl Identity {
    pub fn of(key: PrivateKey) -> Self {
        let public_key = rsa_keys::encode_public(&key.public_key());
        Self { key, public_key }
    }

    pub fn public_key(&self) -> &[u8] {
        &self.public_key
    }

    pub fn key(&self) -> &PrivateKey {
        &self.key
    }
}

/// How the identification ended, told once.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// The peer signed our challenge with this key.
    Identified {
        user_hash: [u8; 16],
        public_key: Vec<u8>,
    },
    Failed {
        user_hash: [u8; 16],
    },
}

/// What a packet of the identification brought: the packets to send, and the outcome when it came.
#[derive(Debug, Default)]
pub struct Handled {
    pub send: Vec<Packet>,
    pub outcome: Option<Outcome>,
}

pub struct IdentityHandshake {
    own: Arc<Identity>,
    peer_hash: [u8; 16],
    peer_versions: u32,
    peer_address: IpAddr,
    our_public_address: Option<Ipv4Addr>,
    our_local_address: Option<IpAddr>,
    peer_key: Option<Vec<u8>>,
    pinned: bool,
    challenge_for: u32,
    challenge_from: u32,
    signature_wanted: bool,
    done: bool,
}

impl IdentityHandshake {
    /// `pinned_key` is the key this user hash proved to own before, if any; `our_public_address` our public IPv4
    /// address, once a server or Kad told it; `our_local_address` our address on the connection, IPv4 or IPv6 as the
    /// peer's.
    pub fn new(
        own: Arc<Identity>,
        peer: &Hello,
        peer_address: IpAddr,
        pinned_key: Option<&[u8]>,
        our_public_address: Option<Ipv4Addr>,
        our_local_address: Option<IpAddr>,
    ) -> Self {
        Self {
            own,
            peer_hash: peer.user_hash,
            peer_versions: versions(peer),
            peer_address,
            our_public_address,
            our_local_address,
            peer_key: pinned_key.map(<[u8]>::to_vec),
            pinned: pinned_key.is_some(),
            challenge_for: 0,
            challenge_from: 0,
            signature_wanted: false,
            done: false,
        }
    }

    /// Whether the peer takes part at all.
    pub fn is_supported(&self) -> bool {
        self.peer_versions != 0
    }

    /// Asks the peer for a signature, after the hellos; nothing to send when it does not take part.
    pub fn start(&mut self, random: &mut impl CryptoRng) -> Vec<Packet> {
        if !self.is_supported() {
            return Vec::new();
        }
        let mut challenge = 0;
        while challenge == 0 {
            challenge = random.random::<u32>();
        }
        self.challenge_for = challenge;
        let state = if self.peer_key.is_none() { STATE_KEY_AND_SIGNATURE } else { STATE_SIGNATURE };
        let mut out = PacketWriter::new();
        out.u8(state).u32(challenge);
        vec![Packet::written(protocol::EMULE, extended::SECIDENTSTATE, out)]
    }

    /// Handles the packet if it belongs to the identification: what to send and how it ended; none when the packet is
    /// another's.
    pub fn received(&mut self, packet: &Packet) -> Result<Option<Handled>, DecodeError> {
        if packet.protocol() != protocol::EMULE {
            return Ok(None);
        }
        let mut handled = Handled::default();
        match packet.opcode() {
            extended::SECIDENTSTATE => self.state_received(packet, &mut handled)?,
            extended::PUBLICKEY => self.key_received(packet, &mut handled)?,
            extended::SIGNATURE => self.signature_received(packet, &mut handled)?,
            _ => return Ok(None),
        }
        Ok(Some(handled))
    }

    fn state_received(&mut self, packet: &Packet, handled: &mut Handled) -> Result<(), DecodeError> {
        if packet.payload().len() != 5 || !self.is_supported() {
            return Ok(());
        }
        let mut input = packet.reader();
        let state = input.u8()?;
        self.challenge_from = input.u32()?;
        if state == STATE_KEY_AND_SIGNATURE {
            let key = self.own.public_key();
            let mut out = PacketWriter::new();
            out.u8(u8::try_from(key.len()).unwrap_or(u8::MAX)).bytes(key);
            handled.send.push(Packet::written(protocol::EMULE, extended::PUBLICKEY, out));
        }
        if state == STATE_SIGNATURE || state == STATE_KEY_AND_SIGNATURE {
            self.signature_wanted = true;
            self.try_sign(handled);
        }
        Ok(())
    }

    fn key_received(&mut self, packet: &Packet, handled: &mut Handled) -> Result<(), DecodeError> {
        let size = packet.payload().len();
        if !(MIN_PACKET..=MAX_PACKET).contains(&size) {
            return Ok(());
        }
        let mut input = packet.reader();
        let length = usize::from(input.u8()?);
        if length != size.saturating_sub(1) || length > rsa_keys::MAX_PUBLIC_KEY_SIZE {
            return Ok(());
        }
        let key = input.bytes(length)?.to_vec();
        // A pinned key never changes: a different one fails the check of the signature.
        if !self.pinned {
            self.peer_key = Some(key);
        }
        self.try_sign(handled);
        Ok(())
    }

    fn signature_received(&mut self, packet: &Packet, handled: &mut Handled) -> Result<(), DecodeError> {
        let size = packet.payload().len();
        if self.done || self.challenge_for == 0 || !(MIN_PACKET..=MAX_PACKET).contains(&size) {
            return Ok(());
        }
        let Some(peer_key) = self.peer_key.clone() else {
            return Ok(());
        };
        let mut input = packet.reader();
        let length = usize::from(input.u8()?);
        let (signature, bindings) = if length == size.saturating_sub(1) {
            (input.bytes(length)?.to_vec(), vec![None])
        } else if length == size.saturating_sub(2) && self.peer_versions & V2 != 0 {
            let signature = input.bytes(length)?.to_vec();
            let Some(kind) = IpKind::of(input.u8()?) else {
                return Ok(());
            };
            let bindings: Vec<Option<IpBinding>> = self.bindings_of(kind).into_iter().map(Some).collect();
            if bindings.is_empty() {
                self.finish(false, &peer_key, handled);
                return Ok(());
            }
            (signature, bindings)
        } else {
            return Ok(());
        };
        let valid = rsa_keys::decode_public(&peer_key).is_ok_and(|key| {
            bindings.iter().any(|binding| {
                secure_ident::verify(&key, &signature, self.own.public_key(), self.challenge_for, binding.as_ref())
            })
        });
        self.finish(valid, &peer_key, handled);
        Ok(())
    }

    // The addresses the peer may have bound its signature to, as we can check them. Our challenge ties any signature to
    // this connection; the address keeps it from being relayed by a client in the middle.
    fn bindings_of(&self, kind: IpKind) -> Vec<IpBinding> {
        match kind {
            IpKind::LocalClient => vec![IpBinding { address: self.peer_address, kind }],
            // Ours as the peer sees it: the public one, or that of the connection when nothing translates it.
            IpKind::RemoteClient => {
                let mut ours: Vec<IpAddr> = self.our_public_address.map(IpAddr::V4).into_iter().collect();
                if let Some(local) = self.our_local_address
                    && !ours.contains(&local)
                {
                    ours.push(local);
                }
                ours.into_iter().map(|address| IpBinding { address, kind }).collect()
            }
            // Bound to no address (0): no weaker than a version 1 signature, which is taken too.
            IpKind::None => vec![IpBinding { address: IpAddr::V4(Ipv4Addr::UNSPECIFIED), kind }],
        }
    }

    fn finish(&mut self, valid: bool, peer_key: &[u8], handled: &mut Handled) {
        self.done = true;
        handled.outcome = Some(if valid {
            Outcome::Identified { user_hash: self.peer_hash, public_key: peer_key.to_vec() }
        } else {
            Outcome::Failed { user_hash: self.peer_hash }
        });
    }

    fn try_sign(&mut self, handled: &mut Handled) {
        let Some(peer_key) = &self.peer_key else {
            return;
        };
        if !self.signature_wanted || self.challenge_from == 0 {
            return;
        }
        self.signature_wanted = false;
        // Version 2 binds the signature to our address when we know it, else to the peer's as we see it, which a peer
        // without a HighID cannot check: version 1 then, when the peer takes it. Over IPv6 our address is the one of
        // the connection, which nothing translates.
        let ours = if self.peer_address.is_ipv6() {
            self.our_local_address.filter(IpAddr::is_ipv6)
        } else {
            self.our_public_address.map(IpAddr::V4)
        };
        let bound = self.peer_versions & V2 != 0 && (ours.is_some() || self.peer_versions & V1 == 0);
        let binding = bound.then_some(match ours {
            Some(address) => IpBinding { address, kind: IpKind::LocalClient },
            None => IpBinding { address: self.peer_address, kind: IpKind::RemoteClient },
        });
        let signature = match secure_ident::sign(self.own.key(), peer_key, self.challenge_from, binding.as_ref()) {
            Ok(signature) => signature,
            Err(e) => {
                tracing::debug!("Cannot sign the challenge of a client: {e}");
                return;
            }
        };
        let mut out = PacketWriter::new();
        out.u8(u8::try_from(signature.len()).unwrap_or(u8::MAX)).bytes(&signature);
        if let Some(binding) = binding {
            out.u8(binding.kind.code());
        }
        handled.send.push(Packet::written(protocol::EMULE, extended::SIGNATURE, out));
    }
}

/// The identification versions a hello announces.
pub fn versions(hello: &Hello) -> u32 {
    hello.misc_options1() >> 16 & 0x0F
}
