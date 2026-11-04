// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The white space texts from the network are trimmed of: ASCII controls and Unicode spaces, but not the no-break
//! ones (U+00A0, U+2007, U+202F) nor U+0085, which Rust's `trim` takes too. The Java engine trims the same set, so
//! both engines keep the same names, lines and comments.

/// Whether `c` is trimmed.
pub fn is_white_space(c: char) -> bool {
    matches!(c, '\t'..='\r' | '\u{1C}'..='\u{20}' | '\u{1680}' | '\u{2028}' | '\u{2029}' | '\u{205F}' | '\u{3000}')
        || matches!(c, '\u{2000}'..='\u{200A}') && c != '\u{2007}'
}

/// `text` without the white space at its ends.
pub fn strip(text: &str) -> &str {
    text.trim_matches(is_white_space)
}
