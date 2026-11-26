// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Application identity, version and the legal notices the GPL asks interactive programs to show.

pub const NAME: &str = "njMule";
pub const TAGLINE: &str = "New Jack Mule";
pub const APP_ID: &str = "org.njmule.njMule";
pub const HOMEPAGE: &str = "https://github.com/njMule/njMule";
pub const LICENSE_ID: &str = "GPL-3.0-or-later";
pub const COPYRIGHT: &str = "Copyright (C) 2026 Salvatore Coppola and the njMule contributors";

const LICENSE: &str = include_str!("../../../LICENSE");
const NOTICE: &str = include_str!("../../../NOTICE");
const WARRANTY_START: &str = "15. Disclaimer of Warranty.";
const WARRANTY_END: &str = "17. Interpretation of Sections 15 and 16.";
const WARRANTY_FALLBACK: &str = "THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY APPLICABLE LAW. \
                                 See sections 15 and 16 of the GNU General Public License.";

/// The semantic version, from `VERSION`.
pub fn version() -> &'static str {
    env!("NJMULE_VERSION")
}

/// The start-up notice recommended by the GPL ("How to Apply These Terms").
pub fn startup_notice(warranty_hint: &str, license_hint: &str) -> String {
    format!(
        "{NAME} {} - {TAGLINE}\n\
         {COPYRIGHT}\n\
         This program comes with ABSOLUTELY NO WARRANTY; for details use '{warranty_hint}'.\n\
         This is free software, and you are welcome to redistribute it under certain conditions; \
         use '{license_hint}' for details.",
        version()
    )
}

/// Full GPL v3 text bundled with njMule.
pub fn license_text() -> &'static str {
    LICENSE
}

/// The NOTICE file: copyright, attribution and third-party components.
pub fn notice_text() -> &'static str {
    NOTICE
}

/// GPL sections 15 (Disclaimer of Warranty) and 16 (Limitation of Liability).
pub fn warranty_text() -> &'static str {
    let Some(start) = LICENSE.find(WARRANTY_START) else {
        return WARRANTY_FALLBACK;
    };
    let Some(end) = LICENSE.find(WARRANTY_END) else {
        return WARRANTY_FALLBACK;
    };
    LICENSE.get(start..end).map_or(WARRANTY_FALLBACK, str::trim)
}
