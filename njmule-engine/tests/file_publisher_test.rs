// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's FilePublisherTest, under their names: the files offered to a server, batch after
//! batch, with the clock in the test's hands.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::time::Instant;

use njmule_engine::persist::format::server_met::Server;
use njmule_engine::protocol::ed2k::file_entry::FileEntry;
use njmule_engine::protocol::ed2k::server_messages::{self, IdChange};
use njmule_engine::protocol::io::PacketReader;
use njmule_engine::protocol::packet::{Packet, packing};
use njmule_engine::protocol::tag::{file_tag, server_tag, tags};
use njmule_engine::server::file_publisher::{BATCH, FilePublisher, INTERVAL, Offer};
use njmule_engine::server::server_list;
use njmule_engine::util::ipv4;

const LISTEN_PORT: u16 = 4662;

fn high_id() -> u32 {
    ipv4::to_ed2k(u32::from_be_bytes([1, 2, 3, 4]))
}

fn hash(n: usize) -> [u8; 16] {
    let mut hash = [0; 16];
    hash[..8].copy_from_slice(&(n as u64).to_le_bytes());
    hash
}

fn id(client_id: u32, flags: u32) -> Option<IdChange> {
    Some(IdChange { client_id, server_flags: flags, reported_ip: 0 })
}

fn offers(packet: &Packet) -> Vec<FileEntry> {
    let packet = packing::unpack_from_server(packet.clone()).unwrap();
    let mut input = PacketReader::of(packet.payload());
    let count = input.u32().unwrap();
    (0..count).map(|_| FileEntry::read(&mut input).unwrap()).collect()
}

#[test]
fn should_offer_a_high_id_address_and_large_files_only_to_servers_that_take_them() {
    let files = [
        Offer { hash: hash(1), name: "film.avi".to_owned(), size: 1000, complete: true },
        Offer { hash: hash(2), name: "huge.iso".to_owned(), size: 5_000_000_000, complete: true },
    ];
    let mut publisher = FilePublisher::default();
    let now = Instant::now();
    publisher.connected(now);

    let (packet, hashes) = publisher.tick(now, id(high_id(), 0), None, LISTEN_PORT, &files).unwrap();
    publisher.sent(now, hashes);

    let first = offers(&packet);
    assert_eq!(first.len(), 1);
    assert_eq!((first[0].hash, first[0].client_id, first[0].port), (hash(1), high_id(), LISTEN_PORT));
    assert_eq!(tags::string_value(&first[0].tags, file_tag::FILETYPE), Some("Video"));
    // Nothing new.
    assert!(publisher.tick(now, id(high_id(), 0), None, LISTEN_PORT, &files).is_none());
}

#[test]
fn should_offer_a_low_id_without_address_and_in_batches_that_wait_their_turn() {
    let mut files: Vec<Offer> = (0..=BATCH)
        .map(|i| Offer {
            hash: hash(i + 10),
            name: format!("file {i}.iso"),
            size: 5_000_000_000 + i as u64,
            complete: i > 0,
        })
        .collect();
    let flags = server_messages::TCP_LARGEFILES | server_messages::TCP_TYPE_TAG_INTEGER;
    let mut publisher = FilePublisher::default();
    let start = Instant::now();
    publisher.connected(start);
    let (packet, hashes) = publisher.tick(start, id(1234, flags), None, LISTEN_PORT, &files).unwrap();
    publisher.sent(start, hashes);
    let batch = offers(&packet);
    assert_eq!(batch.len(), BATCH);
    assert!(batch.iter().all(|entry| entry.client_id == 0 && entry.port == 0));
    assert_eq!(tags::int_value(&batch[0].tags, file_tag::FILETYPE), Some(4));
    // The next batch waits a minute.
    assert!(publisher.tick(start, id(1234, flags), None, LISTEN_PORT, &files).is_none());
    let later = start + INTERVAL;
    let (packet, hashes) = publisher.tick(later, id(1234, flags), None, LISTEN_PORT, &files).unwrap();
    publisher.sent(later, hashes);
    assert_eq!(offers(&packet).iter().map(|entry| entry.hash).collect::<Vec<_>>(), [hash(BATCH + 10)]);
    files[0].complete = true;
    publisher.changed(Some(hash(10)));

    let (packet, _) = publisher.tick(later + INTERVAL, id(1234, flags), None, LISTEN_PORT, &files).unwrap();

    // A file that completed is offered again.
    assert_eq!(offers(&packet).iter().map(|entry| entry.hash).collect::<Vec<_>>(), [hash(10)]);
}

#[test]
fn should_offer_no_more_files_than_the_server_takes() {
    let files: Vec<Offer> = (0..5)
        .map(|i| Offer { hash: hash(i + 100), name: format!("song {i}.mp3"), size: 1000 + i as u64, complete: true })
        .collect();
    let server = Server { ip: 1, port: 4661, tags: vec![server_list::int(server_tag::SOFTFILES, 3)] };
    let mut publisher = FilePublisher::default();
    let mut now = Instant::now();
    publisher.connected(now);
    let mut offered = 0;

    for _ in 0..2 {
        if let Some((packet, hashes)) = publisher.tick(now, id(high_id(), 0), Some(&server), LISTEN_PORT, &files) {
            offered += offers(&packet).len();
            publisher.sent(now, hashes);
        }
        now += INTERVAL;
    }

    assert_eq!(offered, 3);
}

#[test]
fn should_offer_nothing_without_a_session() {
    let files = [Offer { hash: hash(1), name: "film.avi".to_owned(), size: 1000, complete: true }];
    let mut publisher = FilePublisher::default();
    let now = Instant::now();
    publisher.connected(now);

    assert!(publisher.tick(now, None, None, LISTEN_PORT, &files).is_none());
}

#[test]
fn should_offer_a_file_of_a_server_with_compression_as_this_client_complete_or_not() {
    let files = [
        Offer { hash: hash(1), name: "song.mp3".to_owned(), size: 5, complete: true },
        Offer { hash: hash(2), name: "part.avi".to_owned(), size: 9, complete: false },
    ];
    let flags = server_messages::TCP_COMPRESSION | server_messages::TCP_NEWTAGS;
    let mut publisher = FilePublisher::default();
    let now = Instant::now();
    publisher.connected(now);

    let (packet, _) = publisher.tick(now, id(high_id(), flags), None, LISTEN_PORT, &files).unwrap();

    let entries = offers(&packet);
    assert_eq!((entries[0].client_id, entries[0].port), (0xFBFB_FBFB, 0xFBFB));
    assert_eq!((entries[1].client_id, entries[1].port), (0xFCFC_FCFC, 0xFCFC));
}
