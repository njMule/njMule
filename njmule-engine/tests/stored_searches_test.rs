// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's StoredSearchesTest, under its names: the search tabs kept in `StoredSearches.met` from one run to
//! the next.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::path::PathBuf;

use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::api::search_request::{Scope, SearchRequest};
use njmule_engine::api::search_result::{Media, Name, SearchResult};
use njmule_engine::files::hash::ed2k;
use njmule_engine::persist::format::search_files::{self, StoredSearch};
use njmule_engine::protocol::tag::{Tag, TagName, file_tag};
use njmule_engine::search::{search_parser, stored_searches};

fn hash(seed: u8) -> [u8; 16] {
    let mut bytes = [0; 16];
    bytes[0] = seed;
    bytes
}

fn film() -> SearchResult {
    SearchResult::new(hash(1), "film.mkv", 5_000_000_000, 12, Some(3), Some("Video"))
}

fn song() -> SearchResult {
    SearchResult::new(hash(2), "song.mp3", 4_000_000, 1, Some(0), None)
}

struct Scenario {
    dir: tempfile::TempDir,
    saved: Vec<SavedSearch>,
}

impl Scenario {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), saved: Vec::new() }
    }

    fn with(scope: Scope, expression: &str, result: SearchResult) -> Self {
        let mut scenario = Self::new();
        scenario.saved = vec![saved("", expression, scope, vec![result])];
        scenario
    }

    fn stored_file(&self) -> PathBuf {
        self.dir.path().join(stored_searches::FILE_NAME)
    }

    fn file_with(&self, searches: &[StoredSearch]) {
        fs::write(self.stored_file(), search_files::write_stored(searches).unwrap()).unwrap();
    }

    fn saved_and_loaded(&self) -> Vec<SavedSearch> {
        stored_searches::save(&self.stored_file(), &self.saved).unwrap();
        self.load()
    }

    fn load(&self) -> Vec<SavedSearch> {
        stored_searches::load(&self.stored_file()).unwrap()
    }

    fn read_back(&self) -> Vec<StoredSearch> {
        search_files::read_stored(&fs::read(self.stored_file()).unwrap()).unwrap()
    }
}

fn saved(title: &str, expression: &str, scope: Scope, results: Vec<SearchResult>) -> SavedSearch {
    SavedSearch { title: title.to_owned(), request: SearchRequest::new(expression, scope), results }
}

fn server_search(expression: &str) -> Vec<SavedSearch> {
    vec![saved("", expression, Scope::Server, Vec::new())]
}

fn stored(
    search_type: u8,
    client_shared_files: bool,
    expression: &str,
    file_type: &str,
    results: Vec<search_files::SearchResult>,
) -> StoredSearch {
    StoredSearch {
        id: 0,
        search_type,
        client_shared_files,
        title: String::new(),
        expression: expression.to_owned(),
        file_type: file_type.to_owned(),
        results,
    }
}

fn record(seed: u8, name: &str, size: u64) -> search_files::SearchResult {
    search_files::SearchResult {
        file_hash: hash(seed),
        client_id: 0,
        port: 0,
        tags: vec![
            Tag::String { name: TagName::id(file_tag::FILENAME), value: name.to_owned() },
            Tag::Int { name: TagName::id(file_tag::FILESIZE), value: size },
        ],
    }
}

// Seven sources name it film.mkv, five Film HD.mkv.
fn film_found_under_two_names(
    sources: u32,
    complete: Option<u32>,
    first_complete: Option<u32>,
    other_complete: Option<u32>,
) -> SearchResult {
    SearchResult {
        names: vec![
            Name { name: "film.mkv".to_owned(), sources: 7, complete_sources: first_complete },
            Name { name: "Film HD.mkv".to_owned(), sources: 5, complete_sources: other_complete },
        ],
        ..SearchResult::new(hash(10), "film.mkv", 5_000_000_000, sources, complete, Some("Video"))
    }
}

#[test]
fn should_keep_the_open_searches_with_their_results() {
    let mut scenario = Scenario::new();
    assert!(scenario.load().is_empty(), "no file yet");
    scenario.saved = vec![
        saved("", "film @type=Video", Scope::Kad, vec![film()]),
        saved("Related", &format!("related::{}", hex::encode_upper(hash(1))), Scope::Server, vec![film(), song()]),
    ];

    let loaded = scenario.saved_and_loaded();

    assert_eq!(loaded, scenario.saved);
    let methods: Vec<u8> = scenario.read_back().iter().map(|search| search.search_type).collect();
    assert_eq!(methods, [3, 1], "the numbers other clients use for the methods");
}

#[test]
fn should_leave_out_results_that_cannot_be_downloaded() {
    let scenario = Scenario::new();
    scenario.file_with(&[stored(
        1,
        false,
        "film",
        "",
        vec![
            record(1, "good.mkv", 1000),
            record(2, " ", 1000),
            record(3, "zero.bin", 0),
            record(4, "huge.bin", ed2k::MAX_FILE_SIZE + 1),
            record(5, "largest.bin", ed2k::MAX_FILE_SIZE),
        ],
    )]);

    let loaded = scenario.load();

    let names: Vec<&str> = loaded[0].results.iter().map(|result| result.name.as_str()).collect();
    assert_eq!(names, ["good.mkv", "largest.bin"]);
    for result in &loaded[0].results {
        assert_eq!(result.link().unwrap().size, result.size);
    }
}

#[test]
fn should_keep_the_file_type_of_an_imported_search_and_skip_browsed_clients() {
    let scenario = Scenario::new();
    scenario.file_with(&[
        stored(1, false, "film", "Video", Vec::new()),
        stored(2, true, "SomeUser", "", vec![record(1, "shared.mkv", 1000)]),
        stored(2, false, "setup", "Pro", Vec::new()),
        stored(2, false, "set", "EmuleCollection", Vec::new()),
        stored(2, false, "anything", "", Vec::new()),
    ]);

    let loaded = scenario.load();

    let expressions: Vec<&str> = loaded.iter().map(|search| search.request.expression.as_str()).collect();
    assert_eq!(expressions, ["film @type=Video", "setup @type=Pro", "set", "anything"]);
    assert_eq!(search_parser::parse(expressions[0]).unwrap(), search_parser::parse("film @type=video").unwrap());
}

#[test]
fn should_fall_back_to_the_backup_of_a_corrupt_file() {
    let scenario = Scenario::new();
    stored_searches::save(&scenario.stored_file(), &server_search("first")).unwrap();
    stored_searches::save(&scenario.stored_file(), &server_search("second")).unwrap();
    fs::write(scenario.stored_file(), [0x0F, 1, 5]).unwrap();

    let loaded = scenario.load();

    assert_eq!(loaded, server_search("first"));
}

#[test]
fn should_store_no_complete_count_the_network_did_not_tell_and_read_it_back_unknown() {
    let scenario =
        Scenario::with(Scope::Kad, "song", SearchResult::new(hash(8), "film.mkv", 5_000_000, 3, None, Some("Video")));

    let loaded = scenario.saved_and_loaded();

    assert_eq!(loaded, scenario.saved);
    // Other clients read a count of 0 as told: one not told stays out.
    let tags = &scenario.read_back()[0].results[0].tags;
    assert!(!tags.iter().any(|tag| *tag.name() == TagName::id(file_tag::COMPLETE_SOURCES)));
}

#[test]
fn should_keep_the_aich_root_of_the_results() {
    let aich: [u8; 20] = std::array::from_fn(|i| u8::try_from(i + 1).unwrap());
    let scenario = Scenario::with(
        Scope::Kad,
        "song",
        SearchResult {
            aich_hash: Some(aich),
            ..SearchResult::new(hash(9), "film.mkv", 5_000_000, 3, Some(1), Some("Video"))
        },
    );

    let loaded = scenario.saved_and_loaded();

    assert_eq!(loaded, scenario.saved);
}

#[test]
fn should_keep_the_media_details_of_the_results() {
    let scenario = Scenario::with(
        Scope::Kad,
        "song",
        SearchResult {
            media: Media::new("Some Band", "First Album", "Opening Song", 205, 192, "mp3"),
            ..SearchResult::new(hash(7), "song.mp3", 4_000_000, 1, Some(0), Some("Audio"))
        },
    );

    let loaded = scenario.saved_and_loaded();

    assert_eq!(loaded, scenario.saved);
}

#[test]
fn should_keep_the_names_of_a_file_found_under_several() {
    let scenario = Scenario::with(Scope::Server, "film", film_found_under_two_names(12, Some(3), Some(2), Some(1)));

    let loaded = scenario.saved_and_loaded();

    assert_eq!(loaded, scenario.saved);
}

#[test]
fn should_keep_the_names_of_a_file_kad_found_under_several() {
    let scenario = Scenario::with(Scope::Kad, "film", film_found_under_two_names(7, None, None, None));

    let loaded = scenario.saved_and_loaded();

    assert_eq!(loaded, scenario.saved);
}
