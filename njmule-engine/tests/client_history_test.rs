// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's ClientHistoryTest, under its names: the clients met, kept in `clienthistory.json`.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use njmule_engine::peer::client_history::{self, ClientHistory, Entry};

const ANN: [u8; 16] = [0xA1; 16];
const BOB: [u8; 16] = [0xB2; 16];
// 2026-10-01T10:00:00Z.
const START: u64 = 1_790_848_800;
const DAY: u64 = 86_400;

fn nicks(clients: &[Entry]) -> Vec<&str> {
    clients.iter().map(|entry| entry.nick.as_str()).collect()
}

#[test]
fn should_keep_when_a_client_was_first_met_and_what_it_said_last() {
    let dir = tempfile::tempdir().unwrap();
    let mut history = ClientHistory::load(dir.path(), START);
    history.seen(ANN, "ann", "eMule v0.70b", Some("it"), START);
    history.seen(ANN, "ann2", "eMule v0.72a", None, START + 3 * DAY);

    let clients = history.clients(START + 3 * DAY);

    assert_eq!(
        clients,
        [Entry {
            user_hash: ANN,
            nick: "ann2".to_owned(),
            software: "eMule v0.72a".to_owned(),
            first_seen: START,
            last_seen: START + 3 * DAY,
            country: Some("it".to_owned()),
        }]
    );
}

#[test]
fn should_forget_a_client_not_met_for_ninety_days() {
    let dir = tempfile::tempdir().unwrap();
    let mut history = ClientHistory::load(dir.path(), START);
    history.seen(ANN, "ann", "eMule", None, START);
    history.seen(BOB, "bob", "aMule", None, START + 10 * DAY);

    let clients = history.clients(START + client_history::KEPT.as_secs() + 1);

    assert_eq!(nicks(&clients), ["bob"]);
}

#[test]
fn should_read_back_what_it_saved() {
    let dir = tempfile::tempdir().unwrap();
    let mut history = ClientHistory::load(dir.path(), START);
    history.seen(ANN, "ann", "eMule", Some("fr"), START);
    history.seen(BOB, "bob", "aMule", None, START + 60);
    history.save();

    let clients = ClientHistory::load(dir.path(), START + 120).clients(START + 120);

    assert_eq!(nicks(&clients), ["bob", "ann"]);
}

#[test]
fn should_keep_no_address() {
    let dir = tempfile::tempdir().unwrap();
    let mut history = ClientHistory::load(dir.path(), START);
    history.seen(ANN, "ann", "eMule", Some("fr"), START);

    history.save();

    let text = std::fs::read_to_string(dir.path().join(client_history::FILE_NAME)).unwrap();
    assert!(text.contains("ann"));
    assert!(!text.contains("4662") && !text.contains("192.0.2."), "{text}");
}

// Beyond the Java engine's examples: what another engine wrote, and a damaged file.

#[test]
fn should_read_the_file_the_java_engine_writes() {
    let dir = tempfile::tempdir().unwrap();
    let written = r#"{"format":1,"clients":[{"userHash":"A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1","nick":"ann","software":"eMule","firstSeen":1790848800,"lastSeen":1790848860,"country":"it"}]}"#;
    std::fs::write(dir.path().join(client_history::FILE_NAME), written).unwrap();

    let clients = ClientHistory::load(dir.path(), START + 120).clients(START + 120);

    assert_eq!(clients.len(), 1);
    assert_eq!((clients[0].user_hash, clients[0].country.as_deref()), (ANN, Some("it")));
}

#[test]
fn should_start_anew_from_a_damaged_file() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join(client_history::FILE_NAME), "{not json").unwrap();

    let clients = ClientHistory::load(dir.path(), START).clients(START);

    assert!(clients.is_empty());
}
