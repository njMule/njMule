// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's FileNamesTest, under its names: each case of a parameterized test in turn.

use njmule_engine::api::file_names;

struct Names {
    cleaned: String,
}

impl Names {
    fn when_the_name_is_cleaned_up(name: &str, words: &str) -> Self {
        Self { cleaned: file_names::clean_up(name, words) }
    }

    fn then_the_name_is(&self, expected: &str) {
        assert_eq!(self.cleaned, expected);
    }
}

#[test]
fn should_tidy_a_name() {
    for (name, words, expected) in [
        ("Big_Buck_Bunny.sponsored.[www.site.com].mkv", "sponsored", "Big Buck Bunny.mkv"),
        ("linux%20mint%2022.1.iso", "", "Linux Mint 22.1.iso"),
        ("The.Movie.2010.[1080p].x264.mkv", "", "The Movie 2010 [1080P] x264.mkv"),
        ("a+b=c_d.txt", "", "A B C D.txt"),
        ("100% pure.mp3", "", "100% Pure.mp3"),
        ("What? Why: Who*.avi", "", "What Why Who.avi"),
    ] {
        let names = Names::when_the_name_is_cleaned_up(name, words);

        names.then_the_name_is(expected);
    }
}

#[test]
fn should_keep_a_name_of_which_nothing_would_be_left() {
    for (name, words) in [("sponsored.mkv", "sponsored"), ("www.", "www.")] {
        let names = Names::when_the_name_is_cleaned_up(name, words);

        names.then_the_name_is(name);
    }
}
