// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad 2 messages. Ids are 128-bit numbers written as four little-endian words, most significant first; addresses are
//! host-order numbers written little-endian.

use crate::kad::uint128::UInt128;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};
use crate::protocol::packet::opcodes::kad;
use crate::protocol::packet::{Packet, protocol};
use crate::protocol::tag::Tag;
use crate::protocol::tag::tag_codec::{read_kad_list, write_kad_list};

/// The Kad version we speak.
pub const VERSION: u8 = 0x0A;
/// Contacts per bootstrap answer at most.
pub const MAX_BOOTSTRAP_CONTACTS: usize = 20;
/// Contacts per lookup answer at most.
pub const MAX_LOOKUP_CONTACTS: u8 = 31;
/// Results per keyword publish packet at most.
pub const MAX_PUBLISHED_FILES: usize = 50;

const CONTACT_SIZE: usize = 16 + 4 + 2 + 2 + 1;
const MAX_RESULTS: usize = 1_000;
// An id and an empty tag list.
const MIN_ENTRY_SIZE: usize = 16 + 1;

/// A contact as packets carry it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Contact {
    pub id: UInt128,
    pub ip: u32,
    pub udp_port: u16,
    pub tcp_port: u16,
    pub version: u8,
}

impl Contact {
    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        Ok(Self {
            id: UInt128::read(input)?,
            ip: input.u32()?,
            udp_port: input.u16()?,
            tcp_port: input.u16()?,
            version: input.u8()?,
        })
    }

    pub fn write(&self, out: &mut PacketWriter) {
        self.id.write(out);
        out.u32(self.ip).u16(self.udp_port).u16(self.tcp_port).u8(self.version);
    }
}

/// What a node says of itself in a hello or a bootstrap answer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sender {
    pub id: UInt128,
    pub tcp_port: u16,
    pub version: u8,
}

impl Sender {
    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        Ok(Self { id: UInt128::read(input)?, tcp_port: input.u16()?, version: input.u8()? })
    }

    pub fn write(&self, out: &mut PacketWriter) {
        self.id.write(out);
        out.u16(self.tcp_port).u8(self.version);
    }
}

pub fn bootstrap_request() -> Packet {
    Packet::empty(protocol::KAD, kad::BOOTSTRAP_REQ)
}

/// KADEMLIA2_BOOTSTRAP_RES: the sender, then some of its contacts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BootstrapAnswer {
    pub sender: Sender,
    pub contacts: Vec<Contact>,
}

impl BootstrapAnswer {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.sender.write(&mut out);
        out.count16(self.contacts.len())?;
        for contact in &self.contacts {
            contact.write(&mut out);
        }
        Ok(Packet::written(protocol::KAD, kad::BOOTSTRAP_RES, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let sender = Sender::read(&mut input)?;
        let announced = input.u16()?;
        let count = input.count(u64::from(announced), CONTACT_SIZE, MAX_BOOTSTRAP_CONTACTS * 10)?;
        let contacts = (0..count).map(|_| Contact::read(&mut input)).collect::<Result<_, _>>()?;
        Ok(Self { sender, contacts })
    }
}

/// KADEMLIA2_HELLO_REQ and _RES: the sender and a few tags.
#[derive(Debug, Clone, PartialEq)]
pub struct Hello {
    pub sender: Sender,
    pub tags: Vec<Tag>,
}

impl Hello {
    pub fn encode(&self, answer: bool) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.sender.write(&mut out);
        write_kad_list(&mut out, &self.tags)?;
        Ok(Packet::written(protocol::KAD, if answer { kad::HELLO_RES } else { kad::HELLO_REQ }, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { sender: Sender::read(&mut input)?, tags: read_kad_list(&mut input)? })
    }
}

/// KADEMLIA2_HELLO_RES_ACK: the sender id and no tags.
pub fn hello_ack(sender: &UInt128) -> Packet {
    let mut out = PacketWriter::new();
    sender.write(&mut out);
    out.u8(0);
    Packet::written(protocol::KAD, kad::HELLO_RES_ACK, out)
}

pub fn decode_hello_ack(packet: &Packet) -> Result<UInt128, DecodeError> {
    UInt128::read(&mut packet.reader())
}

/// KADEMLIA2_REQ: up to `count` contacts close to `target`, from 1 to [`MAX_LOOKUP_CONTACTS`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Request {
    pub count: u8,
    pub target: UInt128,
    pub receiver: UInt128,
}

impl Request {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        // The count travels in the low five bits of its byte.
        if !(1..=MAX_LOOKUP_CONTACTS).contains(&self.count) {
            return Err(EncodeError::new(format!(
                "a lookup asks for 1 to {MAX_LOOKUP_CONTACTS} contacts: count {}",
                self.count
            )));
        }
        let mut out = PacketWriter::new();
        out.u8(self.count);
        self.target.write(&mut out);
        self.receiver.write(&mut out);
        Ok(Packet::written(protocol::KAD, kad::REQ, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let count = input.u8()? & 0x1F;
        if count == 0 {
            return Err(DecodeError::new("a lookup request asks for no contact"));
        }
        Ok(Self { count, target: UInt128::read(&mut input)?, receiver: UInt128::read(&mut input)? })
    }
}

/// KADEMLIA2_RES: contacts close to `target`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Answer {
    pub target: UInt128,
    pub contacts: Vec<Contact>,
}

impl Answer {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.target.write(&mut out);
        out.count8(self.contacts.len())?;
        for contact in &self.contacts {
            contact.write(&mut out);
        }
        Ok(Packet::written(protocol::KAD, kad::RES, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let target = UInt128::read(&mut input)?;
        let count = usize::from(input.u8()?);
        if count.checked_mul(CONTACT_SIZE) != Some(input.remaining()) {
            return Err(DecodeError::new(format!("{count} contacts do not fill the answer")));
        }
        let contacts = (0..count).map(|_| Contact::read(&mut input)).collect::<Result<_, _>>()?;
        Ok(Self { target, contacts })
    }
}

/// KADEMLIA2_SEARCH_KEY_REQ: files under a keyword, with an optional search expression.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KeywordSearch {
    pub target: UInt128,
    pub start: u16,
    pub expression: Vec<u8>,
}

impl KeywordSearch {
    const WITH_EXPRESSION: u16 = 0x8000;

    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        self.target.write(&mut out);
        let flag = if self.expression.is_empty() { 0 } else { Self::WITH_EXPRESSION };
        out.u16(self.start & 0x7FFF | flag).bytes(&self.expression);
        Packet::written(protocol::KAD, kad::SEARCH_KEY_REQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let target = UInt128::read(&mut input)?;
        let start = input.u16()?;
        let expression = if start & Self::WITH_EXPRESSION != 0 { input.rest().to_vec() } else { Vec::new() };
        Ok(Self { target, start: start & 0x7FFF, expression })
    }
}

/// KADEMLIA2_SEARCH_SOURCE_REQ: sources of a file of `size` bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceSearch {
    pub target: UInt128,
    pub start: u16,
    pub size: u64,
}

impl SourceSearch {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        self.target.write(&mut out);
        out.u16(self.start & 0x7FFF).u64(self.size);
        Packet::written(protocol::KAD, kad::SEARCH_SOURCE_REQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { target: UInt128::read(&mut input)?, start: input.u16()? & 0x7FFF, size: input.u64()? })
    }
}

/// KADEMLIA2_SEARCH_NOTES_REQ: comments on a file of `size` bytes (0: any).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NotesSearch {
    pub target: UInt128,
    pub size: u64,
}

impl NotesSearch {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        self.target.write(&mut out);
        out.u64(self.size);
        Packet::written(protocol::KAD, kad::SEARCH_NOTES_REQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { target: UInt128::read(&mut input)?, size: input.u64()? })
    }
}

/// One entry of a search answer or a keyword publish: an id and its tags.
#[derive(Debug, Clone, PartialEq)]
pub struct Entry {
    pub id: UInt128,
    pub tags: Vec<Tag>,
}

impl Entry {
    pub fn read(input: &mut PacketReader<'_>) -> Result<Self, DecodeError> {
        Ok(Self { id: UInt128::read(input)?, tags: read_kad_list(input)? })
    }

    pub fn write(&self, out: &mut PacketWriter) -> Result<(), EncodeError> {
        self.id.write(out);
        write_kad_list(out, &self.tags)
    }

    /// Bytes the entry takes in a packet.
    pub fn size(&self) -> Result<usize, EncodeError> {
        let mut out = PacketWriter::new();
        self.write(&mut out)?;
        Ok(out.size())
    }
}

fn entries(input: &mut PacketReader<'_>) -> Result<Vec<Entry>, DecodeError> {
    let announced = input.u16()?;
    let count = input.count(u64::from(announced), MIN_ENTRY_SIZE, MAX_RESULTS)?;
    (0..count).map(|_| Entry::read(input)).collect()
}

fn write_entries(out: &mut PacketWriter, entries: &[Entry]) -> Result<(), EncodeError> {
    out.count16(entries.len())?;
    entries.iter().try_for_each(|entry| entry.write(out))
}

/// KADEMLIA2_SEARCH_RES: what the sender holds for `target`.
#[derive(Debug, Clone, PartialEq)]
pub struct SearchAnswer {
    pub sender: UInt128,
    pub target: UInt128,
    pub results: Vec<Entry>,
}

impl SearchAnswer {
    // The header, both ids and the count.
    const EMPTY_ANSWER: usize = 2 + 16 + 16 + 2;

    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.sender.write(&mut out);
        self.target.write(&mut out);
        write_entries(&mut out, &self.results)?;
        Ok(Packet::written(protocol::KAD, kad::SEARCH_RES, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let sender = UInt128::read(&mut input)?;
        let target = UInt128::read(&mut input)?;
        Ok(Self { sender, target, results: entries(&mut input)? })
    }

    /// Whether an answer of at most `max_bytes` can carry `entry`.
    pub fn fits(entry: &Entry, max_bytes: usize) -> Result<bool, EncodeError> {
        Ok(Self::EMPTY_ANSWER.saturating_add(entry.size()?) <= max_bytes)
    }

    /// Answers no larger than `max_bytes` each that together carry `results`; an entry too large for an answer of its
    /// own is left out.
    pub fn split(
        sender: UInt128,
        target: UInt128,
        results: &[Entry],
        max_bytes: usize,
    ) -> Result<Vec<Packet>, EncodeError> {
        let mut packets = Vec::new();
        let mut batch = Vec::new();
        let mut size = Self::EMPTY_ANSWER;
        for entry in results {
            let entry_size = entry.size()?;
            if Self::EMPTY_ANSWER.saturating_add(entry_size) > max_bytes {
                continue;
            }
            if size.saturating_add(entry_size) > max_bytes {
                packets.push(Self { sender, target, results: std::mem::take(&mut batch) }.encode()?);
                size = Self::EMPTY_ANSWER;
            }
            batch.push(entry.clone());
            size = size.saturating_add(entry_size);
        }
        if !batch.is_empty() {
            packets.push(Self { sender, target, results: batch }.encode()?);
        }
        Ok(packets)
    }
}

/// KADEMLIA2_PUBLISH_KEY_REQ: files under a keyword.
#[derive(Debug, Clone, PartialEq)]
pub struct KeywordPublish {
    pub keyword: UInt128,
    pub files: Vec<Entry>,
}

impl KeywordPublish {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.keyword.write(&mut out);
        write_entries(&mut out, &self.files)?;
        Ok(Packet::written(protocol::KAD, kad::PUBLISH_KEY_REQ, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        let keyword = UInt128::read(&mut input)?;
        Ok(Self { keyword, files: entries(&mut input)? })
    }
}

/// KADEMLIA2_PUBLISH_SOURCE_REQ: the publisher has `file`.
#[derive(Debug, Clone, PartialEq)]
pub struct SourcePublish {
    pub file: UInt128,
    pub source: UInt128,
    pub tags: Vec<Tag>,
}

impl SourcePublish {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.file.write(&mut out);
        self.source.write(&mut out);
        write_kad_list(&mut out, &self.tags)?;
        Ok(Packet::written(protocol::KAD, kad::PUBLISH_SOURCE_REQ, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self {
            file: UInt128::read(&mut input)?,
            source: UInt128::read(&mut input)?,
            tags: read_kad_list(&mut input)?,
        })
    }
}

/// KADEMLIA2_PUBLISH_NOTES_REQ: the comment and rating on `file` of the node `publisher`.
#[derive(Debug, Clone, PartialEq)]
pub struct NotesPublish {
    pub file: UInt128,
    pub publisher: UInt128,
    pub tags: Vec<Tag>,
}

impl NotesPublish {
    pub fn encode(&self) -> Result<Packet, EncodeError> {
        let mut out = PacketWriter::new();
        self.file.write(&mut out);
        self.publisher.write(&mut out);
        write_kad_list(&mut out, &self.tags)?;
        Ok(Packet::written(protocol::KAD, kad::PUBLISH_NOTES_REQ, out))
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self {
            file: UInt128::read(&mut input)?,
            publisher: UInt128::read(&mut input)?,
            tags: read_kad_list(&mut input)?,
        })
    }
}

/// KADEMLIA2_PUBLISH_RES: the publish of `target` was stored; load 0..100.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PublishAnswer {
    pub target: UInt128,
    pub load: u8,
}

impl PublishAnswer {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        self.target.write(&mut out);
        out.u8(self.load);
        Packet::written(protocol::KAD, kad::PUBLISH_RES, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { target: UInt128::read(&mut input)?, load: input.u8()? })
    }
}

/// KADEMLIA2_FIREWALLED2_REQ: our TCP port, user hash and connect options.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FirewallCheck {
    pub tcp_port: u16,
    pub user_hash: UInt128,
    pub connect_options: u8,
}

impl FirewallCheck {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u16(self.tcp_port);
        self.user_hash.write(&mut out);
        out.u8(self.connect_options);
        Packet::written(protocol::KAD, kad::FIREWALLED2_REQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { tcp_port: input.u16()?, user_hash: UInt128::read(&mut input)?, connect_options: input.u8()? })
    }
}

/// KADEMLIA_FIREWALLED_RES: the address the helper sees us at.
pub fn firewall_answer(ip: u32) -> Packet {
    Packet::of(protocol::KAD, kad::FIREWALLED_RES, ip.to_le_bytes())
}

pub fn decode_firewall_answer(packet: &Packet) -> Result<u32, DecodeError> {
    if packet.length() != 4 {
        return Err(DecodeError::new("a firewall answer has 4 bytes"));
    }
    packet.reader().u32()
}

/// KADEMLIA_FINDBUDDY_REQ: a firewalled node looks for a buddy under `buddy_id`, the complement of its Kad id, and
/// says how to reach it: its user hash and TCP port; newer nodes add their connect options.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FindBuddy {
    pub buddy_id: UInt128,
    pub user_hash: UInt128,
    pub tcp_port: u16,
    pub connect_options: Option<u8>,
}

impl FindBuddy {
    pub fn encode(&self) -> Packet {
        Packet::written(
            protocol::KAD,
            kad::FINDBUDDY_REQ,
            buddy_fields(&self.buddy_id, &self.user_hash, self.tcp_port, self.connect_options),
        )
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let (buddy_id, user_hash, tcp_port, connect_options) = read_buddy_fields(packet)?;
        Ok(Self { buddy_id, user_hash, tcp_port, connect_options })
    }
}

/// KADEMLIA_FINDBUDDY_RES: a node offers to be the buddy of `buddy_id`; newer nodes add their connect options.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BuddyOffer {
    pub buddy_id: UInt128,
    pub user_hash: UInt128,
    pub tcp_port: u16,
    pub connect_options: Option<u8>,
}

impl BuddyOffer {
    pub fn encode(&self) -> Packet {
        Packet::written(
            protocol::KAD,
            kad::FINDBUDDY_RES,
            buddy_fields(&self.buddy_id, &self.user_hash, self.tcp_port, self.connect_options),
        )
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let (buddy_id, user_hash, tcp_port, connect_options) = read_buddy_fields(packet)?;
        Ok(Self { buddy_id, user_hash, tcp_port, connect_options })
    }
}

fn buddy_fields(buddy_id: &UInt128, user_hash: &UInt128, tcp_port: u16, connect_options: Option<u8>) -> PacketWriter {
    let mut out = PacketWriter::new();
    buddy_id.write(&mut out);
    user_hash.write(&mut out);
    out.u16(tcp_port);
    if let Some(options) = connect_options {
        out.u8(options);
    }
    out
}

fn read_buddy_fields(packet: &Packet) -> Result<(UInt128, UInt128, u16, Option<u8>), DecodeError> {
    let mut input = packet.reader();
    let buddy_id = UInt128::read(&mut input)?;
    let user_hash = UInt128::read(&mut input)?;
    let tcp_port = input.u16()?;
    let connect_options = if input.has_remaining() { Some(input.u8()?) } else { None };
    Ok((buddy_id, user_hash, tcp_port, connect_options))
}

/// KADEMLIA_CALLBACK_REQ, sent plain to the buddy of a firewalled source: the source is asked to connect to our
/// `tcp_port` for `file`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CallbackRequest {
    pub buddy_id: UInt128,
    pub file: UInt128,
    pub tcp_port: u16,
}

impl CallbackRequest {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        self.buddy_id.write(&mut out);
        self.file.write(&mut out);
        out.u16(self.tcp_port);
        Packet::written(protocol::KAD, kad::CALLBACK_REQ, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { buddy_id: UInt128::read(&mut input)?, file: UInt128::read(&mut input)?, tcp_port: input.u16()? })
    }
}

/// KADEMLIA2_FIREWALLUDP: the datagram a helper of the UDP firewall test sends to `port`; `error` is non-zero when the
/// helper knew us already, which makes the outcome worthless.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FirewallUdp {
    pub error: u8,
    pub port: u16,
}

impl FirewallUdp {
    pub fn encode(&self) -> Packet {
        let mut out = PacketWriter::new();
        out.u8(self.error).u16(self.port);
        Packet::written(protocol::KAD, kad::FIREWALLUDP, out)
    }

    pub fn decode(packet: &Packet) -> Result<Self, DecodeError> {
        let mut input = packet.reader();
        Ok(Self { error: input.u8()?, port: input.u16()? })
    }
}

pub fn ping() -> Packet {
    Packet::empty(protocol::KAD, kad::PING)
}

/// KADEMLIA2_PONG: the UDP port the answering node saw us on.
pub fn pong(port: u16) -> Packet {
    Packet::of(protocol::KAD, kad::PONG, port.to_le_bytes())
}

pub fn decode_pong(packet: &Packet) -> Result<u16, DecodeError> {
    packet.reader().u16()
}
