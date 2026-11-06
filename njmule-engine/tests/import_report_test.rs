// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The phrases of an import's report: the keys the interfaces translate and the English they read without them.

use njmule_engine::api::import_report::{Arg, Item, Key, Outcome, Phrase};
use strum::VariantArray;

#[test]
fn should_fill_every_phrase_with_its_arguments() {
    for key in Key::VARIANTS {
        let args: Vec<Arg> = key.template().matches('%').map(|_| Arg::from(7_usize)).collect();

        let english = Phrase::of(*key, args).english();

        assert!(!english.contains('%'), "{key:?}: {english}");
        assert!(key.translation_key().starts_with("nj.import.d."), "{key:?}");
    }
}

#[test]
fn should_name_the_phrases_as_the_translations_do() {
    assert_eq!(Key::KadContacts.translation_key(), "nj.import.d.kadContacts");
    assert_eq!(Outcome::WouldImport.name(), "WOULD_IMPORT");
}

#[test]
fn should_write_texts_and_numbers_in_their_places() {
    let left_out = Phrase::of(Key::LeftOut, vec!["known2_64.met".into(), String::from("broken").into()]);
    let download = Phrase::of(Key::Download, vec!["film.avi".into(), Arg::from(-3_i64)]);
    let lacking = Phrase::of(Key::NotShared, vec![Arg::from(u64::MAX)]);

    assert_eq!(left_out.english(), "known2_64.met left out: broken");
    assert_eq!(download.english(), "film.avi, -3 MiB");
    assert_eq!(lacking.english(), format!("{} not shared: %", i64::MAX));
}

#[test]
fn should_tell_an_item_in_its_phrases_or_its_own_text() {
    let told = Item::new(
        "server.met",
        Outcome::Imported,
        vec![Phrase::of(Key::Servers, vec![Arg::from(2_usize)]), Phrase::of(Key::Added, vec![Arg::from(1_usize)])],
    );

    assert_eq!(told.detail(), "2 servers; 1 new");
    assert!(Item::text("nodes.dat", Outcome::Missing, "").phrases.is_empty());
    assert_eq!(Item::text("nodes.dat", Outcome::Failed, "50% broken").detail(), "50% broken");
}
