// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The search tree sent to servers, in prefix order: operators `<0><0 AND|1 OR|2 NOT>`, keywords `<1><string>`,
//! string attributes `<2><string><u16 1><tag>` and numeric attributes `<3><u32>` (or `<8><u64>` for servers with
//! 64-bit support) followed by `<comparison><u16 1><tag>`.

use crate::files::file_types;
use crate::protocol::io::{DecodeError, EncodeError, PacketReader, PacketWriter, StringEncoding};
use crate::protocol::tag::file_tag;
use crate::search::search_expr::{Comparison, SearchExpr};

const OPERATOR: u8 = 0;
const KEYWORD: u8 = 1;
const STRING: u8 = 2;
const UINT32: u8 = 3;
const UINT64: u8 = 8;

const MAX_NODES: usize = 256;
const MAX_DEPTH: usize = 64;
// Values the Java engine holds in a signed long: a larger one is out of range.
const MAX_VALUE: u64 = i64::MAX.unsigned_abs();

/// The tree to send: an expression made only of ANDed keywords becomes one keyword with the words joined by spaces,
/// which servers handle more efficiently; archives and disc images are asked for as [`indexed_types`] programs.
pub fn for_server(expr: &SearchExpr) -> SearchExpr {
    let mut words = Vec::new();
    if only_anded_keywords(expr, &mut words) && words.len() > 1 {
        SearchExpr::Keyword(words.join(" "))
    } else {
        indexed_types(expr)
    }
}

/// `expr` with archives and disc images asked for as programs, the type servers and Kad nodes index them under.
pub fn indexed_types(expr: &SearchExpr) -> SearchExpr {
    match expr {
        SearchExpr::And(left, right) => SearchExpr::and(indexed_types(left), indexed_types(right)),
        SearchExpr::Or(left, right) => SearchExpr::or(indexed_types(left), indexed_types(right)),
        SearchExpr::Not(left, right) => SearchExpr::not(indexed_types(left), indexed_types(right)),
        SearchExpr::StringAttr { tag, value }
            if *tag == file_tag::FILETYPE && (value == file_types::ARCHIVE || value == file_types::DISC_IMAGE) =>
        {
            SearchExpr::StringAttr { tag: *tag, value: file_types::PROGRAM.to_owned() }
        }
        other => other.clone(),
    }
}

/// The tree to send to Kad: `expr` with its [`indexed_types`], and the conditions on complete sources taken as met,
/// since Kad counts none and would match no file against them; none when nothing is left to ask.
pub fn for_kad(expr: &SearchExpr) -> Option<SearchExpr> {
    without_complete_sources(expr).map(|tree| indexed_types(&tree))
}

fn without_complete_sources(expr: &SearchExpr) -> Option<SearchExpr> {
    match expr {
        SearchExpr::And(left, right) => match (without_complete_sources(left), without_complete_sources(right)) {
            (Some(left), Some(right)) => Some(SearchExpr::and(left, right)),
            (left, right) => left.or(right),
        },
        SearchExpr::Or(left, right) => {
            Some(SearchExpr::or(without_complete_sources(left)?, without_complete_sources(right)?))
        }
        // "a NOT b" without b is a; without a there is nothing to match the rest against.
        SearchExpr::Not(left, right) => match (without_complete_sources(left), without_complete_sources(right)) {
            (Some(left), Some(right)) => Some(SearchExpr::not(left, right)),
            (left, _) => left,
        },
        SearchExpr::NumericAttr { tag, .. } if *tag == file_tag::COMPLETE_SOURCES => None,
        other => Some(other.clone()),
    }
}

pub fn write(out: &mut PacketWriter, expr: &SearchExpr, supports_64_bit: bool) -> Result<(), EncodeError> {
    match expr {
        SearchExpr::And(left, right) => write_operator(out, 0, left, right, supports_64_bit),
        SearchExpr::Or(left, right) => write_operator(out, 1, left, right, supports_64_bit),
        SearchExpr::Not(left, right) => write_operator(out, 2, left, right, supports_64_bit),
        SearchExpr::Keyword(text) => {
            out.u8(KEYWORD).string16(text, StringEncoding::Utf8)?;
            Ok(())
        }
        SearchExpr::StringAttr { tag, value } => {
            out.u8(STRING).string16(value, StringEncoding::Utf8)?.u16(1).u8(*tag);
            Ok(())
        }
        SearchExpr::NumericAttr { tag, comparison, value } => {
            match u32::try_from(*value) {
                Err(_) if supports_64_bit => out.u8(UINT64).u64(*value),
                narrow => out.u8(UINT32).u32(narrow.unwrap_or(u32::MAX)),
            };
            out.u8(comparison.code()).u16(1).u8(*tag);
            Ok(())
        }
    }
}

fn write_operator(
    out: &mut PacketWriter,
    operator: u8,
    left: &SearchExpr,
    right: &SearchExpr,
    supports_64_bit: bool,
) -> Result<(), EncodeError> {
    out.u8(OPERATOR).u8(operator);
    write(out, left, supports_64_bit)?;
    write(out, right, supports_64_bit)
}

/// Reads a tree as written by [`write`]; bounded in depth and size.
pub fn read(input: &mut PacketReader<'_>) -> Result<SearchExpr, DecodeError> {
    let mut nodes = 0;
    read_node(input, 0, &mut nodes)
}

fn read_node(input: &mut PacketReader<'_>, depth: usize, nodes: &mut usize) -> Result<SearchExpr, DecodeError> {
    *nodes = nodes.saturating_add(1);
    if depth > MAX_DEPTH || *nodes > MAX_NODES {
        return Err(DecodeError::new("search tree too large"));
    }
    let deeper = depth.saturating_add(1);
    match input.u8()? {
        OPERATOR => {
            let operator = input.u8()?;
            let left = read_node(input, deeper, nodes)?;
            let right = read_node(input, deeper, nodes)?;
            match operator {
                0 => Ok(SearchExpr::and(left, right)),
                1 => Ok(SearchExpr::or(left, right)),
                2 => Ok(SearchExpr::not(left, right)),
                other => Err(DecodeError::new(format!("unknown search operator {other}"))),
            }
        }
        KEYWORD => {
            let text = input.string16()?;
            if text.is_empty() {
                return Err(DecodeError::new("empty search keyword"));
            }
            Ok(SearchExpr::Keyword(text))
        }
        STRING => {
            let value = input.string16()?;
            Ok(SearchExpr::StringAttr { tag: tag(input)?, value })
        }
        kind @ (UINT32 | UINT64) => {
            let value = if kind == UINT32 { u64::from(input.u32()?) } else { input.u64()? };
            if value > MAX_VALUE {
                return Err(DecodeError::new("search value out of range"));
            }
            let code = input.u8()?;
            let comparison =
                Comparison::of(code).ok_or_else(|| DecodeError::new(format!("unknown comparison {code}")))?;
            Ok(SearchExpr::NumericAttr { tag: tag(input)?, comparison, value })
        }
        other => Err(DecodeError::new(format!("unknown search tree node {other}"))),
    }
}

fn tag(input: &mut PacketReader<'_>) -> Result<u8, DecodeError> {
    if input.u16()? != 1 {
        return Err(DecodeError::new("search attributes use one-byte tag ids"));
    }
    input.u8()
}

fn only_anded_keywords(expr: &SearchExpr, words: &mut Vec<String>) -> bool {
    match expr {
        SearchExpr::And(left, right) => only_anded_keywords(left, words) && only_anded_keywords(right, words),
        SearchExpr::Keyword(text) => {
            words.push(text.clone());
            true
        }
        _ => false,
    }
}
