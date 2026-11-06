// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's KeywordsTest, under its names: the words Kad indexes a name under, in the network's lower case.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::kad::{kad_lower_case, keywords};

fn of(text: &str) -> Vec<String> {
    keywords::of(text)
}

// A unit whose lower case takes fewer bytes in UTF-8; surrogates count as the three bytes of their replacement.
fn is_shortened_by_lowering(unit: u16) -> bool {
    utf8_length(kad_lower_case::lower_unit(unit)) < utf8_length(unit)
}

fn utf8_length(unit: u16) -> usize {
    char::from_u32(u32::from(unit)).map_or(3, char::len_utf8)
}

#[test]
fn should_find_no_keyword_in_text_without_words() {
    for text in ["", keywords::DELIMITERS] {
        assert!(of(text).is_empty(), "{text:?}");
    }
}

#[test]
fn should_find_no_keyword_in_words_under_three_bytes() {
    assert!(of("ab cd").is_empty(), "words under three bytes");
}

#[test]
fn should_split_the_words_at_every_delimiter() {
    for delimiter in keywords::DELIMITERS.chars() {
        assert_eq!(of(&format!("alpha{delimiter}beta{delimiter}")), ["alpha", "beta"], "{delimiter}");
    }
}

#[test]
fn should_keep_other_signs_inside_the_words() {
    assert_eq!(of("rock&roll mp3+ost it's #one."), ["rock&roll", "mp3+ost", "it's", "#one"]);
}

#[test]
fn should_take_one_cjk_character_as_a_word_of_three_bytes() {
    assert_eq!(of("日 x"), ["日"], "one CJK character is three bytes");
}

#[test]
fn should_take_two_characters_of_three_bytes_as_a_word() {
    assert_eq!(of("éa"), ["éa"], "two characters, three bytes");
}

#[test]
fn should_not_take_a_last_word_of_two_characters_and_three_bytes_for_an_extension() {
    assert_eq!(of("abc éa"), ["abc", "éa"], "not taken for an extension");
}

#[test]
fn should_move_a_repeated_last_word_to_the_end_then_drop_it_as_the_extension() {
    assert_eq!(of("abc def abc"), ["def"], "the repeated last word moves to the end, then goes as the extension");
}

#[test]
fn should_apply_the_extension_rule_to_the_last_part_only() {
    for (text, expected) in
        [("Movie MOVIE movie", vec!["movie"]), ("film.mkv", vec!["film"]), ("film.avi.", vec!["film", "avi"])]
    {
        assert_eq!(of(text), expected, "{text}");
    }
}

#[test]
fn should_keep_a_last_part_of_four_letters() {
    assert_eq!(of("film.mpeg"), ["film", "mpeg"], "four letters stay");
}

#[test]
fn should_turn_the_words_to_the_network_lower_case() {
    for (text, expected) in
        [("STRASSE Straße", vec!["strasse", "straße"]), ("Ünïcode", vec!["ünïcode"]), ("🎵music", vec!["🎵music"])]
    {
        assert_eq!(of(text), expected, "{text}");
    }
}

#[test]
fn should_leave_the_dotted_capital_i_unchanged() {
    assert_eq!(of("İSTANBUL"), ["İstanbul"], "dotted capital I unchanged");
}

#[test]
fn should_not_compose_combining_marks() {
    assert_eq!(of("e\u{301}cole"), ["e\u{301}cole"], "combining marks are not composed");
}

#[test]
fn should_count_the_three_bytes_of_a_word_before_lowering_it() {
    for (text, keyword) in [("Ɫ", "ɫ"), ("Ɽ", "ɽ"), ("ⱥ", "Ⱥ"), ("ⱦ", "Ⱦ")] {
        assert_eq!(of(text), [keyword], "three bytes in the name, two once lowered");
    }
}

#[test]
fn should_keep_the_lower_case_table_whole_and_stable() {
    let lowered: Vec<u16> = (0..=u16::MAX).map(kad_lower_case::lower_unit).collect();

    for (unit, lower) in (0..=u16::MAX).zip(&lowered) {
        assert_eq!(kad_lower_case::lower_unit(*lower), *lower, "U+{unit:04X}");
    }
    let mapped = (0..=u16::MAX).zip(&lowered).filter(|(unit, lower)| unit != *lower).count();
    assert_eq!(mapped, 873, "every unit of the table is found");
    for c in b'A'..=b'Z' {
        assert_eq!(lowered[usize::from(c)], u16::from(c.to_ascii_lowercase()));
    }
    let shortened: String = (0..=u16::MAX)
        .filter(|unit| is_shortened_by_lowering(*unit))
        .map(|unit| char::from_u32(u32::from(unit)).unwrap())
        .collect();
    assert_eq!(shortened, "ⱢⱤⱥⱦ");
}

proptest::proptest! {
    #[test]
    fn should_take_keywords_that_are_lower_case_long_enough_and_distinct(text in "(?s).{0,60}") {
        // A word of one unit that lowering shortens can end under three bytes: see its own test.
        proptest::prop_assume!(!text.encode_utf16().any(is_shortened_by_lowering));

        let words = of(&text);

        let mut distinct = words.clone();
        distinct.sort();
        distinct.dedup();
        proptest::prop_assert_eq!(distinct.len(), words.len());
        for word in &words {
            proptest::prop_assert!(word.len() >= 3);
            proptest::prop_assert_eq!(word, &kad_lower_case::to_lower_case(word));
            proptest::prop_assert!(!word.contains(|c| keywords::DELIMITERS.contains(c)));
        }
        proptest::prop_assert_eq!(of(&(words.join(" ") + " ")), words);
    }
}
