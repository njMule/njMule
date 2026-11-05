// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The protocol obfuscation of TCP streams and UDP datagrams (not security: it hides the protocol from simple filters).

pub mod datagram_obfuscation;
pub mod dh768;
pub mod stream_obfuscation;
