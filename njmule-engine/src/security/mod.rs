// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What keeps names and data from the network from doing harm.

pub mod connection_guard;
pub mod ip_filter;
pub mod ip_whitelist;
pub mod path_sanitizer;
pub mod secure_ident;
