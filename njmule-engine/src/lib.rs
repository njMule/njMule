// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's engine in Rust: a port of the Java engine, package by package and with the same names, accepted only
//! where it answers the corpora of the Java engine (`njmule-core/src/test/resources/golden`) alike.

pub mod api;
pub mod config;
pub mod crypto;
pub mod download;
pub mod engine;
pub mod files;
pub mod kad;
pub mod net;
pub mod peer;
pub mod persist;
pub mod protocol;
pub mod remote;
pub mod search;
pub mod security;
pub mod server;
pub mod upload;
pub mod util;
