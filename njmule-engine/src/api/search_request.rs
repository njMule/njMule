// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A search: an expression in the eMule syntax (`ubuntu iso @size>700m`) and how far it goes.

use crate::api::server_info::ServerInfo;
use crate::search::search_parser;
use crate::search::search_syntax_error::SearchSyntaxError;

/// The longest expression a search takes, in UTF-16 units.
pub const MAX_EXPRESSION_LENGTH: usize = search_parser::MAX_LENGTH;

// A big server is worth more than Kad, but only in a short server list: a long one may be full of fake servers.
const BIG_SERVER_MIN_USERS: u64 = 40_000;
const BIG_SERVER_MAX_USERS: u64 = 2_000_000;
const BIG_SERVER_MIN_FILES: u64 = 5_000_000;
const SHORT_SERVER_LIST: usize = 40;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchRequest {
    pub expression: String,
    pub scope: Scope,
}

impl SearchRequest {
    pub fn new(expression: &str, scope: Scope) -> Self {
        Self { expression: expression.to_owned(), scope }
    }
}

/// Where a search looks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scope {
    /// The connected server only.
    Server,
    /// The connected server, then the other known servers over UDP.
    Global,
    /// The Kad network, without servers: the longest word every result must have is looked up, and what comes back is
    /// matched against the whole expression.
    Kad,
}

impl Scope {
    pub fn parse(text: &str) -> Option<Self> {
        match text.trim().to_lowercase().as_str() {
            "server" => Some(Self::Server),
            "global" => Some(Self::Global),
            "kad" => Some(Self::Kad),
            _ => None,
        }
    }
}

/// Checks the syntax of `expression`; the error says what is wrong.
pub fn check_expression(expression: &str) -> Result<(), SearchSyntaxError> {
    search_parser::parse(expression).map(|_| ())
}

/// Where an automatic search goes: the network connected, and Kad when both are, unless the server is a static one or a
/// big one in a short list of `known_servers`; none with neither.
pub fn automatic(
    server_connected: bool,
    kad_connected: bool,
    server: Option<&ServerInfo>,
    known_servers: usize,
) -> Option<Scope> {
    if !server_connected {
        return kad_connected.then_some(Scope::Kad);
    }
    let stays = server.is_some_and(|server| server.is_static || is_big(server, known_servers));
    Some(if !kad_connected || stays { Scope::Server } else { Scope::Kad })
}

fn is_big(server: &ServerInfo, known_servers: usize) -> bool {
    server.users > BIG_SERVER_MIN_USERS
        && server.users < BIG_SERVER_MAX_USERS
        && server.files > BIG_SERVER_MIN_FILES
        && known_servers < SHORT_SERVER_LIST
}
