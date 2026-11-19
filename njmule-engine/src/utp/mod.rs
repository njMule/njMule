// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! uTP (BEP 29) between clients through their routers, its packets carried in the frames of the client UDP port. Our
//! own: the maintained Rust crates bind a UDP socket of their own (librqbit-utp) or are an unmaintained alpha (utp-rs).

pub mod utp_connection;
pub mod utp_packet;
pub mod utp_socket;
