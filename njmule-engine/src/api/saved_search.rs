// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A search tab kept from one run to the next: its title, the search and the results it had.

use crate::api::search_request::SearchRequest;
use crate::api::search_result::SearchResult;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SavedSearch {
    pub title: String,
    pub request: SearchRequest,
    pub results: Vec<SearchResult>,
}
