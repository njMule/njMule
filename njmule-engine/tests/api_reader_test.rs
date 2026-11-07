// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java ApiReaderTest, under its names: what the API writes reads back the same.

#![allow(clippy::unwrap_used)]

use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::api::search_request::{Scope, SearchRequest};
use njmule_engine::api::search_result::{Media, Name, SearchResult};
use njmule_engine::remote::{api_json, api_reader};

#[test]
fn should_read_back_the_saved_search_the_api_writes() {
    let mut reader = Reader::default();

    reader.when_the_saved_search_is_read_back(&saved_search());

    reader.then_it_is(&saved_search());
}

#[derive(Default)]
struct Reader {
    read: Option<SavedSearch>,
}

impl Reader {
    fn when_the_saved_search_is_read_back(&mut self, sample: &SavedSearch) {
        let json = api_json::saved_search(sample).to_string();
        self.read = Some(api_reader::saved_search(&serde_json::from_str(&json).unwrap()).unwrap());
    }

    fn then_it_is(&self, expected: &SavedSearch) {
        assert_eq!(self.read.as_ref(), Some(expected));
    }
}

fn saved_search() -> SavedSearch {
    let mut result = SearchResult::new([0xab; 16], "talk.bin", 5000, 3, None, Some("Pro"));
    result.media = Media::new("Ann", "Talks", "Opening", 3725, 192, "mp3");
    result.spam_rating = 70;
    result.aich_hash = Some([0x5a; 20]);
    result.names = vec![
        Name { name: "talk.bin".to_owned(), sources: 2, complete_sources: Some(1) },
        Name { name: "talk (2).bin".to_owned(), sources: 1, complete_sources: None },
    ];
    SavedSearch { title: "talks".to_owned(), request: SearchRequest::new("talk", Scope::Global), results: vec![result] }
}
