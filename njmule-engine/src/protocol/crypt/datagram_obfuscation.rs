// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! UDP obfuscation. An obfuscated datagram is `<marker 1><random part 2>` in clear followed by RC4-encrypted `<sync
//! 4><padding length 1><padding>[<receiver key 4><sender key 4>]<packet>`; the key stream is not discarded and the
//! bracketed verify keys exist only for Kad.
//!
//! RC4 key = MD5 of:
//!
//! - eD2K client: receiver user hash, sender IP, 91, random part (sync `0x395F2EC1`);
//! - Kad: receiver Kad id or the receiver's verify key, random part (sync `0x395F2EC1`);
//! - server: server key, 0x6B towards the server or 0xA5 from it, random part (sync `0x13EF24D5`).
//!
//! The marker's low bits hint which key to try first: `x1` eD2K, `00` Kad id, `10` Kad verify key.

use rand::{CryptoRng, RngExt};

use crate::crypto::rc4::Rc4;
use crate::protocol::crypt::stream_obfuscation::md5;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader};
use crate::protocol::packet::protocol::{self, EDONKEY};
use crate::protocol::packet::{Packet, udp_framing};

/// How a received datagram was protected.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Kind {
    Plain,
    Ed2k,
    KadIdKey,
    KadVerifyKey,
    Server,
}

/// A received packet; the verify keys are those carried by a Kad datagram (0 otherwise).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Received {
    pub packet: Packet,
    pub kind: Kind,
    pub receiver_verify_key: u32,
    pub sender_verify_key: u32,
}

pub const ED2K_OVERHEAD: usize = 8;
pub const KAD_OVERHEAD: usize = 16;

pub const MAGIC_ED2K: u8 = 91;
pub const SYNC_CLIENT: u32 = 0x395F_2EC1;
pub const SYNC_SERVER: u32 = 0x13EF_24D5;
pub const MAGIC_TO_SERVER: u8 = 0x6B;
pub const MAGIC_FROM_SERVER: u8 = 0xA5;

const KAD_KEYS: usize = 8;

/// The verify key we give the Kad node at `peer_ip` (host order), derived from our secret `udp_key`: 1 to 0xFFFFFFFE,
/// and the same for that address until the secret changes.
pub fn kad_verify_key(udp_key: u32, peer_ip: u32) -> u32 {
    let digest = md5(&[peer_ip.to_be_bytes(), udp_key.to_le_bytes()].concat());
    let (words, _) = digest.as_chunks::<4>();
    let folded = words.iter().fold(0, |folded, word| folded ^ u32::from_le_bytes(*word));
    (folded % 0xFFFF_FFFE).saturating_add(1)
}

pub fn encrypt_for_client(
    datagram: &[u8],
    receiver_user_hash: &[u8; 16],
    own_public_ipv4: [u8; 4],
    random: &mut impl CryptoRng,
) -> Vec<u8> {
    let marker = client_marker(random, None);
    encrypt_for_client_with(datagram, receiver_user_hash, own_public_ipv4, marker, key_part(random))
}

pub fn encrypt_for_client_with(
    datagram: &[u8],
    receiver_user_hash: &[u8; 16],
    own_public_ipv4: [u8; 4],
    marker: u8,
    key_part: [u8; 2],
) -> Vec<u8> {
    let rc4 = rc4(&ed2k_material(receiver_user_hash, own_public_ipv4, key_part));
    seal(datagram, marker, key_part, SYNC_CLIENT, rc4, &[])
}

/// Kad datagram, keyed with the receiver's Kad id when known (and not zero), otherwise with the verify key the receiver
/// gave us; with neither there is no key.
pub fn encrypt_for_kad(
    datagram: &[u8],
    receiver_kad_id: Option<&[u8; 16]>,
    receiver_verify_key: u32,
    sender_verify_key: u32,
    random: &mut impl CryptoRng,
) -> Result<Vec<u8>, EncodeError> {
    let kad_bits = if known(receiver_kad_id).is_some() { 0 } else { 2 };
    let marker = client_marker(random, Some(kad_bits));
    encrypt_for_kad_with(datagram, receiver_kad_id, receiver_verify_key, sender_verify_key, marker, key_part(random))
}

pub fn encrypt_for_kad_with(
    datagram: &[u8],
    receiver_kad_id: Option<&[u8; 16]>,
    receiver_verify_key: u32,
    sender_verify_key: u32,
    marker: u8,
    key_part: [u8; 2],
) -> Result<Vec<u8>, EncodeError> {
    let material = match known(receiver_kad_id) {
        Some(id) => [id.as_slice(), &key_part].concat(),
        None if receiver_verify_key != 0 => [receiver_verify_key.to_le_bytes().as_slice(), &key_part].concat(),
        None => return Err(EncodeError::new("a Kad id or a receiver verify key is required")),
    };
    let keys = [receiver_verify_key.to_le_bytes(), sender_verify_key.to_le_bytes()].concat();
    Ok(seal(datagram, marker, key_part, SYNC_CLIENT, rc4(&material), &keys))
}

pub fn encrypt_for_server(datagram: &[u8], server_key: u32, random: &mut impl CryptoRng) -> Vec<u8> {
    let marker = server_marker(random);
    encrypt_for_server_with(datagram, server_key, marker, key_part(random))
}

pub fn encrypt_for_server_with(datagram: &[u8], server_key: u32, marker: u8, key_part: [u8; 2]) -> Vec<u8> {
    let rc4 = rc4(&server_material(server_key, MAGIC_TO_SERVER, key_part));
    seal(datagram, marker, key_part, SYNC_SERVER, rc4, &[])
}

/// The server's side: an answer for a client that holds `server_key`.
pub fn encrypt_from_server(datagram: &[u8], server_key: u32, random: &mut impl CryptoRng) -> Vec<u8> {
    let marker = server_marker(random);
    let key_part = key_part(random);
    let rc4 = rc4(&server_material(server_key, MAGIC_FROM_SERVER, key_part));
    seal(datagram, marker, key_part, SYNC_SERVER, rc4, &[])
}

/// Decodes a datagram from a client or Kad node, trying the possible keys in the order the marker hints. Datagrams that
/// are not obfuscated, or that match no key, are decoded as plain.
///
/// `own_kad_id` is `None` when Kad is not running; `own_verify_key_for_sender` is the verify key we hand out to the
/// sender's IP (0 if unknown).
pub fn decode_from_client(
    data: &[u8],
    own_user_hash: &[u8; 16],
    sender_ipv4: [u8; 4],
    own_kad_id: Option<&[u8; 16]>,
    own_verify_key_for_sender: u32,
) -> Result<Received, DecodeError> {
    let (marker, key_part) = match data {
        [marker, a, b, ..] if data.len() > ED2K_OVERHEAD && !is_plain_client_protocol(*marker) => (*marker, [*a, *b]),
        _ => return plain(data),
    };
    // 0: Kad id, 1: eD2K, 2: Kad verify key; without Kad only the eD2K key.
    let (first, tries) = match own_kad_id {
        Some(_) if marker & 1 != 0 => (1, 3),
        Some(_) => (usize::from(marker & 2), 3),
        None => (1, 1),
    };
    for attempt in [0, 1, 2].into_iter().cycle().skip(first).take(tries) {
        let (kind, material) = match (attempt, own_kad_id) {
            (0, Some(id)) => (Kind::KadIdKey, [id.as_slice(), &key_part].concat()),
            (1, _) => (Kind::Ed2k, ed2k_material(own_user_hash, sender_ipv4, key_part)),
            _ => (Kind::KadVerifyKey, [own_verify_key_for_sender.to_le_bytes().as_slice(), &key_part].concat()),
        };
        if let Some(received) = open(data, rc4(&material), SYNC_CLIENT, kind)? {
            return Ok(received);
        }
    }
    plain(data)
}

/// Decodes a datagram from a server whose UDP key is `server_key` (0: none).
pub fn decode_from_server(data: &[u8], server_key: u32) -> Result<Received, DecodeError> {
    decode_server_datagram(data, server_key, MAGIC_FROM_SERVER)
}

/// The server's side, as the test kit's server plays it: decodes what a client holding `server_key` sent.
pub fn decode_at_server(data: &[u8], server_key: u32) -> Result<Received, DecodeError> {
    decode_server_datagram(data, server_key, MAGIC_TO_SERVER)
}

fn decode_server_datagram(data: &[u8], server_key: u32, magic: u8) -> Result<Received, DecodeError> {
    match data {
        [marker, a, b, ..] if data.len() > ED2K_OVERHEAD && server_key != 0 && *marker != EDONKEY => {
            let rc4 = rc4(&server_material(server_key, magic, [*a, *b]));
            match open(data, rc4, SYNC_SERVER, Kind::Server)? {
                Some(received) => Ok(received),
                None => plain(data),
            }
        }
        _ => plain(data),
    }
}

fn open(data: &[u8], mut rc4: Rc4, sync: u32, kind: Kind) -> Result<Option<Received>, DecodeError> {
    let Some((head, body)) = data.get(3..).and_then(<[u8]>::split_first_chunk::<5>) else {
        return Ok(None);
    };
    let mut head = *head;
    rc4.apply(&mut head);
    let [s0, s1, s2, s3, padding] = head;
    let padding = usize::from(padding & 0x0F);
    if u32::from_le_bytes([s0, s1, s2, s3]) != sync || body.len() <= padding {
        return Ok(None);
    }
    let mut body = body.to_vec();
    rc4.apply(&mut body);
    let mut reader = PacketReader::of(&body);
    reader.skip(padding)?;
    let (receiver_verify_key, sender_verify_key) = if matches!(kind, Kind::KadIdKey | Kind::KadVerifyKey) {
        if reader.remaining() <= KAD_KEYS {
            return Ok(None);
        }
        (reader.u32()?, reader.u32()?)
    } else {
        (0, 0)
    };
    let packet = udp_framing::decode(reader.rest())?;
    Ok(Some(Received { packet, kind, receiver_verify_key, sender_verify_key }))
}

fn seal(datagram: &[u8], marker: u8, key_part: [u8; 2], sync: u32, mut rc4: Rc4, kad_keys: &[u8]) -> Vec<u8> {
    let mut out = vec![marker];
    out.extend_from_slice(&key_part);
    out.extend_from_slice(&sync.to_le_bytes());
    out.push(0); // no padding
    out.extend_from_slice(kad_keys);
    out.extend_from_slice(datagram);
    if let Some(sealed) = out.get_mut(3..) {
        rc4.apply(sealed);
    }
    out
}

fn known(kad_id: Option<&[u8; 16]>) -> Option<&[u8; 16]> {
    kad_id.filter(|id| **id != [0; 16])
}

// eD2K markers end in 1; Kad markers end in their key's bits.
fn client_marker(random: &mut impl CryptoRng, kad_bits: Option<u8>) -> u8 {
    let shaped = |b: u8| kad_bits.map_or(b | 1, |bits| (b & !3) | bits);
    (0..32)
        .map(|_| shaped(random.random()))
        .find(|b| !is_plain_client_protocol(*b))
        .unwrap_or_else(|| shaped(0))
}

fn server_marker(random: &mut impl CryptoRng) -> u8 {
    loop {
        let candidate: u8 = random.random();
        if candidate != EDONKEY {
            return candidate;
        }
    }
}

fn is_plain_client_protocol(marker: u8) -> bool {
    matches!(
        marker,
        protocol::EMULE
            | protocol::KAD_PACKED
            | protocol::KAD
            | protocol::UDP_RESERVED_1
            | protocol::UDP_RESERVED_2
            | protocol::PACKED
    )
}

fn plain(data: &[u8]) -> Result<Received, DecodeError> {
    Ok(Received { packet: udp_framing::decode(data)?, kind: Kind::Plain, receiver_verify_key: 0, sender_verify_key: 0 })
}

fn ed2k_material(user_hash: &[u8; 16], ipv4: [u8; 4], key_part: [u8; 2]) -> Vec<u8> {
    let mut material = user_hash.to_vec();
    material.extend_from_slice(&ipv4);
    material.push(MAGIC_ED2K);
    material.extend_from_slice(&key_part);
    material
}

fn server_material(server_key: u32, magic: u8, key_part: [u8; 2]) -> Vec<u8> {
    let mut material = server_key.to_le_bytes().to_vec();
    material.push(magic);
    material.extend_from_slice(&key_part);
    material
}

fn key_part(random: &mut impl CryptoRng) -> [u8; 2] {
    random.random()
}

/// A key stream keyed with the MD5 of `material`.
fn rc4(material: &[u8]) -> Rc4 {
    Rc4::new(&md5(material))
}
