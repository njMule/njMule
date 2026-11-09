// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! QUIC connections between clients through their routers, whose datagrams travel in frames on the client UDP port.

pub mod frame_socket;
pub mod quic_link;
pub mod quic_transport;
pub mod tls_identity;
