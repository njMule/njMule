// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The server list and the Kad contacts a new profile starts from, shipped with njMule.

use crate::persist::format::nodes_dat::{self, Contents};
use crate::persist::format::server_met::{self, Server};

/// Set to `true`, a new profile starts with no server and no Kad contact: tests must not reach real ones.
pub const DISABLED_PROPERTY: &str = "njmule.defaultlists.disabled";

const SERVER_MET: &[u8] = include_bytes!("../../../njmule-core/src/main/resources/org/njmule/core/engine/server.met");
const NODES_DAT: &[u8] = include_bytes!("../../../njmule-core/src/main/resources/org/njmule/core/engine/nodes.dat");

/// The servers shipped with njMule for a brand-new profile.
pub fn servers() -> Vec<Server> {
    match server_met::read(SERVER_MET) {
        Ok(servers) => servers,
        Err(e) => {
            tracing::warn!("Cannot read the server list shipped with njMule: {e}");
            Vec::new()
        }
    }
}

/// The contacts shipped with njMule, marked bootstrap-only so every new user asks them one at a time and never adds
/// them to its own routing table.
pub fn nodes() -> Contents {
    match nodes_dat::read(NODES_DAT) {
        Ok(shipped) => Contents { file_version: shipped.file_version, bootstrap: true, contacts: shipped.contacts },
        Err(e) => {
            tracing::warn!("Cannot read the Kad contacts shipped with njMule: {e}");
            Contents { file_version: 2, bootstrap: true, contacts: Vec::new() }
        }
    }
}
