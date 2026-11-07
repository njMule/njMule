// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Kad: the network's 128-bit ids, the keywords it indexes names under, and the node's routing table with what keeps
//! it honest.

pub mod kad_contact;
pub mod kad_identity;
pub mod kad_index;
pub mod kad_lower_case;
pub mod kad_notes;
pub mod keywords;
pub mod lookup;
pub mod packet_tracking;
pub mod proven_ids;
pub mod response_times;
pub mod routing_table;
pub mod uint128;
