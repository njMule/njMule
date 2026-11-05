// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! njMule's engine in Rust: a port of the Java engine, package by package and with the same names, accepted only
//! where it answers the corpora of the Java engine (`njmule-core/src/test/resources/golden`) alike.

pub mod api;
pub mod files;
pub mod kad;
pub mod protocol;
pub mod search;
pub mod util;
