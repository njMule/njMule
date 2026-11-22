// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's FileKindTest, under its names: the kind of a file from its extension as exposed by the API.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use njmule_engine::api::download_info::{DownloadInfo, DownloadPriority, DownloadStatus};
use njmule_engine::files::part_file::PartMap;
use njmule_engine::remote::api_json;

#[test]
fn should_tell_the_kind_of_a_file_from_its_extension() {
    let cases = [
        ("Sintel.2010.1080p.MKV", "video"),
        ("NASA_Apollo11_restored_audio.flac", "audio"),
        ("cover.png", "image"),
        ("Divina_Commedia.epub", "document"),
        ("blender-4.5.3-windows-x64.zip", "archive"),
        ("ubuntu-24.04.1-desktop-amd64.iso", "disc_image"),
        ("LibreOffice_25.8.1_Win_x86-64.msi", "program"),
        ("osm-italy-latest.osm.pbf", "other"),
        ("README", "other"),
    ];
    for (name, expected) in cases {
        let mut kind = Kind::default();

        kind.when_the_kind_of_is_asked(name);

        kind.then_the_kind_is(expected);
    }
}

#[derive(Default)]
struct Kind {
    kind: String,
}

impl Kind {
    fn when_the_kind_of_is_asked(&mut self, name: &str) {
        let info = DownloadInfo {
            hash: [1u8; 16],
            name: name.to_owned(),
            size: 100,
            completed: 0,
            status: DownloadStatus::Downloading,
            priority: DownloadPriority::Normal,
            category: 0,
            sources: 0,
            transferring: 0,
            bytes_per_second: 0,
            parts: PartMap { size: 100, missing: Vec::new(), requested: Vec::new(), sources: Vec::new() },
            preview_first: false,
            auto_swap: false,
        };
        let value = api_json::download(&info, false);
        self.kind = value["kind"].as_str().unwrap().to_owned();
    }

    fn then_the_kind_is(&self, expected: &str) {
        assert_eq!(self.kind, expected);
    }
}
