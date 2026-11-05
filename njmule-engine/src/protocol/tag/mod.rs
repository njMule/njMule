// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! eD2K and Kad tags: typed name-value pairs.

pub mod client_tag;
pub mod file_tag;
pub mod media_tags;
pub mod server_tag;
pub mod tag_codec;
pub mod tags;

use std::fmt;

/// Wire values of the tag types.
pub mod tag_type {
    pub const HASH: u8 = 0x01;
    pub const STRING: u8 = 0x02;
    pub const UINT32: u8 = 0x03;
    pub const FLOAT32: u8 = 0x04;
    pub const BOOL: u8 = 0x05;
    pub const BOOLARRAY: u8 = 0x06;
    pub const BLOB: u8 = 0x07;
    pub const UINT16: u8 = 0x08;
    pub const UINT8: u8 = 0x09;
    pub const BSOB: u8 = 0x0A;
    pub const UINT64: u8 = 0x0B;
    /// STR1…STR16: a 1-16 byte string without length prefix (new eD2K encoding only).
    pub const STR1: u8 = 0x11;
    pub const STR16: u8 = 0x20;
    /// Flag on the type byte of the new eD2K encoding: a one-byte id follows.
    pub const NEW_ED2K_FLAG: u8 = 0x80;
}

/// The two ways eD2K writes a tag.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Ed2kTagFormat {
    /// The type byte with the id in it, short strings and integers as small as they fit.
    New,
    /// Every name with its length, integers on 32 or 64 bits: what .met files and older peers take.
    Old,
}

/// A tag name as raw bytes: one byte is a numeric id; longer names (`"\x09" "12"` for gaps) are byte strings.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct TagName(Vec<u8>);

impl TagName {
    pub fn id(id: u8) -> Self {
        Self(vec![id])
    }

    pub fn of_bytes(raw: &[u8]) -> Self {
        Self(raw.to_vec())
    }

    pub fn is_id(&self) -> bool {
        self.0.len() == 1
    }

    /// The numeric id, for a name of one byte.
    pub fn as_id(&self) -> Option<u8> {
        match self.0.as_slice() {
            [id] => Some(*id),
            _ => None,
        }
    }

    pub fn to_bytes(&self) -> &[u8] {
        &self.0
    }
}

impl fmt::Display for TagName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(id) = self.as_id() {
            return write!(f, "0x{id:02X}");
        }
        for byte in &self.0 {
            if (0x20..0x7F).contains(byte) {
                write!(f, "{}", char::from(*byte))?;
            } else {
                write!(f, "\\x{byte:02X}")?;
            }
        }
        Ok(())
    }
}

/// A typed eD2K/Kad tag. Integers of every width are one type: the encoders pick the width.
#[derive(Debug, Clone, PartialEq)]
pub enum Tag {
    /// An unsigned integer of up to 64 bits (UINT8/16/32/64 on the wire).
    Int {
        name: TagName,
        value: u64,
    },
    String {
        name: TagName,
        value: String,
    },
    Hash {
        name: TagName,
        value: [u8; 16],
    },
    Float {
        name: TagName,
        value: f32,
    },
    Bool {
        name: TagName,
        value: bool,
    },
    /// eD2K BOOLARRAY: `bit_count` bits followed by `bit_count / 8 + 1` bytes.
    BoolArray {
        name: TagName,
        bit_count: u16,
        value: Vec<u8>,
    },
    /// eD2K BLOB: a length-prefixed (u32) byte string.
    Blob {
        name: TagName,
        value: Vec<u8>,
    },
    /// BSOB: a short (u8 length) byte string, used by Kad.
    Bsob {
        name: TagName,
        value: Vec<u8>,
    },
}

impl Tag {
    pub fn name(&self) -> &TagName {
        match self {
            Tag::Int { name, .. }
            | Tag::String { name, .. }
            | Tag::Hash { name, .. }
            | Tag::Float { name, .. }
            | Tag::Bool { name, .. }
            | Tag::BoolArray { name, .. }
            | Tag::Blob { name, .. }
            | Tag::Bsob { name, .. } => name,
        }
    }
}
