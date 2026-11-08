// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java ApiReaderTest, under its names: what the API writes reads back the same.

#![allow(clippy::unwrap_used)]

use njmule_engine::api::saved_search::SavedSearch;
use njmule_engine::remote::{api_json, api_reader};

use crate::api_samples::saved_search;

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
