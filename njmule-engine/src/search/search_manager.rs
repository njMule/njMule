// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Searches on the eD2K servers: the connected server first over TCP, then, for global searches, the other known
//! servers over UDP, one every [`GLOBAL_INTERVAL`]; or on Kad. Results with the same hash are merged and their sources
//! add up. UDP answers count only from servers the search asked. The engine's task owns the manager; it carries out
//! the [`Effect`]s it hands back.

use std::collections::{HashMap, HashSet, VecDeque};
use std::net::Ipv4Addr;
use std::time::Duration;

use indexmap::IndexMap;

use crate::api::search_result::{self, Origin, SearchResult};
use crate::files::file_types;
use crate::kad::keywords;
use crate::persist::format::server_met::Server;
use crate::protocol::ed2k::file_entry::FileEntry;
use crate::protocol::ed2k::server_datagrams;
use crate::protocol::ed2k::server_messages::{self, IdChange};
use crate::protocol::packet::Packet;
use crate::protocol::tag::file_tag;
use crate::search::result_names;
use crate::search::search_expr::SearchExpr;
use crate::search::search_parser;
use crate::search::search_syntax_error::SearchSyntaxError;
use crate::search::search_tree_codec;
use crate::server::server_list::{self, Address, ServerList};
use crate::server::server_pinger::PORT_OFFSET;
use crate::util::ipv4;

/// How long to wait for the connected server before giving up on it.
pub const SERVER_WAIT: Duration = Duration::from_secs(50);
/// Pace of the UDP queries of a global search.
pub const GLOBAL_INTERVAL: Duration = Duration::from_millis(750);
/// Time left for UDP answers after the last query.
pub const ANSWER_GRACE: Duration = Duration::from_secs(5);
/// A global search asks no further server once it holds more than this many results.
pub const GLOBAL_RESULTS: usize = 100;
/// The results one search keeps at most, against a flood.
pub const MAX_RESULTS: usize = 2_000;
/// Requests for more results of one search, as servers allow.
pub const MAX_MORE_REQUESTS: u32 = 5;
/// A Kad search ends after this long.
pub const KAD_WAIT: Duration = Duration::from_secs(50);

// What separates the words of a name, as a bare name leaves out the words searched for.
const NAME_SEPARATORS: &str = ".[]()!-'_";

pub use crate::api::search_request::Scope;

/// Why a search cannot start.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StartError {
    Syntax(SearchSyntaxError),
    /// Kad cannot look the expression up: no word it indexes every match has.
    NotForKad(String),
    /// No server is connected, or Kad does not run or cannot search now.
    Unavailable(String),
}

/// Rates results for spam, and keeps how many spam results each server gives over UDP.
pub trait SpamRater {
    fn rate(&self, result: &SearchResult) -> u32;

    /// The server at `server_ip` gave `results` over UDP, `spam` of them rated as spam.
    fn answered(&mut self, server_ip: u32, results: u32, spam: u32);
}

/// No rating: nothing is spam.
pub struct NoSpam;

impl SpamRater for NoSpam {
    fn rate(&self, _: &SearchResult) -> u32 {
        0
    }

    fn answered(&mut self, _: u32, _: u32, _: u32) {}
}

/// Kad, when it runs.
pub trait KadSearches {
    /// Looks for files under `keyword`, filtered by `expression`, for the search numbered `search`, whose results come
    /// back through [`SearchManager::kad_results`] until [`Effect::StopKad`]; false when Kad cannot search for it now.
    fn search(&mut self, search: u64, keyword: &str, expression: &SearchExpr) -> bool;
}

/// A timer of a search; a timer whose token is no longer the search's was cancelled.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Timer {
    pub search: u64,
    pub kind: TimerKind,
    token: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimerKind {
    /// The connected server did not answer in time.
    ServerSilent,
    /// The next UDP query of a global search.
    Query,
    /// The answers of a global search had their time.
    End,
}

/// What the manager asks the engine to do.
#[derive(Debug, Clone)]
pub enum Effect {
    /// A query for this server over UDP, along the route the engine has for it.
    Query(Server, Packet),
    Results {
        search: u64,
        changed: Vec<SearchResult>,
    },
    Ended {
        search: u64,
        results: usize,
    },
    After(Duration, Timer),
    /// Kad stops looking for this search, and for no other.
    StopKad(u64),
}

struct Search {
    expression: SearchExpr,
    scope: Scope,
    // An archive or disc image search, which the networks answer with every program.
    kind: Option<&'static str>,
    results: IndexMap<[u8; 16], SearchResult>,
    // Files given two AICH roots, which keep none.
    aich_disputed: HashSet<[u8; 16]>,
    // The words searched for, which bare names leave out.
    words: HashSet<String>,
    udp_queue: VecDeque<Server>,
    asked_over_udp: HashSet<u32>,
    server_token: u64,
    udp_token: u64,
    udp_started: bool,
    global_started: bool,
    more_available: bool,
    more_requests: u32,
}

#[derive(Default)]
pub struct SearchManager {
    searches: HashMap<u64, Search>,
    next_id: u64,
    next_token: u64,
    // Searches the connected server has yet to answer, in the order we asked: its answers carry no search id, so a
    // late answer must not land in the search asked after it. Ended ones stay to keep the order.
    server_queue: VecDeque<u64>,
    // The last search sent to the server, the only one whose results it can continue.
    server_search: Option<u64>,
    // No UDP socket: global searches ask no other server.
    no_udp: bool,
}

impl SearchManager {
    /// A manager without a UDP socket, whose global searches end with the connected server's answer.
    pub fn without_udp(self) -> Self {
        Self { no_udp: true, ..self }
    }

    /// Whether there is a UDP socket from now on, as the network interface goes and comes back.
    pub fn set_udp(&mut self, open: bool) {
        self.no_udp = !open;
    }

    /// Starts a search on the connected server, or on Kad; for the server, the server and global searches still open
    /// end, as server answers carry no search id to tell them apart.
    pub fn start(
        &mut self,
        expression: &str,
        scope: Scope,
        id: Option<IdChange>,
        kad: Option<&mut dyn KadSearches>,
        send: impl FnOnce(Packet) -> bool,
        effects: &mut Vec<Effect>,
    ) -> Result<u64, StartError> {
        let expression = search_parser::parse(expression).map_err(StartError::Syntax)?;
        if scope == Scope::Kad {
            return self.start_kad(expression, kad, effects);
        }
        let id = id.ok_or_else(|| StartError::Unavailable("not connected to a server".to_owned()))?;
        let open: Vec<u64> =
            self.searches.iter().filter(|(_, search)| search.scope != Scope::Kad).map(|(number, _)| *number).collect();
        for search in open {
            self.end(search, effects);
        }
        let large_files = id.has(server_messages::TCP_LARGEFILES);
        let request = server_messages::search_request(&expression, large_files)
            .map_err(|e| StartError::Unavailable(format!("cannot write the search: {e}")))?;
        let number = self.next_number();
        self.searches.insert(number, Search::new(expression, scope));
        self.server_search = Some(number);
        send(request);
        self.wait_for_server(number, effects);
        Ok(number)
    }

    // The longest word every result must have is the keyword looked up, as short common words land on overloaded
    // nodes; the whole expression filters.
    fn start_kad(
        &mut self,
        expression: SearchExpr,
        kad: Option<&mut dyn KadSearches>,
        effects: &mut Vec<Effect>,
    ) -> Result<u64, StartError> {
        let kad = kad.ok_or_else(|| StartError::Unavailable("Kad is not running".to_owned()))?;
        let keyword = kad_keyword(&expression).map_err(StartError::NotForKad)?;
        let number = self.next_number();
        let asked = search_tree_codec::for_kad(&expression).unwrap_or_else(|| SearchExpr::keyword(&keyword));
        if !kad.search(number, &keyword, &asked) {
            return Err(StartError::Unavailable(format!("Kad cannot search for \"{keyword}\" now")));
        }
        let token = self.token();
        let mut search = Search::new(expression, Scope::Kad);
        search.udp_token = token;
        self.searches.insert(number, search);
        effects.push(Effect::After(KAD_WAIT, Timer { search: number, kind: TimerKind::End, token }));
        Ok(number)
    }

    /// Results Kad found for the search numbered `search`; nothing once it ended.
    pub fn kad_results(
        &mut self,
        search: u64,
        results: Vec<SearchResult>,
        spam: &mut dyn SpamRater,
        effects: &mut Vec<Effect>,
    ) {
        if self.searches.get(&search).is_none_or(|open| open.scope != Scope::Kad) {
            return;
        }
        let kad = Origin::new([], true, [], "");
        let found = results.into_iter().map(|result| SearchResult { origin: kad.clone(), ..result }).collect();
        self.merge_results(search, found, false, spam, effects);
    }

    /// Whether the server said it holds more results of this search and [`Self::more`] may ask for them now: not while
    /// an answer is awaited, past the limit or once the server has taken a newer search.
    pub fn has_more(&self, search: u64) -> bool {
        self.server_search == Some(search)
            && self.searches.get(&search).is_some_and(|s| s.more_available && s.more_requests < MAX_MORE_REQUESTS)
    }

    /// Asks the server for more results; false unless [`Self::has_more`] or when the request cannot be sent.
    pub fn more(&mut self, search: u64, send: impl FnOnce(Packet) -> bool, effects: &mut Vec<Effect>) -> bool {
        if !self.has_more(search) || !send(server_messages::query_more_results()) {
            return false;
        }
        if let Some(open) = self.searches.get_mut(&search) {
            open.more_requests = open.more_requests.saturating_add(1);
            open.more_available = false;
        }
        self.wait_for_server(search, effects);
        true
    }

    pub fn stop(&mut self, search: u64, effects: &mut Vec<Effect>) {
        self.end(search, effects);
    }

    /// Results from the connected server, for the oldest search it has yet to answer.
    pub fn server_results(
        &mut self,
        files: &[FileEntry],
        more: bool,
        servers: &ServerList,
        connected: Option<Address>,
        spam: &mut dyn SpamRater,
        effects: &mut Vec<Effect>,
    ) {
        let Some(number) = self.server_queue.pop_front() else {
            return;
        };
        let Some(search) = self.searches.get_mut(&number) else {
            tracing::debug!("Dropping the server's answer to an ended search");
            return;
        };
        // Its wait is over.
        search.server_token = search.server_token.wrapping_add(1);
        search.more_available = more;
        self.merge(number, files, None, spam, effects);
        self.after_server(number, servers, connected, effects);
    }

    /// A UDP answer from the server at `ip`; ignored unless an open global search asked it. The server is counted with
    /// the spam it gave.
    pub fn datagram_results(
        &mut self,
        ip: u32,
        files: &[FileEntry],
        spam: &mut dyn SpamRater,
        effects: &mut Vec<Effect>,
    ) {
        let asking = self.searches.iter().find(|(_, search)| search.asked_over_udp.contains(&ip)).map(|(id, _)| *id);
        let Some(number) = asking else {
            tracing::debug!("Unrequested search results from {}", Ipv4Addr::from(ip));
            return;
        };
        let rated = self.merge(number, files, Some(ip), spam, effects);
        let spam_results = rated.iter().filter(|result| result.is_spam()).count();
        spam.answered(ip, count(rated.len()), count(spam_results));
    }

    /// Ends the server and global searches when the server connection is lost; Kad searches go on.
    pub fn server_lost(&mut self, effects: &mut Vec<Effect>) {
        self.server_queue.clear();
        let open: Vec<u64> =
            self.searches.iter().filter(|(_, search)| search.scope != Scope::Kad).map(|(number, _)| *number).collect();
        for search in open {
            self.end(search, effects);
        }
    }

    /// A timer of a search went off.
    pub fn timer(&mut self, timer: Timer, servers: &ServerList, connected: Option<Address>, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.get(&timer.search) else {
            return;
        };
        let current = match timer.kind {
            TimerKind::ServerSilent => search.server_token,
            TimerKind::Query | TimerKind::End => search.udp_token,
        };
        if current != timer.token {
            return;
        }
        match timer.kind {
            // A server that does not answer in time is taken not to answer at all.
            TimerKind::ServerSilent => {
                if let Some(position) = self.server_queue.iter().position(|queued| *queued == timer.search) {
                    self.server_queue.remove(position);
                    self.after_server(timer.search, servers, connected, effects);
                }
            }
            TimerKind::Query => self.query_next(timer.search, effects),
            TimerKind::End => self.end(timer.search, effects),
        }
    }

    fn wait_for_server(&mut self, number: u64, effects: &mut Vec<Effect>) {
        self.server_queue.push_back(number);
        let token = self.token();
        if let Some(search) = self.searches.get_mut(&number) {
            search.server_token = token;
            effects.push(Effect::After(SERVER_WAIT, Timer { search: number, kind: TimerKind::ServerSilent, token }));
        }
    }

    fn after_server(
        &mut self,
        number: u64,
        servers: &ServerList,
        connected: Option<Address>,
        effects: &mut Vec<Effect>,
    ) {
        let Some(search) = self.searches.get(&number) else {
            return;
        };
        if search.scope == Scope::Global && !search.global_started {
            self.start_global(number, servers, connected, effects);
        } else if (search.scope == Scope::Server || search.udp_queue.is_empty())
            && !search.more_available
            && !search.udp_started
        {
            self.end(number, effects);
        }
    }

    fn start_global(
        &mut self,
        number: u64,
        servers: &ServerList,
        connected: Option<Address>,
        effects: &mut Vec<Effect>,
    ) {
        let token = self.token();
        let Some(search) = self.searches.get_mut(&number) else {
            return;
        };
        search.global_started = true;
        for server in servers.candidates() {
            // A server listens for UDP on its TCP port + 4, which does not exist above 65531.
            let has_udp_port = server.port.checked_add(PORT_OFFSET).is_some();
            if has_udp_port && connected.as_ref() != Some(&server_list::address_of(&server)) {
                search.udp_queue.push_back(server);
            }
        }
        if self.no_udp || search.udp_queue.is_empty() {
            self.end(number, effects);
            return;
        }
        search.udp_started = true;
        search.udp_token = token;
        self.query_next(number, effects);
    }

    fn query_next(&mut self, number: u64, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.get_mut(&number) else {
            return;
        };
        if search.udp_queue.is_empty() || search.results.len() > GLOBAL_RESULTS {
            effects.push(Effect::After(
                ANSWER_GRACE,
                Timer { search: number, kind: TimerKind::End, token: search.udp_token },
            ));
            return;
        }
        let next = Timer { search: number, kind: TimerKind::Query, token: search.udp_token };
        effects.push(Effect::After(GLOBAL_INTERVAL, next));
        let Some(server) = search.udp_queue.pop_front() else {
            return;
        };
        let flags = u32::try_from(server.udp_flags().unwrap_or(0)).unwrap_or(0);
        let request = match server_datagrams::search_request(&search.expression, flags) {
            Ok(Some(request)) => request,
            Ok(None) => return,
            Err(e) => {
                tracing::debug!("Cannot write a global search: {e}");
                return;
            }
        };
        search.asked_over_udp.insert(server.ip);
        effects.push(Effect::Query(server, request));
    }

    // Answers of the connected server, or of the server at `udp_server` over UDP; the results they made, rated.
    fn merge(
        &mut self,
        number: u64,
        files: &[FileEntry],
        udp_server: Option<u32>,
        spam: &mut dyn SpamRater,
        effects: &mut Vec<Effect>,
    ) -> Vec<SearchResult> {
        let mut found = Vec::new();
        for file in files {
            let (Some(name), Some(size)) = (file.name(), file.size()) else {
                continue;
            };
            // A HighID source the answer names; a LowID is no address.
            let source = (file.client_id >= server_messages::LOW_ID_LIMIT && file.port != 0)
                .then(|| ipv4::from_ed2k(file.client_id));
            found.push(SearchResult {
                media: file.media(),
                origin: Origin::new(udp_server, udp_server.is_none(), source, ""),
                aich_hash: file.aich_hash(),
                ..SearchResult::new(
                    file.hash,
                    name,
                    size,
                    file.sources(),
                    Some(file.complete_sources()),
                    file.file_type(),
                )
            });
        }
        self.merge_results(number, found, true, spam, effects)
    }

    // Adds results to a search, rated for spam once merged; returns them as merged. Unusable ones (no name, a size of
    // zero or beyond eD2K's limit, a known hash with another size) are left out. Servers count their own sources, so
    // their counts add up; Kad nodes report the same sources, so the largest count is kept.
    fn merge_results(
        &mut self,
        number: u64,
        found: Vec<SearchResult>,
        add_counts: bool,
        spam: &mut dyn SpamRater,
        effects: &mut Vec<Effect>,
    ) -> Vec<SearchResult> {
        let Some(search) = self.searches.get_mut(&number) else {
            return Vec::new();
        };
        let mut changed = Vec::new();
        let mut rated = Vec::new();
        for reported in found {
            let mut file = as_shown(reported);
            file.origin.bare_name = bare_name(&file.name, &search.words);
            if !search_result::is_downloadable(&file.name, file.size) {
                continue;
            }
            if search.kind.is_some_and(|kind| !file_types::is_a(kind, &file.name)) {
                continue;
            }
            let known = search.results.get(&file.hash).cloned();
            if known.is_none() && search.results.len() >= MAX_RESULTS {
                continue;
            }
            if known.as_ref().is_some_and(|known| known.size != file.size) {
                continue;
            }
            let mut merged = match &known {
                Some(known) => SearchResult {
                    media: known.media.or(&file.media),
                    origin: known.origin.and(&file.origin),
                    aich_hash: agreed_aich(&mut search.aich_disputed, known, &file),
                    ..result_names::found_again(known, &file, add_counts)
                },
                None => file,
            };
            merged.spam_rating = spam.rate(&merged);
            rated.push(merged.clone());
            if known.as_ref() != Some(&merged) {
                search.results.insert(merged.hash, merged.clone());
                changed.push(merged);
            }
        }
        if !changed.is_empty() {
            effects.push(Effect::Results { search: number, changed });
        }
        rated
    }

    fn end(&mut self, number: u64, effects: &mut Vec<Effect>) {
        let Some(search) = self.searches.remove(&number) else {
            return;
        };
        if search.scope == Scope::Kad {
            effects.push(Effect::StopKad(number));
        }
        effects.push(Effect::Ended { search: number, results: search.results.len() });
    }

    fn next_number(&mut self) -> u64 {
        self.next_id = self.next_id.saturating_add(1);
        self.next_id
    }

    fn token(&mut self) -> u64 {
        self.next_token = self.next_token.wrapping_add(1);
        self.next_token
    }
}

impl Search {
    fn new(expression: SearchExpr, scope: Scope) -> Self {
        Self {
            kind: required_kind(&expression),
            words: searched_words(&expression),
            expression,
            scope,
            results: IndexMap::new(),
            aich_disputed: HashSet::new(),
            udp_queue: VecDeque::new(),
            asked_over_udp: HashSet::new(),
            server_token: 0,
            udp_token: 0,
            udp_started: false,
            global_started: false,
            more_available: false,
            more_requests: 0,
        }
    }
}

fn count(n: usize) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}

/// `name` in lower case without the words searched for, its other words apart by single spaces.
pub fn bare_name(name: &str, searched: &HashSet<String>) -> String {
    words(&name.to_lowercase()).filter(|word| !searched.contains(*word)).collect::<Vec<_>>().join(" ")
}

fn words(text: &str) -> impl Iterator<Item = &str> {
    text.split(|c: char| c.is_whitespace() || NAME_SEPARATORS.contains(c)).filter(|word| !word.is_empty())
}

// The words of the expression's keywords, as names are split, in lower case.
fn searched_words(expression: &SearchExpr) -> HashSet<String> {
    let mut found = HashSet::new();
    collect_words(expression, &mut found);
    found
}

fn collect_words(expression: &SearchExpr, found: &mut HashSet<String>) {
    match expression {
        SearchExpr::And(left, right) | SearchExpr::Or(left, right) => {
            collect_words(left, found);
            collect_words(right, found);
        }
        SearchExpr::Not(left, _) => collect_words(left, found),
        SearchExpr::Keyword(text) => {
            found.extend(words(&text.replace('"', "").to_lowercase()).map(str::to_owned));
        }
        // Attributes and numbers are no words of a name.
        SearchExpr::StringAttr { .. } | SearchExpr::NumericAttr { .. } => {}
    }
}

// Archives and disc images every match must be: asked for as programs, the others are left out here.
fn required_kind(expression: &SearchExpr) -> Option<&'static str> {
    match expression {
        SearchExpr::And(left, right) => required_kind(left).or_else(|| required_kind(right)),
        SearchExpr::Not(left, _) => required_kind(left),
        SearchExpr::StringAttr { tag, value } if *tag == file_tag::FILETYPE => match value.as_str() {
            file_types::ARCHIVE => Some(file_types::ARCHIVE),
            file_types::DISC_IMAGE => Some(file_types::DISC_IMAGE),
            _ => None,
        },
        _ => None,
    }
}

/// The longest word, as Kad indexes words, that every match of `expression` has: joined by AND or on the left of NOT,
/// preferably outside quotes; an error saying why there is none.
pub fn kad_keyword(expression: &SearchExpr) -> Result<String, String> {
    let mut plain = Vec::new();
    let mut quoted = Vec::new();
    let alternatives = required_words(expression, &mut plain, &mut quoted);
    let words = if plain.is_empty() { quoted } else { plain };
    let mut best: Option<String> = None;
    for word in words {
        if best.as_ref().is_none_or(|best| word.chars().count() > best.chars().count()) {
            best = Some(word);
        }
    }
    best.ok_or_else(|| {
        if alternatives {
            "Kad cannot search for words joined by OR: search for each on its own".to_owned()
        } else {
            "Kad needs a word of at least three letters to search for".to_owned()
        }
    })
}

// Adds the words every match has; true when alternatives (OR) were left out.
fn required_words(expression: &SearchExpr, plain: &mut Vec<String>, quoted: &mut Vec<String>) -> bool {
    match expression {
        SearchExpr::And(left, right) => {
            let left = required_words(left, plain, quoted);
            let right = required_words(right, plain, quoted);
            left || right
        }
        SearchExpr::Not(left, _) => required_words(left, plain, quoted),
        SearchExpr::Or(..) => true,
        SearchExpr::Keyword(text) => {
            let words = keywords::of(text);
            if text.starts_with('"') {
                quoted.extend(words)
            } else {
                plain.extend(words)
            }
            false
        }
        SearchExpr::StringAttr { .. } | SearchExpr::NumericAttr { .. } => false,
    }
}

// Names are shown and printed: no terminal escapes, line breaks or direction overrides that would hide the real
// extension. The publisher's type may lie or be no type at all: the name's extension tells first, and a reported type
// counts only when it is a real one.
fn as_shown(file: SearchResult) -> SearchResult {
    let name: String = file.name.chars().filter(|c| !unshown(*c)).collect();
    let name = crate::util::white_space::strip(&name).to_owned();
    let file_type = file_types::of(&name)
        .map(str::to_owned)
        .or_else(|| file.file_type.clone().filter(|kind| file_types::is_known(kind)));
    SearchResult { name, file_type, ..file }
}

// Control characters, and the embeddings, overrides and isolates that reorder text.
fn unshown(c: char) -> bool {
    c.is_control() || ('\u{202A}'..='\u{202E}').contains(&c) || ('\u{2066}'..='\u{2069}').contains(&c)
}

// Publishers that disagree on a file's AICH root leave it none for the rest of the search: a wrong one would keep its
// download from completing.
fn agreed_aich(disputed: &mut HashSet<[u8; 16]>, known: &SearchResult, file: &SearchResult) -> Option<[u8; 20]> {
    if disputed.contains(&file.hash) {
        return None;
    }
    if let (Some(a), Some(b)) = (known.aich_hash, file.aich_hash)
        && a != b
    {
        disputed.insert(file.hash);
        return None;
    }
    known.aich_hash.or(file.aich_hash)
}
