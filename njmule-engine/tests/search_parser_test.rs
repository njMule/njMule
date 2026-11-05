// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SearchParserTest, under their names: the syntax, attributes, errors and the
//! trees servers and Kad receive. Its Kad keyword scenarios come with the search manager.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::protocol::io::{PacketReader, PacketWriter};
use njmule_engine::protocol::tag::file_tag;
use njmule_engine::search::search_expr::{Comparison, SearchExpr};
use njmule_engine::search::search_parser::parse;
use njmule_engine::search::search_syntax_error::Reason;
use njmule_engine::search::search_tree_codec::{self, for_kad, for_server};

fn kw(text: &str) -> SearchExpr {
    SearchExpr::keyword(text)
}

fn and(left: SearchExpr, right: SearchExpr) -> SearchExpr {
    SearchExpr::and(left, right)
}

fn or(left: SearchExpr, right: SearchExpr) -> SearchExpr {
    SearchExpr::or(left, right)
}

fn not(left: SearchExpr, right: SearchExpr) -> SearchExpr {
    SearchExpr::not(left, right)
}

fn numeric(tag: u8, comparison: Comparison, value: u64) -> SearchExpr {
    SearchExpr::NumericAttr { tag, comparison, value }
}

fn string_attr(tag: u8, value: &str) -> SearchExpr {
    SearchExpr::StringAttr { tag, value: value.to_owned() }
}

fn large_size() -> SearchExpr {
    numeric(file_tag::FILESIZE, Comparison::GreaterEqual, 5_000_000_000)
}

fn parsed(text: &str) -> SearchExpr {
    parse(text).unwrap_or_else(|e| panic!("{text}: {e}"))
}

fn written(tree: &SearchExpr, supports_64_bit: bool) -> Vec<u8> {
    let mut out = PacketWriter::new();
    search_tree_codec::write(&mut out, tree, supports_64_bit).unwrap();
    out.into_byte_array()
}

fn read_back(bytes: &[u8]) -> SearchExpr {
    search_tree_codec::read(&mut PacketReader::of(bytes)).unwrap()
}

#[test]
fn should_and_adjacent_words_from_the_right() {
    for (text, expected) in [("linux iso", and(kw("linux"), kw("iso"))), ("a b c", and(kw("a"), and(kw("b"), kw("c"))))]
    {
        assert_eq!(parsed(text), expected, "{text}");
    }
}

#[test]
fn should_take_a_related_search_as_one_word_for_the_server() {
    let related = format!("related::{}::{}", "ab".repeat(16), "cd".repeat(16));

    assert_eq!(parsed(&related), kw(&related));
}

#[test]
fn should_bind_not_tighter_than_or_and_or_tighter_than_and() {
    for (text, expected) in [
        ("a OR b c", and(or(kw("a"), kw("b")), kw("c"))),
        ("a AND b OR c", and(kw("a"), or(kw("b"), kw("c")))),
        ("a OR b NOT c", or(kw("a"), not(kw("b"), kw("c")))),
        ("(a OR b) AND c", and(or(kw("a"), kw("b")), kw("c"))),
    ] {
        assert_eq!(parsed(text), expected, "{text}");
    }
}

#[test]
fn should_read_a_leading_minus_as_not() {
    assert_eq!(parsed("linux -windows"), not(kw("linux"), kw("windows")));
}

#[test]
fn should_keep_a_minus_inside_a_word() {
    assert_eq!(parsed("x-ray"), kw("x-ray"));
}

#[test]
fn should_take_lower_case_operators_as_words() {
    assert_eq!(parsed("rock or roll"), and(kw("rock"), and(kw("or"), kw("roll"))));
}

#[test]
fn should_take_an_upper_case_word_starting_like_an_operator_as_a_word() {
    assert_eq!(parsed("ORANGE"), kw("ORANGE"));
}

#[test]
fn should_keep_the_quotes_and_escapes_of_quoted_phrases() {
    for (text, expected) in [
        ("\"exact phrase\" x", and(kw("\"exact phrase\""), kw("x"))),
        ("\"a\\\"b\"", kw("\"a\"b\"")),
        ("\"\" x", kw("x")),
    ] {
        assert_eq!(parsed(text), expected, "{text}");
    }
}

#[test]
fn should_take_only_ascii_hex_digits_in_an_escape() {
    assert_eq!(parsed("\"a\\x\u{663}b\\x41\""), kw("\"ax\u{663}bA\""));
}

#[test]
fn should_read_sizes_in_binary_units_and_default_to_megabytes() {
    for (text, comparison, bytes) in [
        ("@size>700m", Comparison::Greater, 734_003_200),
        ("@size>=1.5g", Comparison::GreaterEqual, 1_610_612_736),
        ("@size<100", Comparison::Less, 104_857_600),
        ("@siz<=10k", Comparison::LessEqual, 10_240),
        ("@size=5b", Comparison::Equal, 5),
        ("@size<>3m", Comparison::NotEqual, 3_145_728),
    ] {
        assert_eq!(parsed(text), numeric(file_tag::FILESIZE, comparison, bytes), "{text}");
    }
}

#[test]
fn should_read_numeric_attributes_in_decimal_units_and_lengths_as_time() {
    for (text, expected) in [
        ("@sources>10", numeric(file_tag::SOURCES, Comparison::Greater, 10)),
        ("@avail>=2k", numeric(file_tag::SOURCES, Comparison::GreaterEqual, 2000)),
        ("@complete>5", numeric(file_tag::COMPLETE_SOURCES, Comparison::Greater, 5)),
        ("@bitrate>=192", numeric(file_tag::MEDIA_BITRATE, Comparison::GreaterEqual, 192)),
        ("@rating>3", numeric(file_tag::FILERATING, Comparison::Greater, 3)),
        ("@length>1:30", numeric(file_tag::MEDIA_LENGTH, Comparison::Greater, 90)),
        ("@len>=1:00:00", numeric(file_tag::MEDIA_LENGTH, Comparison::GreaterEqual, 3600)),
        ("@length < 5m", numeric(file_tag::MEDIA_LENGTH, Comparison::Less, 300)),
    ] {
        assert_eq!(parsed(text), expected, "{text}");
    }
}

#[test]
fn should_read_string_attributes_and_file_types() {
    for (text, expected) in [
        ("@type=video", string_attr(file_tag::FILETYPE, "Video")),
        ("@typ=img", string_attr(file_tag::FILETYPE, "Image")),
        ("@type=doc", string_attr(file_tag::FILETYPE, "Doc")),
        ("@type=cd", string_attr(file_tag::FILETYPE, "Iso")),
        ("@ext=mkv", string_attr(file_tag::FILEFORMAT, "mkv")),
        ("@ext=\"mp3\"", string_attr(file_tag::FILEFORMAT, "mp3")),
        ("@artist=Queen song", and(string_attr(file_tag::MEDIA_ARTIST, "Queen"), kw("song"))),
    ] {
        assert_eq!(parsed(text), expected, "{text}");
    }
}

#[test]
fn should_turn_an_ed2k_link_into_a_hash_term() {
    assert_eq!(
        parsed("ed2k://|file|My File.iso|123|31d6cfe0d16ae931b73c59d7e0c089c0|/"),
        kw("ed2k::31D6CFE0D16AE931B73C59D7E0C089C0")
    );
}

#[test]
fn should_report_why_an_expression_is_invalid() {
    for (text, reason) in [
        ("a AND", Reason::MissingAndOperand),
        ("a OR", Reason::MissingOrOperand),
        ("a NOT", Reason::MissingNotOperand),
        ("( )", Reason::MissingExpressionInParentheses),
        ("(a", Reason::MissingClosingParenthesis),
        ("@foo=1", Reason::UnknownAttribute),
        ("@size5", Reason::UnknownAttribute),
        ("@size", Reason::InvalidAttributeValue),
        ("@type=foo", Reason::InvalidAttributeValue),
        ("@ext>mp3", Reason::InvalidAttributeValue),
        ("\"abc", Reason::UnterminatedString),
        ("=5", Reason::OperatorWithoutAttribute),
        ("a > b", Reason::OperatorWithoutAttribute),
        ("-a", Reason::General),
        ("a b c d e f g h i j k l", Reason::TooComplex),
    ] {
        assert_eq!(parse(text).map_err(|e| e.reason()), Err(reason), "{text}");
    }
}

#[test]
fn should_refuse_lengths_too_large_for_a_number() {
    for text in [
        "@length>99999999999999999999:00",
        "@length>1:99999999999999999999",
        "@length>1:00:99999999999999999999",
        "@length>3000000000000000:00:00",
        "@length>5000000000000000:00:00",
        "@length>2562047788015215:30:08",
        "@length>153722867280912931:00",
    ] {
        assert_eq!(parse(text).map_err(|e| e.reason()), Err(Reason::InvalidAttributeValue), "{text}");
    }
}

#[test]
fn should_accept_the_longest_length_a_number_holds() {
    assert_eq!(
        parsed("@length>2562047788015215:30:07"),
        numeric(file_tag::MEDIA_LENGTH, Comparison::Greater, i64::MAX.unsigned_abs())
    );
}

#[test]
fn should_accept_a_length_of_zero() {
    assert_eq!(parsed("@length>0:0:0"), numeric(file_tag::MEDIA_LENGTH, Comparison::Greater, 0));
}

#[test]
fn should_refuse_numbers_too_large_or_malformed() {
    for text in ["@size>1e999", "@sources>1e30", "@size>9999999999999999g", "@bitrate>."] {
        assert!(parse(text).is_err(), "{text}");
    }
}

#[test]
fn should_read_a_fraction_without_a_leading_zero() {
    assert_eq!(parsed("@size>.5k"), numeric(file_tag::FILESIZE, Comparison::Greater, 512));
}

#[test]
fn should_parse_fragments_of_the_syntax_or_fail_with_a_syntax_error() {
    let fragments = [
        "(",
        ")",
        "\"",
        " ",
        "a",
        "b1",
        "@size>",
        "@length>",
        "@length=",
        "@type=",
        "@ext=",
        "AND",
        "OR",
        "NOT",
        "-",
        ":",
        "99999999999999999999",
        "123",
        "1.5",
        "e9",
        "k",
        "m",
        "g",
        "=",
        "<",
        ">",
        "video",
        "\\",
    ];
    // Every pair and triple of fragments: a search, whose tree a 64-bit server reads back, or a syntax error.
    for first in fragments {
        for second in fragments {
            for third in ["", "a", "@size>", "\"", "1.5", "NOT"] {
                let text = format!("{first}{second}{third}");
                if let Ok(search) = parse(&text) {
                    assert_eq!(read_back(&written(&search, true)), search, "{text}");
                }
            }
        }
    }
}

#[test]
fn should_prefix_encode_the_trees_for_servers() {
    let tree = and(kw("a"), numeric(file_tag::FILESIZE, Comparison::Greater, 5));

    assert_eq!(hex::encode(written(&tree, false)), "000001010061030500000001010002");
}

#[test]
fn should_keep_a_large_value_for64_bit_servers() {
    assert_eq!(read_back(&written(&large_size(), true)), large_size());
}

#[test]
fn should_cap_a_large_value_for32_bit_servers() {
    assert_eq!(
        read_back(&written(&large_size(), false)),
        numeric(file_tag::FILESIZE, Comparison::GreaterEqual, 0xFFFF_FFFF)
    );
}

#[test]
fn should_send_anded_keywords_as_one_phrase() {
    assert_eq!(for_server(&parsed("ubuntu 24.04 iso")), kw("ubuntu 24.04 iso"));
}

#[test]
fn should_ask_kad_without_the_complete_sources_it_does_not_count() {
    assert_eq!(
        for_kad(&parsed("linux @complete>=3 @size>1g")),
        Some(and(kw("linux"), numeric(file_tag::FILESIZE, Comparison::Greater, 1 << 30)))
    );
}

#[test]
fn should_ask_kad_for_the_keyword_alone_when_the_complete_sources_were_all_else() {
    assert_eq!(for_kad(&parsed("linux OR @complete>=3")), None);
}

#[test]
fn should_take_the_complete_sources_as_met_wherever_kad_is_asked_for_them() {
    let complete = numeric(file_tag::COMPLETE_SOURCES, Comparison::GreaterEqual, 3);
    for (asked, expected) in [
        (not(kw("linux"), complete.clone()), Some(kw("linux"))),
        (not(complete.clone(), kw("beta")), None),
        (not(kw("linux"), kw("beta")), Some(not(kw("linux"), kw("beta")))),
        (or(kw("linux"), kw("bsd")), Some(or(kw("linux"), kw("bsd")))),
        (and(complete.clone(), complete.clone()), None),
        (and(complete, kw("linux")), Some(kw("linux"))),
    ] {
        assert_eq!(for_kad(&asked), expected, "{asked:?}");
    }
}

#[test]
fn should_ask_kad_for_the_indexed_type_as_servers_are() {
    assert_eq!(for_kad(&parsed("linux @type=arc")), Some(and(kw("linux"), string_attr(file_tag::FILETYPE, "Pro"))));
}

#[test]
fn should_send_other_searches_as_they_are() {
    let search = parsed("a OR b");

    assert_eq!(for_server(&search), search);
}

#[test]
fn should_ask_servers_for_archives_as_programs() {
    let sent = read_back(&written(&for_server(&parsed("linux @type=arc")), false));

    assert_eq!(sent, and(kw("linux"), string_attr(file_tag::FILETYPE, "Pro")));
}

#[test]
fn should_ask_servers_for_disc_images_as_programs() {
    let sent = read_back(&written(&for_server(&parsed("@type=iso linux")), false));

    assert_eq!(sent, and(string_attr(file_tag::FILETYPE, "Pro"), kw("linux")));
}

#[test]
fn should_read_back_every_tree_it_writes() {
    for tree in [
        kw("a"),
        string_attr(7, "città ÿ"),
        numeric(0, Comparison::NotEqual, i64::MAX.unsigned_abs()),
        and(or(kw("x"), not(kw("y"), string_attr(255, ""))), numeric(2, Comparison::LessEqual, 1 << 40)),
    ] {
        let bytes = written(&tree, true);
        let mut input = PacketReader::of(&bytes);

        assert_eq!(search_tree_codec::read(&mut input), Ok(tree.clone()), "{tree:?}");
        assert!(input.expect_end().is_ok(), "{tree:?}");
    }
}

#[test]
fn should_decode_any_bytes_or_fail_cleanly() {
    // A value above a signed long, an unknown comparison, a tag id of two bytes, an empty keyword.
    for hex in ["08ffffffffffffffff00010002", "03010000000601000200", "0301000000010200020300", "010000"] {
        assert!(search_tree_codec::read(&mut PacketReader::of(&hex::decode(hex).unwrap())).is_err(), "{hex}");
    }
}
