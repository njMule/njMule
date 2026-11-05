// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Friends, `emfriends.met`: `<0x0E><u32 count>` then `<user hash 16><last IP 4><last port 2><last seen u32><last
//! chatted u32><u32 tag count><tags>`; the name (id 1) is an old-encoding tag and the Kad id (id 2) a new-encoding hash
//! tag.

use crate::persist::format::met_codecs;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::tag::{Ed2kTagFormat, Tag, tag_codec, tags};
use crate::util::ipv4;

pub const HEADER: u8 = 0x0E;
pub const NAME: u8 = 0x01;
pub const KAD_ID: u8 = 0x02;
const MAX_FRIENDS: usize = 100_000;

#[derive(Debug, Clone, PartialEq)]
pub struct Friend {
    pub user_hash: [u8; 16],
    pub last_ip: u32,
    pub last_port: u16,
    pub last_seen: u32,
    pub last_chatted: u32,
    pub tags: Vec<Tag>,
}

impl Friend {
    pub fn name(&self) -> Option<&str> {
        tags::string_value(&self.tags, NAME)
    }

    pub fn kad_id(&self) -> Option<[u8; 16]> {
        tags::hash_value(&self.tags, KAD_ID).filter(|id| *id != [0; 16])
    }
}

pub fn read(data: &[u8]) -> Result<Vec<Friend>, DecodeError> {
    let mut input = PacketReader::of(data);
    met_codecs::header(&mut input, "emfriends.met", &[HEADER])?;
    let declared = input.u32()?;
    let count = input.count(u64::from(declared), 34, MAX_FRIENDS)?;
    (0..count)
        .map(|_| {
            Ok(Friend {
                user_hash: input.hash16()?,
                last_ip: ipv4::from_ed2k(input.u32()?),
                last_port: input.u16()?,
                last_seen: input.u32()?,
                last_chatted: input.u32()?,
                tags: met_codecs::read_tags(&mut input)?,
            })
        })
        .collect()
}

pub fn write(friends: &[Friend]) -> Result<Vec<u8>, EncodeError> {
    let mut out = PacketWriter::new();
    out.u8(HEADER).count32(friends.len())?;
    for friend in friends {
        out.hash16(&friend.user_hash)
            .u32(ipv4::to_ed2k(friend.last_ip))
            .u16(friend.last_port)
            .u32(friend.last_seen)
            .u32(friend.last_chatted)
            .count32(friend.tags.len())?;
        for tag in &friend.tags {
            let format = if tag.name().as_id() == Some(KAD_ID) { Ed2kTagFormat::New } else { Ed2kTagFormat::Old };
            tag_codec::write_ed2k(&mut out, tag, format, StringEncoding::Utf8BomIfNeeded)?;
        }
    }
    Ok(out.into_byte_array())
}
