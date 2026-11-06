// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's EmuleBindAddressImportTest, under its names.

#![allow(clippy::unwrap_used)]

use njmule_engine::config::preferences::Preferences;
use njmule_engine::persist::emule_importer;
use njmule_engine::persist::format::ini_file::IniFile;

#[test]
fn should_take_the_address_emule_binds_to() {
    for (bind_addr, expected) in
        [("10.8.0.2", "10.8.0.2"), (" 10.8.0.2 ", "10.8.0.2"), ("0.0.0.0", ""), ("", ""), ("vpn", ""), ("10.8.0", "")]
    {
        let ini = IniFile::parse(format!("[eMule]\nBindAddr={bind_addr}\n").as_bytes());

        let imported = emule_importer::map_preferences(&ini, &Preferences::default()).unwrap();

        assert_eq!(imported.bind_interface, expected, "BindAddr={bind_addr} binds to [{expected}]");
    }
}
