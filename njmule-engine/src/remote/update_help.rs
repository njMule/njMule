// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! How a new version takes the place of this one, by the way njMule was installed; for the web and the console.

use std::path::Path;

use crate::api::install_kind::InstallKind;
use crate::api::nj_mule_info;

/// One sentence telling how to install a new version.
pub fn how_to_install(installation: InstallKind, download: Option<&str>, page: Option<&str>) -> String {
    let file = download.map(file_name).unwrap_or("");
    match installation {
        InstallKind::Deb => {
            format!("Download {} and install it: sudo apt install ./{file}", download.unwrap_or("the .deb package"))
        }
        InstallKind::Rpm => {
            format!("Download {} and install it: sudo dnf install ./{file}", download.unwrap_or("the .rpm package"))
        }
        InstallKind::Synology => format!(
            "Install it in the Package Center with Manual Install: {}",
            download.unwrap_or("the .spk package of the release")
        ),
        InstallKind::Docker => {
            "Pull the new image and start it again: docker compose pull && docker compose up -d".to_owned()
        }
        _ => format!("Download and install {}", download.or(page).unwrap_or(nj_mule_info::HOMEPAGE)),
    }
}

/// One sentence telling how to install the package of a new version downloaded to `file`.
pub fn how_to_install_file(installation: InstallKind, file: &Path) -> String {
    let display = file.display();
    let quoted = format!("\"{display}\"");
    match installation {
        InstallKind::Deb => format!("Install it: sudo apt install {quoted}"),
        InstallKind::Rpm => format!("Install it: sudo dnf install {quoted}"),
        InstallKind::Synology => format!("Install it in the Package Center with Manual Install: {display}"),
        InstallKind::Dmg => {
            format!("Open it and drag {} to Applications, replacing this one: {display}", nj_mule_info::NAME)
        }
        _ => format!("Install it as this copy was installed: {display}"),
    }
}

fn file_name(uri: &str) -> &str {
    let without_query = uri.split(['?', '#']).next().unwrap_or(uri);
    without_query.rsplit('/').next().unwrap_or("")
}
