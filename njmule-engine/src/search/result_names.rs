// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The names a result is found under: one file may be published under many, and the one most sources give is shown.

use crate::api::search_result::{Name, SearchResult};
use crate::files::file_types;

/// Names kept per result at most.
pub const MAX_NAMES: usize = 64;

/// `known` found again as `found`: their names merged, counted by name; servers count their own sources, so with
/// `add_counts` the counts add up, otherwise the largest is kept.
pub fn found_again(known: &SearchResult, found: &SearchResult, add_counts: bool) -> SearchResult {
    let mut merged = names(known);
    for name in names(found) {
        let same = merged.iter().position(|kept| kept.name.to_lowercase() == name.name.to_lowercase());
        if let Some(kept) = same.and_then(|index| merged.get_mut(index)) {
            kept.sources = count(kept.sources, name.sources, add_counts);
            kept.complete_sources = optional_count(kept.complete_sources, name.complete_sources, add_counts);
        } else if merged.len() < MAX_NAMES {
            merged.push(name);
        }
    }
    // A stable sort: of names as many sources give, the first found stays first.
    merged.sort_by_key(|name| std::cmp::Reverse(name.sources));
    let shown = merged.first().map_or_else(|| known.name.clone(), |first| first.name.clone());
    let file_type = file_types::of(&shown)
        .map(str::to_owned)
        .or_else(|| known.file_type.clone())
        .or_else(|| found.file_type.clone());
    SearchResult {
        hash: known.hash,
        size: known.size,
        sources: count(known.sources, found.sources, add_counts),
        complete_sources: optional_count(known.complete_sources, found.complete_sources, add_counts),
        file_type,
        spam_rating: known.spam_rating,
        aich_hash: known.aich_hash,
        names: if merged.len() > 1 { merged } else { Vec::new() },
        name: shown,
    }
}

fn names(result: &SearchResult) -> Vec<Name> {
    if result.names.is_empty() {
        vec![Name { name: result.name.clone(), sources: result.sources, complete_sources: result.complete_sources }]
    } else {
        result.names.clone()
    }
}

fn count(known: u32, found: u32, add: bool) -> u32 {
    if add { known.saturating_add(found) } else { known.max(found) }
}

// A count not told adds nothing.
fn optional_count(known: Option<u32>, found: Option<u32>, add: bool) -> Option<u32> {
    match (known, found) {
        (Some(known), Some(found)) => Some(count(known, found, add)),
        (known, found) => known.or(found),
    }
}
