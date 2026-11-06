// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's LevenshteinTest, under its names: the edit distance of two names.

use njmule_engine::search::levenshtein;

#[test]
fn should_count_the_fewest_edits_from_one_name_to_the_other() {
    for (a, b, expected) in [
        ("kitten", "sitting", 3),
        ("flaw", "lawn", 2),
        ("", "abc", 3),
        ("abc", "", 3),
        ("same", "same", 0),
        ("great movie 2026", "great movie 2025", 1),
        // One character beyond the basic plane is two UTF-16 units, as the Java engine counts them.
        ("a\u{1F600}", "a", 2),
    ] {
        assert_eq!(levenshtein::distance(a, b), expected, "{a} to {b}");
    }
}
