// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the front ends send back as the API wrote it: the searches left open, with their results, for the next start.

use serde_json::Value;

use crate::api::saved_search::SavedSearch;
use crate::api::search_request::{Scope, SearchRequest};
use crate::api::search_result::{Media, Name, Origin, SearchResult};

/// A result of a search as the API writes it; where it came from is not told.
pub fn result(n: &Value) -> Result<SearchResult, String> {
    let media = n.get("media").filter(|m| m.is_object()).map_or_else(Media::default, |m| Media {
        artist: text(m, "artist"),
        album: text(m, "album"),
        title: text(m, "title"),
        length: count(m, "length"),
        bitrate: count(m, "bitrate"),
        codec: text(m, "codec"),
    });
    let hash = text(n, "hash");
    let aich_hash = match n.get("aich").and_then(Value::as_str) {
        Some(aich) => Some(
            data_encoding::BASE32_NOPAD
                .decode(aich.as_bytes())
                .ok()
                .and_then(|bytes| <[u8; 20]>::try_from(bytes).ok())
                .ok_or_else(|| format!("Not an AICH hash: {aich}"))?,
        ),
        None => None,
    };
    let names = n
        .get("names")
        .and_then(Value::as_array)
        .map(|names| {
            names
                .iter()
                .map(|each| Name {
                    name: text(each, "name"),
                    sources: count(each, "sources"),
                    complete_sources: complete_sources(each),
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(SearchResult {
        hash: data_encoding::HEXUPPER_PERMISSIVE
            .decode(hash.as_bytes())
            .ok()
            .and_then(|bytes| <[u8; 16]>::try_from(bytes).ok())
            .ok_or_else(|| format!("Not a file hash: {hash}"))?,
        name: text(n, "name"),
        size: n.get("size").and_then(Value::as_u64).unwrap_or(0),
        sources: count(n, "sources"),
        complete_sources: complete_sources(n),
        file_type: n.get("type").and_then(Value::as_str).map(str::to_owned),
        media,
        origin: Origin::default(),
        spam_rating: count(n, "spamRating"),
        aich_hash,
        names,
    })
}

/// A search left open, with its results, as the API writes it.
pub fn saved_search(n: &Value) -> Result<SavedSearch, String> {
    let asked = text(n, "scope");
    let scope = Scope::parse(&asked).ok_or_else(|| format!("Not a search scope: {asked}"))?;
    let results = match n.get("results").and_then(Value::as_array) {
        Some(results) => results.iter().map(result).collect::<Result<Vec<_>, _>>()?,
        None => Vec::new(),
    };
    Ok(SavedSearch { title: text(n, "title"), request: SearchRequest::new(&text(n, "query"), scope), results })
}

fn text(n: &Value, name: &str) -> String {
    n.get(name).and_then(Value::as_str).unwrap_or_default().to_owned()
}

fn count(n: &Value, name: &str) -> u32 {
    n.get(name).and_then(Value::as_u64).and_then(|count| u32::try_from(count).ok()).unwrap_or(0)
}

// Kad does not tell how many sources are complete.
fn complete_sources(n: &Value) -> Option<u32> {
    n.get("completeSources").and_then(Value::as_u64).and_then(|count| u32::try_from(count).ok())
}
