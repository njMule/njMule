// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The search tabs left open at exit, in `StoredSearches.met`, to show again at the next start.

use std::io::{self, ErrorKind};
use std::path::Path;

use indexmap::IndexMap;
use indexmap::map::Entry;

use crate::api::saved_search::SavedSearch;
use crate::api::search_request::{Scope, SearchRequest};
use crate::api::search_result::{self, SearchResult};
use crate::persist::atomic_files;
use crate::persist::format::search_files::{self, StoredSearch};
use crate::protocol::ed2k::file_entry::FileEntry;
use crate::protocol::tag::{Tag, TagName, file_tag, media_tags, tags};
use crate::search::{result_names, search_parser};
use crate::util::white_space;

pub const FILE_NAME: &str = "StoredSearches.met";

// The search methods as the file numbers them; 0 is automatic, which ran as one of the others.
const SERVER: u8 = 1;
const GLOBAL: u8 = 2;
const KAD: u8 = 3;

/// The searches in `file`, or in its backup when it is damaged; none when there is no file. Tabs that browsed a
/// client's shared files are left out: they cannot be asked again.
pub fn load(file: &Path) -> io::Result<Vec<SavedSearch>> {
    atomic_files::cleanup_interrupted_write(file);
    let read = atomic_files::read(file, |data| {
        search_files::read_stored(data)
            .map_err(|e| io::Error::new(ErrorKind::InvalidData, format!("{}: {e}", file.display())))
    });
    match read {
        Ok(stored) => Ok(stored.iter().filter(|search| !search.client_shared_files).map(saved).collect()),
        Err(e) if e.kind() == ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e),
    }
}

pub fn save(file: &Path, searches: &[SavedSearch]) -> io::Result<()> {
    let stored: Vec<StoredSearch> = searches.iter().zip(0..).map(|(search, id)| stored(id, search)).collect();
    let data = search_files::write_stored(&stored).map_err(|e| io::Error::new(ErrorKind::InvalidInput, e))?;
    atomic_files::write(file, &data)
}

fn saved(stored: &StoredSearch) -> SavedSearch {
    let scope = match stored.search_type {
        GLOBAL => Scope::Global,
        KAD => Scope::Kad,
        _ => Scope::Server,
    };
    // A file found under several names has a record for each, which make it whole again.
    let mut results: IndexMap<[u8; 16], SearchResult> = IndexMap::new();
    for record in &stored.results {
        let entry = FileEntry {
            hash: record.file_hash,
            client_id: record.client_id,
            port: record.port,
            tags: record.tags.clone(),
        };
        let (Some(name), Some(size)) = (entry.name(), entry.size()) else {
            continue;
        };
        if !search_result::is_downloadable(name, size) {
            continue;
        }
        // Kept only when the network told it.
        let complete = tags::int_value(&record.tags, file_tag::COMPLETE_SOURCES).map(|_| entry.complete_sources());
        let found = SearchResult {
            media: entry.media(),
            aich_hash: entry.aich_hash(),
            ..SearchResult::new(entry.hash, name, size, entry.sources(), complete, entry.file_type())
        };
        match results.entry(found.hash) {
            Entry::Occupied(mut known) => {
                let merged = again(known.get(), &found, scope);
                known.insert(merged);
            }
            Entry::Vacant(slot) => {
                slot.insert(found);
            }
        }
    }
    SavedSearch {
        title: stored.title.clone(),
        request: SearchRequest::new(&with_file_type(&stored.expression, &stored.file_type), scope),
        results: results.into_values().collect(),
    }
}

// Kad nodes report the same sources: the larger count stands. Another size is another file, left out.
fn again(known: &SearchResult, again: &SearchResult, scope: Scope) -> SearchResult {
    if again.size != known.size {
        return known.clone();
    }
    let named = result_names::found_again(known, again, scope != Scope::Kad);
    let disputed = known.aich_hash.is_some() && again.aich_hash.is_some() && known.aich_hash != again.aich_hash;
    SearchResult { aich_hash: if disputed { None } else { known.aich_hash.or(again.aich_hash) }, ..named }
}

// Other clients keep the file type apart from the expression; it becomes a type condition when it is one we know.
fn with_file_type(expression: &str, file_type: &str) -> String {
    let file_type = white_space::strip(file_type);
    if file_type.is_empty() {
        return expression.to_owned();
    }
    let typed = format!("{expression} @type={file_type}");
    if search_parser::parse(&typed).is_ok() { typed } else { expression.to_owned() }
}

fn stored(id: u32, search: &SavedSearch) -> StoredSearch {
    let search_type = match search.request.scope {
        Scope::Server => SERVER,
        Scope::Global => GLOBAL,
        Scope::Kad => KAD,
    };
    let mut results = Vec::new();
    for file in &search.results {
        // A file found under several names as found under each, which make it whole when read again.
        let named = if file.names.is_empty() {
            vec![file.clone()]
        } else {
            file.names.iter().map(|name| file.named(name)).collect()
        };
        for result in named {
            results.push(search_files::SearchResult {
                file_hash: result.hash,
                client_id: 0,
                port: 0,
                tags: tags(&result),
            });
        }
    }
    // The file type stays empty (any): the expression holds the one asked for.
    StoredSearch {
        id,
        search_type,
        client_shared_files: false,
        title: search.title.clone(),
        expression: search.request.expression.clone(),
        file_type: String::new(),
        results,
    }
}

fn tags(result: &SearchResult) -> Vec<Tag> {
    let mut tags = vec![
        string(file_tag::FILENAME, &result.name),
        int(file_tag::FILESIZE, result.size),
        int(file_tag::SOURCES, u64::from(result.sources)),
    ];
    if let Some(complete) = result.complete_sources {
        tags.push(int(file_tag::COMPLETE_SOURCES, u64::from(complete)));
    }
    if let Some(file_type) = &result.file_type {
        tags.push(string(file_tag::FILETYPE, file_type));
    }
    if let Some(aich) = &result.aich_hash {
        tags.push(string(file_tag::AICH_HASH, &data_encoding::BASE32_NOPAD.encode(aich)));
    }
    tags.extend(media_tags::of(&result.media));
    tags
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}
