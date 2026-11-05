// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! A readable name and version for a peer's client software, from its hello.

use crate::api::nj_mule_info;
use crate::protocol::ed2k::client_version::NJMULE_COMPAT;
use crate::protocol::ed2k::hello::Hello;
use crate::util::white_space::strip;
use unicode_general_category::{GeneralCategory, get_general_category};

/// The longest mod name kept: what a peer adds beyond it is not shown.
pub const MAX_MOD: usize = 64;

/// The software its version tag names, then the mod the client names, in brackets.
pub fn describe(hello: &Hello) -> String {
    let software = software(hello);
    let Some(shown_mod) = hello.mod_version().map(shown).filter(|shown_mod| !shown_mod.is_empty()) else {
        return software;
    };
    // Another njMule names itself with its full version, pre-release included.
    let ours = format!("{} ", nj_mule_info::NAME);
    if software.starts_with(&format!("{} v", nj_mule_info::NAME)) && shown_mod.starts_with(&ours) {
        return shown_mod;
    }
    format!("{software} [{shown_mod}]")
}

fn software(hello: &Hello) -> String {
    let Some(tag) = hello.emule_version() else {
        return "eDonkey compatible".to_owned();
    };
    let [compat, high, middle, low] = (tag as u32).to_be_bytes();
    let rest = u32::from_be_bytes([0, high, middle, low]);
    let major = rest >> 17 & 0x7F;
    let minor = rest >> 10 & 0x7F;
    let update = rest >> 7 & 0x7;
    match compat {
        // The update shows as a letter after the version: 0 is a, 1 is b.
        0 => format!("eMule v{major}.{minor:02}{}", char::from(b'a'.saturating_add(update as u8))),
        3 => format!("aMule v{major}.{minor}.{update}"),
        NJMULE_COMPAT => format!("njMule v{major}.{minor}.{update}"),
        _ => format!("eMule compatible (client {compat}) v{major}.{minor}.{update}"),
    }
}

// A peer's text is shown and logged: no control characters or direction overrides, and no more than fits.
fn shown(text: &str) -> String {
    let kept: String = strip(text)
        .chars()
        .filter(|c| !c.is_control() && get_general_category(*c) != GeneralCategory::Format)
        .take(MAX_MOD)
        .collect();
    strip(&kept).to_owned()
}
