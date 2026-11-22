// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the command line says of new versions, from the engine's status or from a running njMule's API.

use std::io::Write;

use crate::api::install_kind::InstallKind;
use crate::api::nj_mule_info;
use crate::api::update_status::UpdateStatus;
use crate::remote::update_help;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateReport {
    pub current: String,
    pub installation: InstallKind,
    pub checked: bool,
    pub failure: Option<String>,
    pub available: Option<String>,
    pub page: Option<String>,
    pub download: Option<String>,
}

impl UpdateReport {
    pub fn of(status: &UpdateStatus) -> Self {
        let available = status.available.as_ref();
        Self {
            current: status.current.clone(),
            installation: status.installation,
            checked: status.last_check.is_some(),
            failure: status.failure.clone(),
            available: available.map(|v| v.version.clone()),
            page: available.map(|v| v.page.clone()),
            download: available.and_then(|v| v.download.as_ref()).map(|d| d.url.clone()),
        }
    }

    /// Whether a newer version is out.
    pub fn is_available(&self) -> bool {
        self.available.is_some()
    }

    /// Prints the outcome: the new version and how to install it, that this one is the newest, or the failure.
    pub fn print<W1: Write, W2: Write>(&self, out: &mut W1, err: &mut W2) {
        if let Some(version) = &self.available {
            let _ = writeln!(out, "{} {version} is available (this is {}).", nj_mule_info::NAME, self.current);
            if let Some(page) = &self.page {
                let _ = writeln!(out, "What changed: {page}");
            }
            let _ = writeln!(
                out,
                "{}",
                update_help::how_to_install(self.installation, self.download.as_deref(), self.page.as_deref())
            );
        } else if let Some(failure) = &self.failure {
            let _ = writeln!(err, "Cannot check for a new version: {failure}");
        } else if !self.checked {
            let _ = writeln!(out, "{} {} has not looked for a new version yet.", nj_mule_info::NAME, self.current);
        } else {
            let _ = writeln!(out, "{} {} is the newest version.", nj_mule_info::NAME, self.current);
        }
    }
}
