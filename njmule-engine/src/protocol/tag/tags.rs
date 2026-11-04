// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Typed lookups in a tag list; the first tag with the name and the expected type wins.

use crate::protocol::tag::{Tag, TagName};

pub fn find<'a>(tags: &'a [Tag], name: &TagName) -> Option<&'a Tag> {
    tags.iter().find(|tag| tag.name() == name)
}

pub fn int_value(tags: &[Tag], id: u8) -> Option<u64> {
    int_value_named(tags, &TagName::id(id))
}

pub fn int_value_named(tags: &[Tag], name: &TagName) -> Option<u64> {
    tags.iter().find_map(|tag| match tag {
        Tag::Int { name: n, value } if n == name => Some(*value),
        _ => None,
    })
}

/// The integer tag `id` as a TCP or UDP port: none unless within 1..65535.
pub fn port_value(tags: &[Tag], id: u8) -> Option<u16> {
    int_value(tags, id).and_then(|value| u16::try_from(value).ok()).filter(|port| *port != 0)
}

pub fn string_value(tags: &[Tag], id: u8) -> Option<&str> {
    let name = TagName::id(id);
    tags.iter().find_map(|tag| match tag {
        Tag::String { name: n, value } if *n == name => Some(value.as_str()),
        _ => None,
    })
}

pub fn hash_value(tags: &[Tag], id: u8) -> Option<[u8; 16]> {
    let name = TagName::id(id);
    tags.iter().find_map(|tag| match tag {
        Tag::Hash { name: n, value } if *n == name => Some(*value),
        _ => None,
    })
}

pub fn blob_value(tags: &[Tag], id: u8) -> Option<&[u8]> {
    let name = TagName::id(id);
    tags.iter().find_map(|tag| match tag {
        Tag::Blob { name: n, value } if *n == name => Some(value.as_slice()),
        _ => None,
    })
}
