// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad contacts, `nodes.dat`. Version 0: `<count ≠ 0>` then 25-byte records ending with a contact type; version 1:
//! `<0><1><count>` with a Kad version instead; version 2 (written): `<0><2><count>`, 34-byte records adding the UDP key
//! and the verified flag; version 3: `<0><3><edition>`, edition 1 being a bootstrap list of 25-byte records. IPs are
//! stored as little-endian numbers.

use crate::kad::uint128::UInt128;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter};

/// A contact's obfuscation key, valid only while our own IP is `ip`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct UdpKey {
    pub key: u32,
    pub ip: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contact {
    pub id: UInt128,
    pub ip: u32,
    pub udp_port: u16,
    pub tcp_port: u16,
    pub version: u8,
    pub contact_type: u8,
    pub udp_key: UdpKey,
    pub verified: bool,
}

/// What was read: the file version, whether it is a bootstrap-only list, the contacts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contents {
    pub file_version: u32,
    pub bootstrap: bool,
    pub contacts: Vec<Contact>,
}

/// Contacts written at most; reading is limited by the file size.
pub const MAX_WRITTEN: usize = 200;
const MAX_CONTACTS: usize = 100_000;
const RECORD: usize = 34;
const RECORD_SHORT: usize = 25;

pub fn read(data: &[u8]) -> Result<Contents, DecodeError> {
    let mut input = PacketReader::of(data);
    let mut count = input.u32()?;
    let mut version = 0;
    if count == 0 {
        if input.remaining() < 4 {
            return Ok(Contents { file_version: 0, bootstrap: false, contacts: Vec::new() });
        }
        version = input.u32()?;
        if version == 3 && input.u32()? == 1 {
            let declared = input.u32()?;
            let contacts = records(&mut input, declared, 1, true)?;
            return Ok(Contents { file_version: 3, bootstrap: true, contacts });
        }
        if !(1..=3).contains(&version) {
            return Ok(Contents { file_version: version, bootstrap: false, contacts: Vec::new() });
        }
        count = input.u32()?;
    }
    let contacts = records(&mut input, count, version, false)?;
    Ok(Contents { file_version: version, bootstrap: false, contacts })
}

/// Writes version 2, which every Kad client reads.
pub fn write(contacts: &[Contact]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u32(0).u32(2).count32(contacts.len())?;
    for contact in contacts {
        contact.id.write(&mut out);
        out.u32(contact.ip)
            .u16(contact.udp_port)
            .u16(contact.tcp_port)
            .u8(contact.version)
            .u32(contact.udp_key.key)
            .u32(contact.udp_key.ip)
            .u8(u8::from(contact.verified));
    }
    Ok(out.into_byte_array())
}

fn records(input: &mut PacketReader<'_>, count: u32, version: u32, exact: bool) -> Result<Vec<Contact>, DecodeError> {
    let size = if version >= 2 && !exact { RECORD } else { RECORD_SHORT };
    let n = input.count(u64::from(count), size, MAX_CONTACTS)?;
    if exact && n.checked_mul(size) != Some(input.remaining()) {
        return Err(DecodeError::new("bootstrap nodes.dat has trailing data"));
    }
    (0..n)
        .map(|_| {
            let id = UInt128::read(input)?;
            let ip = input.u32()?;
            let udp_port = input.u16()?;
            let tcp_port = input.u16()?;
            let last = input.u8()?;
            let mut udp_key = UdpKey::default();
            let mut verified = false;
            if size == RECORD {
                udp_key = UdpKey { key: input.u32()?, ip: input.u32()? };
                verified = input.u8()? != 0;
            }
            let (version, contact_type) = if version == 0 { (0, last) } else { (last, 0) };
            Ok(Contact { id, ip, udp_port, tcp_port, version, contact_type, udp_key, verified })
        })
        .collect()
}
