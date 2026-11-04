// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Reads and writes tags in the two eD2K encodings and the Kad encoding. Unknown types are refused: skipping them
//! without consuming their data would put the rest of the list out of step.

use super::tag_type::{
    BLOB, BOOL, BOOLARRAY, BSOB, FLOAT32, HASH, NEW_ED2K_FLAG, STR1, STR16, STRING, UINT8, UINT16, UINT32, UINT64,
};
use super::{Ed2kTagFormat, Tag, TagName};
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding, ed2k_strings};

/// Smallest encoded eD2K tag: `<type|0x80><id><u8 value>`.
pub const MIN_ED2K_TAG_SIZE: usize = 3;

/// Smallest encoded Kad tag: `<type><u16 0><u8 value>`.
pub const MIN_KAD_TAG_SIZE: usize = 4;

pub fn read_ed2k(input: &mut PacketReader<'_>) -> Result<Tag, DecodeError> {
    let mut kind = input.u8()?;
    let name = if kind & NEW_ED2K_FLAG != 0 {
        kind &= !NEW_ED2K_FLAG;
        TagName::id(input.u8()?)
    } else {
        let length = input.u16()?;
        TagName::of_bytes(input.bytes(usize::from(length))?)
    };
    Ok(match kind {
        HASH => Tag::Hash { name, value: input.hash16()? },
        STRING => Tag::String { name, value: input.string16()? },
        UINT32 => Tag::Int { name, value: u64::from(input.u32()?) },
        UINT64 => Tag::Int { name, value: input.u64()? },
        UINT16 => Tag::Int { name, value: u64::from(input.u16()?) },
        UINT8 => Tag::Int { name, value: u64::from(input.u8()?) },
        FLOAT32 => Tag::Float { name, value: input.f32()? },
        BOOL => Tag::Bool { name, value: input.u8()? != 0 },
        BOOLARRAY => {
            let bit_count = input.u16()?;
            let value = input.bytes(usize::from(bit_count >> 3).saturating_add(1))?.to_vec();
            Tag::BoolArray { name, bit_count, value }
        }
        BLOB => {
            let length = input.u32()?;
            let fits = usize::try_from(length).ok().filter(|length| *length <= input.remaining());
            let Some(length) = fits else {
                return Err(DecodeError::new(format!("blob of {length} bytes in tag {name}")));
            };
            Tag::Blob { name, value: input.bytes(length)?.to_vec() }
        }
        BSOB => {
            let length = input.u8()?;
            Tag::Bsob { name, value: input.bytes(usize::from(length))?.to_vec() }
        }
        STR1..=STR16 => {
            let length = usize::from(kind.saturating_sub(STR1)).saturating_add(1);
            Tag::String { name, value: ed2k_strings::decode(input.bytes(length)?) }
        }
        _ => return Err(DecodeError::new(format!("unknown tag type 0x{kind:02X} for tag {name}"))),
    })
}

/// Reads `count` eD2K tags, after checking that they can fit in the remaining data.
pub fn read_ed2k_list(input: &mut PacketReader<'_>, count: u64, max_count: usize) -> Result<Vec<Tag>, DecodeError> {
    let n = input.count(count, MIN_ED2K_TAG_SIZE, max_count)?;
    (0..n).map(|_| read_ed2k(input)).collect()
}

pub fn write_ed2k(
    out: &mut PacketWriter,
    tag: &Tag,
    format: Ed2kTagFormat,
    encoding: StringEncoding,
) -> Result<(), EncodeError> {
    let mut string = None;
    let kind = match tag {
        Tag::Int { value, .. } if format == Ed2kTagFormat::New => smallest_int_type(*value),
        Tag::Int { value, .. } if fits32(*value) => UINT32,
        Tag::Int { .. } => UINT64,
        Tag::String { value, .. } => {
            let raw = ed2k_strings::encode(value, encoding);
            let short = u8::try_from(raw.len()).ok().filter(|length| (1..=16).contains(length));
            let kind = match short {
                Some(length) if format == Ed2kTagFormat::New => STR1.saturating_add(length).saturating_sub(1),
                _ => STRING,
            };
            string = Some(raw);
            kind
        }
        Tag::Hash { .. } => HASH,
        Tag::Float { .. } => FLOAT32,
        Tag::Bool { .. } => BOOL,
        Tag::BoolArray { .. } => BOOLARRAY,
        Tag::Blob { .. } => BLOB,
        Tag::Bsob { name, .. } => {
            return Err(EncodeError::new(format!("other clients cannot read BSOB in eD2K tags: {name}")));
        }
    };
    let name = tag.name();
    match name.as_id() {
        Some(id) if format == Ed2kTagFormat::New => {
            out.u8(kind | NEW_ED2K_FLAG).u8(id);
        }
        _ => write_name(out, kind, name)?,
    }
    write_value(out, tag, kind, string.as_deref())
}

pub fn write_ed2k_list(
    out: &mut PacketWriter,
    tags: &[Tag],
    format: Ed2kTagFormat,
    encoding: StringEncoding,
) -> Result<(), EncodeError> {
    tags.iter().try_for_each(|tag| write_ed2k(out, tag, format, encoding))
}

pub fn read_kad(input: &mut PacketReader<'_>) -> Result<Tag, DecodeError> {
    let kind = input.u8()?;
    let length = input.u16()?;
    let name = TagName::of_bytes(input.bytes(usize::from(length))?);
    Ok(match kind {
        HASH => Tag::Hash { name, value: input.hash16()? },
        STRING => Tag::String { name, value: input.string16()? },
        UINT64 => Tag::Int { name, value: input.u64()? },
        UINT32 => Tag::Int { name, value: u64::from(input.u32()?) },
        UINT16 => Tag::Int { name, value: u64::from(input.u16()?) },
        UINT8 => Tag::Int { name, value: u64::from(input.u8()?) },
        FLOAT32 => Tag::Float { name, value: input.f32()? },
        BSOB => {
            let length = input.u8()?;
            Tag::Bsob { name, value: input.bytes(usize::from(length))?.to_vec() }
        }
        _ => return Err(DecodeError::new(format!("unsupported Kad tag type 0x{kind:02X} for tag {name}"))),
    })
}

/// A Kad tag list: `<u8 count><tags>`.
pub fn read_kad_list(input: &mut PacketReader<'_>) -> Result<Vec<Tag>, DecodeError> {
    let count = input.u8()?;
    let n = input.count(u64::from(count), MIN_KAD_TAG_SIZE, 0xFF)?;
    (0..n).map(|_| read_kad(input)).collect()
}

pub fn write_kad(out: &mut PacketWriter, tag: &Tag) -> Result<(), EncodeError> {
    let kind = match tag {
        Tag::Int { value, .. } => smallest_int_type(*value),
        Tag::String { .. } => STRING,
        Tag::Hash { .. } => HASH,
        Tag::Float { .. } => FLOAT32,
        Tag::Bsob { .. } => BSOB,
        Tag::Bool { name, .. } => return Err(unsupported_in_kad("BoolTag", name)),
        Tag::BoolArray { name, .. } => return Err(unsupported_in_kad("BoolArrayTag", name)),
        Tag::Blob { name, .. } => return Err(unsupported_in_kad("BlobTag", name)),
    };
    write_name(out, kind, tag.name())?;
    let string = match tag {
        Tag::String { value, .. } => Some(ed2k_strings::encode(value, StringEncoding::Utf8)),
        _ => None,
    };
    write_value(out, tag, kind, string.as_deref())
}

pub fn write_kad_list(out: &mut PacketWriter, tags: &[Tag]) -> Result<(), EncodeError> {
    let count = u8::try_from(tags.len()).map_err(|_| EncodeError::new("a Kad tag list holds at most 255 tags"))?;
    out.u8(count);
    tags.iter().try_for_each(|tag| write_kad(out, tag))
}

fn write_name(out: &mut PacketWriter, kind: u8, name: &TagName) -> Result<(), EncodeError> {
    let raw = name.to_bytes();
    let length = u16::try_from(raw.len()).map_err(|_| EncodeError::new(format!("tag name too long: {}", raw.len())))?;
    out.u8(kind).u16(length).bytes(raw);
    Ok(())
}

// The value of `tag` as `kind` says; `string` holds a string tag's bytes in its encoding.
fn write_value(out: &mut PacketWriter, tag: &Tag, kind: u8, string: Option<&[u8]>) -> Result<(), EncodeError> {
    match tag {
        // The type picked for the value holds it: the casts take the bits it fits in.
        Tag::Int { value, .. } => match kind {
            UINT8 => {
                out.u8(*value as u8);
            }
            UINT16 => {
                out.u16(*value as u16);
            }
            UINT32 => {
                out.u32(*value as u32);
            }
            _ => {
                out.u64(*value);
            }
        },
        Tag::String { name, .. } => {
            let raw = string.unwrap_or_default();
            if kind == STRING {
                let length = u16::try_from(raw.len())
                    .map_err(|_| EncodeError::new(format!("string too long for tag {name}: {}", raw.len())))?;
                out.u16(length);
            }
            out.bytes(raw);
        }
        Tag::Hash { value, .. } => {
            out.hash16(value);
        }
        Tag::Float { value, .. } => {
            out.f32(*value);
        }
        Tag::Bool { value, .. } => {
            out.u8(u8::from(*value));
        }
        Tag::BoolArray { bit_count, value, .. } => {
            out.u16(*bit_count).bytes(value);
        }
        Tag::Blob { name, value } => {
            let length =
                u32::try_from(value.len()).map_err(|_| EncodeError::new(format!("blob too long for tag {name}")))?;
            out.u32(length).bytes(value);
        }
        Tag::Bsob { name, value } => {
            let length = u8::try_from(value.len())
                .map_err(|_| EncodeError::new(format!("BSOB longer than 255 bytes: {name}")))?;
            out.u8(length).bytes(value);
        }
    }
    Ok(())
}

fn smallest_int_type(value: u64) -> u8 {
    if value & !0xFF == 0 {
        UINT8
    } else if value & !0xFFFF == 0 {
        UINT16
    } else if fits32(value) {
        UINT32
    } else {
        UINT64
    }
}

fn fits32(value: u64) -> bool {
    value & 0xFFFF_FFFF_0000_0000 == 0
}

fn unsupported_in_kad(kind: &str, name: &TagName) -> EncodeError {
    EncodeError::new(format!("Kad tags cannot hold {kind}: {name}"))
}
