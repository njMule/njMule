// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's MetFilesEdgeCaseTest, under their names: corner cases of the binary files of a
//! profile. Where the Java engine checks that only a DecodeException comes out, a panic is what fails here.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use njmule_engine::files::hash::ed2k::MAX_FILE_SIZE;
use njmule_engine::kad::uint128::UInt128;
use njmule_engine::persist::format::clients_met::{self, Credit};
use njmule_engine::persist::format::friends_met::{self, Friend};
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::known2_met::{self, Entry};
use njmule_engine::persist::format::part_met::{self, Gap};
use njmule_engine::persist::format::search_files::{self, SearchResult, StoredSearch};
use njmule_engine::persist::format::server_met::{self, Server};
use njmule_engine::persist::format::{cancelled_met, preferences_kad_dat};
use njmule_engine::protocol::io::DecodeError;
use njmule_engine::protocol::tag::{Tag, TagName, file_tag, server_tag};
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

const H: [u8; 16] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];
// The key size byte follows the 16-byte hash, five u32 and one u16 of the first record.
const KEY_SIZE_AT: usize = 1 + 4 + 16 + 5 * 4 + 2;

fn int(id: u8, value: u64) -> Tag {
    Tag::Int { name: TagName::id(id), value }
}

fn string(id: u8, value: &str) -> Tag {
    Tag::String { name: TagName::id(id), value: value.to_owned() }
}

fn named(name: &[u8], value: u64) -> Tag {
    Tag::Int { name: TagName::of_bytes(name), value }
}

fn part_met(tags: Vec<Tag>) -> part_met::Contents {
    part_met::Contents { version: part_met::VERSION, mtime: 0, file_hash: H, part_hashes: Vec::new(), tags }
}

fn gap(start: u64, end: u64) -> Gap {
    Gap::new(start, end).unwrap()
}

/// Reads a file and uses what a caller would use of it.
type Use = fn(&[u8]) -> Result<(), DecodeError>;

/// Valid files of every counted format, each read and then used.
fn samples() -> Vec<(&'static str, Vec<u8>, Use)> {
    let aich = [0; 20];
    vec![
        (
            "server.met",
            server_met::write(&[Server {
                ip: 0x0102_0304,
                port: 4661,
                tags: vec![string(server_tag::SERVERNAME, "Big"), int(server_tag::PING, 20)],
            }])
            .unwrap(),
            |data| {
                for server in server_met::read(data)? {
                    let _ = (server.name(), server.version(), server.last_ping(), server.obfuscated_tcp_port());
                }
                Ok(())
            },
        ),
        (
            "known.met",
            known_met::write(&[KnownFile {
                mtime: 1,
                file_hash: H,
                part_hashes: Vec::new(),
                tags: vec![string(file_tag::FILENAME, "a.bin"), int(file_tag::FILESIZE, 100)],
            }])
            .unwrap(),
            |data| {
                for file in known_met::read(data)? {
                    let _ = (file.name(), file.size(), file.aich_hash(), file.transferred(), file.is_consistent());
                }
                Ok(())
            },
        ),
        (
            "part.met",
            part_met::write(
                &part_met(vec![string(file_tag::FILENAME, "x"), int(file_tag::FILESIZE, 1000)])
                    .with_gaps(&[gap(0, 100), gap(500, 1000)]),
            )
            .unwrap(),
            |data| {
                let contents = part_met::read(data)?;
                let _ =
                    (contents.gaps(), contents.name(), contents.aich_hash(), contents.paused(), contents.category());
                Ok(())
            },
        ),
        (
            "clients.met",
            clients_met::write(&[
                Credit { user_hash: H, uploaded: 1, downloaded: 2, last_seen: 3, public_key: vec![0; 76] },
                Credit { user_hash: H, uploaded: 5_000_000_000, downloaded: 0, last_seen: 0, public_key: Vec::new() },
            ])
            .unwrap(),
            |data| clients_met::read(data).map(|_| ()),
        ),
        (
            "emfriends.met",
            friends_met::write(&[Friend {
                user_hash: H,
                last_ip: 0x0102_0304,
                last_port: 4662,
                last_seen: 1,
                last_chatted: 2,
                tags: Vec::new(),
            }])
            .unwrap(),
            |data| {
                for friend in friends_met::read(data)? {
                    let _ = (friend.name(), friend.kad_id());
                }
                Ok(())
            },
        ),
        ("cancelled.met", cancelled_met::write(7, &[cancelled_met::key(7, &H)]).unwrap(), |data| {
            cancelled_met::read(data).map(|_| ())
        }),
        (
            "StoredSearches.met",
            search_files::write_stored(&[StoredSearch {
                id: 1,
                search_type: 1,
                client_shared_files: false,
                title: "title".to_owned(),
                expression: "expression".to_owned(),
                file_type: String::new(),
                results: vec![SearchResult {
                    file_hash: H,
                    client_id: 0,
                    port: 0,
                    tags: vec![string(file_tag::FILENAME, "r")],
                }],
            }])
            .unwrap(),
            |data| search_files::read_stored(data).map(|_| ()),
        ),
        ("SearchSpam.met", search_files::write_spam(&[int(search_files::SPAM_SIZE, 5)]).unwrap(), |data| {
            search_files::read_spam(data).map(|_| ())
        }),
        (
            "known2_64.met",
            known2_met::write(&[Entry { master_hash: aich, block_hashes: vec![aich] }]).unwrap(),
            |data| known2_met::read(data).map(|_| ()),
        ),
        (
            "preferencesKad.dat",
            preferences_kad_dat::write(&preferences_kad_dat::Contents {
                last_ip: 1,
                kad_id: UInt128(0x0102_0304_0506_0708_090A_0B0C_0D0E_0F10),
            }),
            |data| preferences_kad_dat::read(data).map(|_| ()),
        ),
    ]
}

#[test]
fn should_only_fail_cleanly_on_a_changed_byte() {
    let mut random = StdRng::seed_from_u64(1);
    for (name, data, use_it) in samples() {
        for _ in 0..500 {
            let mut changed = data.clone();
            let at = random.random_range(0..changed.len());
            changed[at] = random.random();

            // A panic fails the test; a DecodeError is a clean failure.
            let _ = use_it(&changed);
        }
        assert!(use_it(&data).is_ok(), "{name}");
    }
}

#[test]
fn should_only_fail_cleanly_on_a_sample_changed_at_random() {
    let mut random = StdRng::seed_from_u64(2);
    for (_, data, use_it) in samples() {
        for _ in 0..300 {
            let changed = changed_at_random(&data, &mut random);

            let _ = use_it(&changed);
        }
    }
}

// Overwrites, inserts and removes a few bytes, as a damaged or crafted file would.
fn changed_at_random(data: &[u8], random: &mut StdRng) -> Vec<u8> {
    let mut changed = data.to_vec();
    for _ in 0..random.random_range(1..=4) {
        let at = random.random_range(0..=changed.len());
        match random.random_range(0..3) {
            0 if at < changed.len() => changed[at] = random.random(),
            1 => changed.insert(at, random.random()),
            _ if at < changed.len() => {
                changed.remove(at);
            }
            _ => changed.push(random.random()),
        }
    }
    changed
}

#[test]
fn should_fail_cleanly_on_every_cut_of_a_counted_file() {
    // known2_64.met has no count: a cut between two entries is a shorter valid file.
    for (name, data, use_it) in samples().into_iter().filter(|(name, ..)| *name != "known2_64.met") {
        // preferencesKad.dat ends with a byte nobody reads.
        let complete = if name == "preferencesKad.dat" { 22 } else { data.len() };
        for length in 0..complete {
            assert!(use_it(&data[..length]).is_err(), "{name} cut to {length} bytes");
        }
    }
}

#[test]
fn should_fail_cleanly_on_huge_counts_with_no_data() {
    for file in ["e0ffffffff", "0effffffff", "0e01ffff", "45ffffffff", "0f01ffff", "0fffffffff"] {
        let data = hex::decode(file).unwrap();
        for (_, _, use_it) in samples() {
            let _ = use_it(&data);
        }
    }
}

#[test]
fn should_refuse_a_part_met_without_a_usable_size() {
    for size in [0, MAX_FILE_SIZE + 1] {
        let file = part_met::write(&part_met(vec![int(file_tag::FILESIZE, size)])).unwrap();

        assert!(part_met::read(&file).is_err(), "size {size}");
    }
}

#[test]
fn should_refuse_a_part_met_without_size() {
    // Header, mtime, file hash, no part hashes, no tags.
    let file = hex::decode("e0".to_owned() + "00000000" + &hex::encode(H) + "0000" + "00000000").unwrap();

    assert!(part_met::read(&file).is_err());
}

#[test]
fn should_write_the_part_met_of_the_largest_size_in_the_large_version() {
    let file = part_met::write(&part_met(vec![int(file_tag::FILESIZE, MAX_FILE_SIZE)])).unwrap();

    assert_eq!(part_met::read(&file).unwrap().version, part_met::VERSION_LARGE);
}

#[test]
fn should_pair_the_gap_tags_by_their_number_not_its_spelling() {
    let contents = part_met(vec![
        int(file_tag::FILESIZE, 1000),
        named(b"\t01", 100),
        named(b"\n1", 200),
        named(b"\t000", 300),
        named(b"\n0", 400),
    ]);

    assert_eq!(contents.gaps(), [gap(100, 200), gap(300, 400)]);
}

#[test]
fn should_drop_or_clamp_odd_gaps() {
    let contents = part_met(vec![
        int(file_tag::FILESIZE, 1000),
        // Inverted, empty, past the end, clamped, duplicate start, and a gap tag that is not a number.
        named(b"\t1", 50),
        named(b"\n1", 40),
        named(b"\t2", 60),
        named(b"\n2", 60),
        named(b"\t3", 1000),
        named(b"\n3", 2000),
        named(b"\t4", 900),
        named(b"\n4", 5000),
        named(b"\t5", 100),
        named(b"\n5", 150),
        named(b"\t6", 100),
        named(b"\n6", 200),
        Tag::String { name: TagName::of_bytes(b"\t7"), value: "300".to_owned() },
        named(b"\n7", 400),
    ]);

    assert_eq!(contents.gaps(), [gap(100, 200), gap(900, 1000)]);
}

#[test]
fn should_keep_the_gap_lists_through_a_write_and_a_read() {
    let mut random = StdRng::seed_from_u64(3);
    for _ in 0..200 {
        // Sorted, disjoint gaps within the file: cut points two by two.
        let mut points: Vec<u64> =
            (0..random.random_range(0..=40)).map(|_| random.random_range(0..=1_000_000)).collect();
        points.sort_unstable();
        points.dedup();
        let gaps: Vec<Gap> = points.as_chunks::<2>().0.iter().map(|[start, end]| gap(*start, *end)).collect();
        let file = part_met::write(&part_met(vec![int(file_tag::FILESIZE, 1_000_000)]).with_gaps(&gaps)).unwrap();

        assert_eq!(part_met::read(&file).unwrap().gaps(), gaps);
    }
}

#[test]
fn should_refuse_a_public_key_of_more_than_eighty_bytes() {
    let mut file = clients_met::write(&[Credit {
        user_hash: H,
        uploaded: 0,
        downloaded: 0,
        last_seen: 0,
        public_key: vec![0; 80],
    }])
    .unwrap();
    assert_eq!(file[KEY_SIZE_AT], 80);
    file[KEY_SIZE_AT] = 81;

    assert!(clients_met::read(&file).is_err());
}

#[test]
fn should_keep_credits_beyond_four_gigabytes_through_a_write_and_a_read() {
    let credits = [Credit {
        user_hash: H,
        uploaded: 5_000_000_000,
        downloaded: (1 << 40) + 7,
        last_seen: 1_700_000_000,
        public_key: Vec::new(),
    }];
    let file = clients_met::write(&credits).unwrap();

    assert_eq!(clients_met::read(&file).unwrap(), credits);
}

#[test]
fn should_refuse_to_write_a_public_key_of_more_than_eighty_bytes() {
    let credit = Credit { user_hash: H, uploaded: 0, downloaded: 0, last_seen: 0, public_key: vec![0; 81] };

    assert!(clients_met::write(&[credit]).is_err());
}

// Beyond the Java engine's examples: the tags the engine reads for later, and the ends of damaged files.

#[test]
fn should_read_every_tag_of_a_server_kept_in_server_met() {
    let server = Server {
        ip: 0x0102_0304,
        port: 4661,
        tags: vec![
            int(server_tag::FAIL, 2),
            named(server_tag::USERS, 300),
            named(server_tag::FILES, 9000),
            int(server_tag::MAXUSERS, 500),
            int(server_tag::UDPFLAGS, 0x7FF),
            int(server_tag::UDPKEY, 77),
            int(server_tag::UDPKEYIP, 0x0A00_0001),
            int(server_tag::TCPPORTOBFUSCATION, 4662),
            int(server_tag::UDPPORTOBFUSCATION, 4672),
            int(server_tag::PING, 120),
            int(server_tag::SOFTFILES, 1000),
            int(server_tag::HARDFILES, 2000),
            int(server_tag::LOWIDUSERS, 30),
        ],
    };

    let read = &server_met::read(&server_met::write(&[server]).unwrap()).unwrap()[0];

    assert_eq!((read.failed_count(), read.users(), read.files()), (Some(2), Some(300), Some(9000)));
    assert_eq!((read.max_users(), read.udp_flags(), read.udp_key()), (Some(500), Some(0x7FF), Some(77)));
    assert_eq!(read.udp_key_ip(), Some(0x0A00_0001));
    assert_eq!((read.obfuscated_tcp_port(), read.obfuscated_udp_port()), (Some(4662), Some(4672)));
    assert_eq!((read.ping(), read.soft_files(), read.hard_files()), (Some(120), Some(1000), Some(2000)));
    assert_eq!(read.low_id_users(), Some(30));
}

#[test]
fn should_read_the_upload_tags_of_a_known_file() {
    let file = KnownFile {
        mtime: 0,
        file_hash: H,
        part_hashes: Vec::new(),
        tags: vec![
            int(file_tag::FILESIZE, 10),
            int(file_tag::ULPRIORITY, 3),
            int(file_tag::ATREQUESTED, 40),
            int(file_tag::ATACCEPTED, 12),
            string(file_tag::AICH_HASH, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        ],
    };
    let short_aich = KnownFile { tags: vec![string(file_tag::AICH_HASH, "AAAA")], ..file.clone() };

    let read = &known_met::read(&known_met::write(&[file, short_aich]).unwrap()).unwrap();

    assert_eq!((read[0].upload_priority(), read[0].requests(), read[0].accepted()), (Some(3), 40, 12));
    assert_eq!(read[0].aich_hash(), Some([0; 20]));
    assert_eq!(read[1].aich_hash(), None);
}

#[test]
fn should_refuse_to_write_more_part_hashes_than_a_file_has() {
    let file = KnownFile {
        mtime: 0,
        file_hash: H,
        part_hashes: vec![H; njmule_engine::persist::format::met_codecs::MAX_PART_HASHES + 1],
        tags: Vec::new(),
    };

    assert!(known_met::write(&[file]).is_err());
}

#[test]
fn should_give_no_gaps_for_a_size_beyond_what_a_file_can_have() {
    let contents = part_met(vec![int(file_tag::FILESIZE, u64::MAX), named(b"\t0", 0), named(b"\n0", 100)]);

    assert!(contents.gaps().is_empty());
}

#[test]
fn should_refuse_a_bootstrap_nodes_dat_with_trailing_data() {
    let mut data = Vec::new();
    for value in [0_u32, 3, 1, 1] {
        data.extend(value.to_le_bytes());
    }
    data.extend([0; 26]);

    assert!(njmule_engine::persist::format::nodes_dat::read(&data).is_err());
}

#[test]
fn should_read_the_levels_and_lists_of_every_ip_filter_form() {
    use njmule_engine::persist::format::ip_filter_dat;
    let ranges = ip_filter_dat::read(b"1.2.3.0 - 1.2.3.255 , 1000\n5.6.7.0 - 5.6.7.9 , 042 , office\n", "list.prefix");

    assert_eq!(
        ranges.iter().map(|r| (r.level, r.description.as_str())).collect::<Vec<_>>(),
        [(100, ""), (42, "office")]
    );
    // A binary list that ends inside an entry keeps the entries before it.
    for cut in [2_usize, 6] {
        let mut p2b = vec![0xFF, 0xFF, 0xFF, 0xFF, b'P', b'2', b'B', 2];
        p2b.extend(b"a\0");
        p2b.extend([1, 2, 3, 0, 1, 2, 3, 255]);
        p2b.extend(b"b\0");
        p2b.extend(&[5, 6, 7, 0, 5, 6, 7, 255][..cut]);

        assert_eq!(ip_filter_dat::read(&p2b, "list.p2b").len(), 1, "{cut}");
    }
}
