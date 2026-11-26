// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's engine: eD2K and Kad protocol, storage, transfers, remote API and CLI.

pub mod api;
pub mod app;
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
pub mod quic;
pub mod remote;
pub mod search;
pub mod security;
pub mod server;
pub mod social;
pub mod update;
pub mod upload;
pub mod upnp;
pub mod util;
pub mod utp;
