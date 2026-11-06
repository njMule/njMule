// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Connections to servers and clients, along the routes of the preferences.

pub mod country_table;
pub mod http_route;
pub mod public_ipv6;
pub mod relay_keeper;
pub mod routes;
pub mod socks5;
pub mod socks5_udp;
pub mod tcp_connection;
pub mod tcp_connector;
pub mod traffic;
pub mod udp_route;
pub mod web_lists;
