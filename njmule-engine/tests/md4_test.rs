// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's Md4Test, under their names: the MD4 of the md4 crate, which the eD2K hashes use.

#![allow(clippy::unwrap_used)]

use md4::{Digest, Md4};
use njmule_engine::files::hash::ed2k;

#[test]
fn should_match_the_rfc_vectors() {
    // RFC 1320, appendix A.5.
    let vectors = [
        ("", "31D6CFE0D16AE931B73C59D7E0C089C0"),
        ("a", "BDE52CB31DE33E46245E05FBDBD6FB24"),
        ("abc", "A448017AAF21D8525FC10AE87AA6729D"),
        ("message digest", "D9130A8164549FE818874806E1C7014B"),
        ("abcdefghijklmnopqrstuvwxyz", "D79E1C308AA5BBCDEEA8ED63DF412DA9"),
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", "043F8582F241DB351CE627E153E7F0E4"),
        (
            "12345678901234567890123456789012345678901234567890123456789012345678901234567890",
            "E33B4DDC9C38F2199C3E7B164FCC0536",
        ),
    ];

    for (message, expected) in vectors {
        assert_eq!(hex::encode_upper(ed2k::md4(message.as_bytes())), expected, "{message}");
    }
}

proptest::proptest! {
    #[test]
    fn should_give_the_same_digest_whatever_pieces_the_data_is_fed_in(
        data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=300),
        piece in 1usize..=70,
    ) {
        let mut md4 = Md4::new();
        for chunk in data.chunks(piece) {
            md4.update(chunk);
        }

        proptest::prop_assert_eq!(<[u8; 16]>::from(md4.finalize()), ed2k::md4(&data));
    }

    #[test]
    fn should_start_again_after_a_digest(data in proptest::collection::vec(proptest::prelude::any::<u8>(), 0..=100)) {
        let mut md4 = Md4::new();
        md4.update(b"something else first");
        let _ = md4.finalize_reset();
        md4.update(&data);

        proptest::prop_assert_eq!(<[u8; 16]>::from(md4.finalize()), ed2k::md4(&data));
    }
}
