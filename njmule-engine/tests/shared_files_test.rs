// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SharedFilesTest for the shared folders and trees, private folders and scans;
//! those of renames, priorities, counts, AICH trees and other clients' records wait for their ports. The disk is read
//! and hashed here as the network does it off its task, one step after the other.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::{HashMap, VecDeque};
use std::fs;
use std::io;
use std::ops::ControlFlow;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use njmule_engine::api::shared_file_info::Statistics;
use njmule_engine::api::upload_priority::UploadPriority;
use njmule_engine::files::hash::aich_recovery;
use njmule_engine::files::hash::aich_tree::AichTree;
use njmule_engine::files::hash::ed2k::PARTSIZE;
use njmule_engine::files::hash::file_hasher;
use njmule_engine::files::shared_files::{
    self, Changes, Finished, Found, Listing, RenameError, SharedFile, SharedFiles, Sharing,
};
use njmule_engine::persist::format::known_met::{self, KnownFile};
use njmule_engine::persist::format::text_files;
use rand::rngs::StdRng;
use rand::{RngExt, SeedableRng};

#[test]
fn should_share_the_incoming_and_shared_directories_and_remember_the_hashes() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 100_000, 1);
    shares.given_a_file("extra/b.bin", 50_000, 2);
    shares.given_a_one_byte_file("incoming/desktop.ini");
    shares.given_a_one_byte_file("incoming/001.part");
    shares.given_a_one_byte_file("incoming/sub/c.bin");
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_directory_is_shared("extra");
    shares.given_the_shares_report(&["shared b.bin"]);
    shares.given_the_shared_file_has_the_size_of_its_content("a.bin");
    shares.given_the_number_of_shared_files_is(2);
    shares.given_the_shares_are_closed();
    shares.given_the_config_directory_holds(shared_files::KNOWN_FILE_NAME);
    shares.given_the_shares_are_open();
    shares.given_a_scan_shares_the_known_files_at_once(2);
    shares.given_the_file_is_deleted("extra/b.bin");
    shares.given_the_events_so_far_are_ignored();

    shares.when_the_shares_are_scanned();

    shares.then_the_shares_report(&["unshared b.bin"]);
    shares.then_the_number_of_shared_files_is(1);
}

#[test]
fn should_treat_private_directories_as_private() {
    for directory in private_directories() {
        let mut shares = Shares::new();
        shares.given_private_directories_and_a_backup_beside_a_file_to_share();
        shares.given_the_shares_are_open();
        shares.given_the_shares_report(&["shared real.bin"]);

        shares.when_it_is_asked_whether_the_directory_is_private(&directory);

        shares.then_the_directory_is_private();
    }
}

#[test]
fn should_not_treat_the_incoming_directory_as_private() {
    let mut shares = Shares::new();
    shares.given_private_directories_and_a_backup_beside_a_file_to_share();
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared real.bin"]);

    shares.when_it_is_asked_whether_the_directory_is_private(Path::new("incoming"));

    shares.then_the_directory_is_not_private();
}

#[test]
fn should_never_share_private_directories_or_backups() {
    let mut shares = Shares::new();
    shares.given_private_directories_and_a_backup_beside_a_file_to_share();
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared real.bin"]);

    shares.when_the_private_directories_are_shared();

    shares.then_every_private_directory_is_refused();
    shares.then_the_shares_report_nothing();
    shares.then_the_number_of_shared_files_is(1);
    shares.then_no_directory_is_shared();
}

#[test]
fn should_keep_a_private_directory_listed_by_an_older_version_unshared() {
    let mut shares = Shares::new();
    shares.given_private_directories_and_a_backup_beside_a_file_to_share();
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared real.bin"]);
    shares.given_the_shares_are_closed();
    shares.given_the_shared_directories_list_holds("emule-config");

    shares.when_the_shares_are_opened();

    shares.then_the_shares_report(&["shared real.bin"]);
    shares.then_the_shares_report_nothing();
}

#[test]
fn should_share_a_tree_with_its_subdirectories_also_later_ones_until_it_is_unshared() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("tree/sub/c.bin", 3000, 4);
    shares.given_a_file("tree/sub/deeper/d.bin", 4000, 5);
    shares.given_the_shares_are_open();
    shares.given_the_tree_is_shared("tree");
    shares.given_the_shares_report_in_any_order(&["shared c.bin", "shared d.bin"]);
    shares.given_the_shared_trees_are("tree");
    shares.given_no_directory_is_shared();
    shares.given_a_file_in_a_directory_made_later("tree/later/e.bin", 5000, 6);
    shares.given_the_shares_are_scanned();
    shares.given_the_shares_report(&["shared e.bin"]);

    shares.when_the_directory_is_unshared("tree");

    shares.then_the_shares_report_in_any_order(&["unshared c.bin", "unshared d.bin", "unshared e.bin"]);
    shares.then_no_tree_is_shared();
}

#[test]
fn should_go_on_scanning_a_directory_past_a_file_that_cannot_be_read() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_numbered_files_in_the_incoming_directory(6);
    shares.given_the_shares_are_open();
    shares.given_the_order_in_which_the_files_are_shared(6);
    shares.given_the_shares_are_closed();

    shares.when_the_shares_are_opened_and_the_second_file_goes_while_the_directory_is_read();

    shares.then_all_the_files_but_the_second_are_shared();
}

#[test]
fn should_keep_the_content_shared_through_another_copy_when_one_goes() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/one.bin", 4000, 30);
    shares.given_a_copy_of("one.bin", "incoming/two.bin");
    shares.given_the_shares_are_open();
    shares.given_the_second_copy_is_tracked("one.bin");
    shares.given_the_tracked_copy_is_deleted();
    shares.given_the_events_so_far_are_ignored();

    shares.when_the_shares_are_scanned();

    shares.then_the_other_copy_is_shared("one.bin");
    shares.then_the_shares_report_nothing();
}

#[test]
fn should_unshare_a_file_whose_content_changes_under_its_old_hash() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/changing.bin", 4000, 31);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared changing.bin"]);
    shares.given_the_hash_of_the_shared_file_is_noted();
    shares.given_the_file_changes("incoming/changing.bin", 5000, 32);

    shares.when_the_shares_are_scanned();

    shares.then_the_shares_report(&["unshared changing.bin", "shared changing.bin"]);
    shares.then_no_file_is_shared_under_the_old_hash();
}

#[test]
fn should_leave_out_unreadable_lists_and_records_and_hash_the_files_again() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_unreadable_lists_and_records_in_the_config_directory();
    shares.given_a_file("incoming/a.bin", 3000, 38);
    shares.given_an_empty_file("incoming/empty.bin");
    shares.given_a_hidden_file("incoming/.hidden.bin", 3000, 39);

    shares.when_the_shares_are_opened();

    shares.then_the_shares_report(&["shared a.bin"]);
    shares.then_no_directory_is_shared();
    shares.then_the_only_shared_tree_is("missing");
}

#[test]
fn should_scan_nothing_once_closed_and_leave_an_unwritable_known_met_as_it_is() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_unreadable_lists_and_records_in_the_config_directory();
    shares.given_a_file("incoming/a.bin", 3000, 38);
    shares.given_an_empty_file("incoming/empty.bin");
    shares.given_a_hidden_file("incoming/.hidden.bin", 3000, 39);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);

    shares.when_the_shares_are_closed_and_scanned();

    shares.then_the_shares_report_nothing();
    shares.then_the_config_file_is_a_directory(shared_files::KNOWN_FILE_NAME);
}

#[test]
fn should_keep_a_shared_tree_to_itself_through_junctions() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("tree/sub/in.bin", 3000, 36);
    shares.given_a_file("outside/secret.bin", 3000, 37);
    shares.given_a_junction("tree/escape", "outside");
    shares.given_a_junction("tree/loop", "tree");
    shares.given_the_shares_are_open();

    shares.when_the_tree_is_shared("tree");

    shares.then_the_shares_report(&["shared in.bin"]);
    shares.then_the_shares_report_nothing();
    shares.then_the_only_shared_file_is("tree/sub/in.bin");
}

#[test]
fn should_take_the_record_of_a_file_dated_an_hour_off_without_hashing_it_again() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 3000, 40);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_shares_are_closed();
    shares.given_the_file_is_dated_an_hour_later("incoming/a.bin");

    shares.when_the_shares_are_opened();

    shares.then_the_shares_report(&["shared a.bin"]);
    shares.then_the_files_hashed_are(1);
}

#[test]
fn should_find_the_files_of_a_folder_but_not_its_folders_or_empty_files() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/b.avi", 5, 41);
    shares.given_a_file("incoming/a.iso", 5, 42);
    shares.given_an_empty_file("incoming/empty.txt");
    shares.given_a_file("incoming/inner/c.mp3", 4, 43);

    shares.when_the_incoming_directory_is_read();

    shares.then_the_files_found_are(&["a.iso", "b.avi"]);
}

#[test]
fn should_count_the_requests_slots_and_bytes_of_a_shared_file() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 20_000, 3);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);

    shares.when_the_file_is_asked_for_twice_and_sent_once("a.bin", 9_000);

    shares.then_its_statistics_are("a.bin", statistics([2, 1, 9_000, 2, 1, 9_000]));
}

#[test]
fn should_add_the_counts_of_a_run_to_the_totals_known_met_keeps() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 20_000, 3);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_file_asked_for_twice_and_sent_once("a.bin", 9_000);
    shares.given_the_shares_are_closed();
    shares.given_the_shares_are_open();
    shares.given_the_shares_are_scanned();

    shares.when_the_file_is_asked_for_twice_and_sent_once("a.bin", 1_000);

    shares.then_its_statistics_are("a.bin", statistics([2, 1, 1_000, 4, 2, 10_000]));
}

#[test]
fn should_keep_the_upload_priority_of_each_file_in_known_met() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 20_000, 3);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_upload_priority_is("a.bin", UploadPriority::Auto);
    shares.given_the_upload_priority_is_set("a.bin", UploadPriority::Release);
    shares.given_the_upload_priority_of_an_unknown_file_cannot_be_set([0; 16], UploadPriority::Low);
    shares.given_the_shares_are_closed();
    shares.given_known_met_holds_one_record_with_the_upload_priority(3);
    shares.given_the_shares_are_open();

    shares.when_the_shares_are_scanned();

    shares.then_the_upload_priority_is("a.bin", UploadPriority::Release);
}

#[test]
fn should_rename_a_shared_file_and_keep_what_is_known_of_it() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 20_000, 6);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_upload_priority_is_set("a.bin", UploadPriority::High);

    shares.when_the_file_is_renamed("a.bin", "b?.bin");

    shares.then_the_renamed_file_is_named("b_.bin");
    shares.then_the_file_has_the_content_of("incoming/b_.bin", "a.bin");
    shares.then_the_file_does_not_exist("incoming/a.bin");
    shares.then_the_shares_report(&["unshared a.bin", "shared b_.bin"]);
    shares.then_the_upload_priority_is("a.bin", UploadPriority::High);
}

#[test]
fn should_refuse_to_rename_a_shared_file_over_another_file() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 20_000, 6);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_a_one_byte_file("incoming/c.bin");

    shares.when_the_file_is_renamed("a.bin", "c.bin");

    shares.then_the_rename_fails_with(io::ErrorKind::AlreadyExists);
}

#[test]
fn should_rename_nothing_for_an_unknown_hash() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 20_000, 6);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);

    shares.when_an_unknown_file_is_renamed([0; 16], "d.bin");

    shares.then_nothing_is_renamed();
}

#[test]
fn should_keep_every_upload_priority_under_the_code_other_clients_use() {
    for priority in UploadPriority::ALL {
        let mut shares = Shares::new();
        shares.given_an_incoming_and_a_config_directory();
        shares.given_a_file("incoming/a.bin", 2000, 42);
        shares.given_the_shares_are_open();
        shares.given_the_shares_report(&["shared a.bin"]);

        shares.when_the_upload_priority_is_set("a.bin", priority);

        shares.then_the_upload_priority_is("a.bin", priority);
    }
}

#[test]
fn should_give_an_unknown_file_the_automatic_upload_priority() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 2000, 42);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);

    shares.when_the_upload_priority_is_asked_for([0x25; 16]);

    shares.then_the_upload_priority_answered_is(UploadPriority::Auto);
}

#[test]
fn should_change_the_case_of_a_name_too_when_renaming() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/movie.avi", 3000, 34);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared movie.avi"]);

    shares.when_the_file_is_renamed("movie.avi", "Movie.avi");

    shares.then_the_renamed_file_is_named("Movie.avi");
    shares.then_the_incoming_directory_holds_only("Movie.avi");
    shares.then_the_file_is_shared_under_the_name("movie.avi", "Movie.avi");
}

#[test]
fn should_refuse_names_that_are_never_shared() {
    for name in ["kept.bak", "kept.tmp", "001.part", "Desktop.ini", "link.lnk", ".hidden"] {
        let mut shares = Shares::new();
        shares.given_an_incoming_and_a_config_directory();
        shares.given_a_file("incoming/kept.bin", 3000, 35);
        shares.given_the_shares_are_open();
        shares.given_the_shares_report(&["shared kept.bin"]);

        shares.when_the_file_is_renamed("kept.bin", name);

        shares.then_the_rename_is_refused_as_never_shared();
        shares.then_the_file_exists("incoming/kept.bin");
        shares.then_the_file_is_shared_under_the_name("kept.bin", "kept.bin");
    }
}

#[test]
fn should_build_the_aich_tree_of_a_file_once_for_all_its_requests() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 500_000, 7);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_aich_tree_is_requested("a.bin");

    shares.when_the_aich_tree_is_requested("a.bin");

    shares.then_the_same_aich_tree_is_given();
    shares.then_the_aich_tree_covers_the_file("a.bin");
}

#[test]
fn should_keep_a_file_renamed_while_hashed_again_shared_under_its_new_name() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_the_shares_are_open();
    shares.given_a_file("incoming/done.bin", 600_000, 33);
    shares.given_the_download("done.bin");
    shares.given_the_download_finishes_and_is_renamed("done.bin", "renamed.bin");
    shares.given_the_file_gets_an_aich_tree("done.bin");

    shares.when_the_shares_are_scanned();

    shares.then_the_file_is_shared_as("done.bin", "renamed.bin");
    shares.then_the_file_has_an_aich_tree("done.bin");
}

#[test]
fn should_give_a_file_renamed_before_it_is_hashed_again_its_aich_tree_under_its_new_name() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_the_shares_are_open();
    shares.given_a_file("incoming/done.bin", 600_000, 37);
    shares.given_the_download("done.bin");
    shares.given_the_hashing_thread_busy_with_a_large_file(36, [0x77; 16]);

    shares.when_the_download_finishes_and_is_renamed("done.bin", "renamed.bin");

    shares.then_the_file_gets_an_aich_tree("done.bin");
    shares.then_the_file_is_shared_as("done.bin", "renamed.bin");
    shares.then_the_file_with_the_hash_keeps_no_aich_tree([0x77; 16]);
}

#[test]
fn should_give_a_download_finished_while_a_scan_waits_to_read_its_file_its_aich_tree_once_renamed() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_the_shares_are_open();
    shares.given_the_hashing_thread_busy_with_a_large_file(40, [0x66; 16]);

    shares.when_a_download_finishes_after_a_scan_queued_its_file_and_is_renamed("renamed.bin");

    shares.then_the_file_gets_an_aich_tree("done.bin");
    shares.then_the_file_is_shared_as("done.bin", "renamed.bin");
}

#[test]
fn should_hash_a_finished_file_for_its_own_tree_and_leave_out_one_that_cannot_be_hashed() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_the_shares_are_open();
    shares.given_a_file("other/done.bin", 400_000, 44);
    shares.given_the_download_with_the_aich_hash("done.bin", [0x18; 20]);
    shares.given_a_file("extra/gone.bin", 3000, 45);
    shares.given_the_hashing_thread_busy_with_a_large_file(43, [0x26; 16]);
    shares.given_the_download_finished_at("never.bin");
    shares.given_the_download_finished_at("other/done.bin");

    shares.when_a_directory_is_shared_and_its_file_goes_before_it_is_hashed("extra", "extra/gone.bin");

    shares.then_the_file_gets_an_aich_tree("done.bin");
    shares.then_the_aich_hash_is_the_files_own_not_the_links("done.bin");
    shares.then_none_of_these_files_is_shared(&["gone.bin", "never.bin"]);
}

#[test]
fn should_give_the_recovery_data_of_a_part_only_for_the_tree_asked_for() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/big.bin", 2 * PARTSIZE as usize + 1000, 46);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared big.bin"]);

    shares.when_the_recovery_data_of_the_part_is_asked_for("big.bin", 1);

    shares.then_the_recovery_data_proves_the_part("big.bin", 1);
    shares.then_none_is_given_for_another_tree("big.bin", 1);
}

#[test]
fn should_keep_the_aich_tree_of_a_file_across_runs_without_hashing_it_again() {
    let mut shares = Shares::new();
    shares.given_an_incoming_and_a_config_directory();
    shares.given_a_file("incoming/a.bin", 500_000, 47);
    shares.given_the_shares_are_open();
    shares.given_the_shares_report(&["shared a.bin"]);
    shares.given_the_shares_are_closed();

    shares.when_the_shares_are_opened();

    shares.then_the_shares_report(&["shared a.bin"]);
    shares.then_the_files_hashed_are(1);
    shares.then_the_file_has_an_aich_tree("a.bin");
}

fn private_directories() -> Vec<PathBuf> {
    // The home is absolute: joining it to the test folder keeps it as it is.
    let mut directories: Vec<PathBuf> = ["config", "config/sub", "temp", "emule-config"].map(PathBuf::from).into();
    directories.push(dirs::home_dir().unwrap());
    directories
}

/// The shared files of a test folder, scanned and hashed in turn, and what they report.
struct Shares {
    dir: tempfile::TempDir,
    incoming: PathBuf,
    config: PathBuf,
    shares: Option<SharedFiles>,
    events: VecDeque<String>,
    contents: HashMap<String, Vec<u8>>,
    hashed: usize,
    directory: PathBuf,
    private_directory: bool,
    refusals: Vec<(PathBuf, Result<(), String>)>,
    order: Vec<String>,
    tracked: PathBuf,
    old_hash: [u8; 16],
    found: Vec<String>,
    renamed: Option<Result<Option<SharedFile>, RenameError>>,
    priority: Option<UploadPriority>,
    // The files waiting for the hasher, and whether it is busy, so that they wait until a step lets it answer.
    waiting: VecDeque<Found>,
    busy: bool,
    download: Option<Finished>,
    link_aich_hash: Option<[u8; 20]>,
    first_aich_tree: Option<Arc<AichTree>>,
    aich_tree: Option<Arc<AichTree>>,
    recovery: Option<Vec<u8>>,
}

impl Shares {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        Self {
            incoming: dir.path().join("incoming"),
            config: dir.path().join("config"),
            dir,
            shares: None,
            events: VecDeque::new(),
            contents: HashMap::new(),
            hashed: 0,
            directory: PathBuf::new(),
            private_directory: false,
            refusals: Vec::new(),
            order: Vec::new(),
            tracked: PathBuf::new(),
            old_hash: [0; 16],
            found: Vec::new(),
            renamed: None,
            priority: None,
            waiting: VecDeque::new(),
            busy: false,
            download: None,
            link_aich_hash: None,
            first_aich_tree: None,
            aich_tree: None,
            recovery: None,
        }
    }

    fn given_an_incoming_and_a_config_directory(&self) {
        fs::create_dir_all(&self.incoming).unwrap();
        fs::create_dir_all(&self.config).unwrap();
    }

    fn given_a_file(&mut self, path: &str, size: usize, seed: u64) {
        self.write_file(path, bytes(size, seed));
    }

    fn given_a_one_byte_file(&mut self, path: &str) {
        self.write_file(path, vec![1]);
    }

    fn given_an_empty_file(&mut self, path: &str) {
        self.write_file(path, Vec::new());
    }

    fn given_a_hidden_file(&mut self, path: &str, size: usize, seed: u64) {
        let hidden = self.write_file(path, bytes(size, seed));
        #[cfg(windows)]
        assert!(std::process::Command::new("attrib").arg("+h").arg(&hidden).status().unwrap().success());
        #[cfg(not(windows))]
        let _ = hidden;
    }

    fn given_a_copy_of(&mut self, original: &str, path: &str) {
        self.write_file(path, self.contents[original].clone());
    }

    fn given_a_file_in_a_directory_made_later(&mut self, path: &str, size: usize, seed: u64) {
        self.given_a_file(path, size, seed);
    }

    fn given_numbered_files_in_the_incoming_directory(&mut self, count: u64) {
        for i in 0..count {
            self.given_a_file(&format!("incoming/f{i}.bin"), 3000, 20 + i);
        }
    }

    fn given_private_directories_and_a_backup_beside_a_file_to_share(&mut self) {
        self.given_an_incoming_and_a_config_directory();
        self.given_a_file("config/cryptkey.dat", 300, 3);
        self.given_a_file("temp/x.bin", 300, 4);
        self.given_a_file(&format!("emule-config/{}", shared_files::CLIENT_KEY), 300, 5);
        self.given_a_file("emule-config/known.met", 300, 6);
        self.given_a_file("incoming/001.part.met.bak", 300, 7);
        self.given_a_file("incoming/real.bin", 300, 8);
    }

    fn given_unreadable_lists_and_records_in_the_config_directory(&self) {
        // known.met is a folder: it can be neither read nor written.
        fs::create_dir_all(self.config.join(shared_files::KNOWN_FILE_NAME).join("x")).unwrap();
        fs::write(self.config.join("known2_64.met"), [9, 9, 9]).unwrap();
        fs::create_dir_all(self.config.join(shared_files::SHARED_DIRS)).unwrap();
        let entries = ["\u{0}bad".to_owned(), self.dir.path().join("missing").to_string_lossy().into_owned()];
        fs::write(self.config.join(shared_files::SHARED_TREES), text_files::write_lines(&entries)).unwrap();
    }

    fn given_the_shared_directories_list_holds(&self, path: &str) {
        let entries = [self.dir.path().join(path).to_string_lossy().into_owned()];
        fs::write(self.config.join(shared_files::SHARED_DIRS), text_files::write_lines(&entries)).unwrap();
    }

    fn given_a_junction(&self, link: &str, target: &str) {
        link_folder(&self.dir.path().join(link), &self.dir.path().join(target));
    }

    fn given_the_shares_are_open(&mut self) {
        self.open();
    }

    fn given_the_shares_are_closed(&mut self) {
        let _ = self.shares.take().unwrap().close();
    }

    fn given_the_shares_are_scanned(&mut self) {
        self.scan();
    }

    fn given_the_directory_is_shared(&mut self, path: &str) {
        let dir = self.dir.path().join(path);
        self.shares_mut().add_directory(&dir, false).unwrap();
        self.scan();
    }

    fn given_the_tree_is_shared(&mut self, path: &str) {
        self.when_the_tree_is_shared(path);
    }

    fn given_the_shares_report(&mut self, expected: &[&str]) {
        self.then_the_shares_report(expected);
    }

    fn given_the_shares_report_in_any_order(&mut self, expected: &[&str]) {
        self.then_the_shares_report_in_any_order(expected);
    }

    fn given_the_shared_file_has_the_size_of_its_content(&self, name: &str) {
        let shared = self.shares().get(&self.hash_of(name)).unwrap();
        assert_eq!(shared.size, self.contents[name].len() as u64);
    }

    fn given_the_number_of_shared_files_is(&self, count: usize) {
        self.then_the_number_of_shared_files_is(count);
    }

    fn given_the_config_directory_holds(&self, file: &str) {
        assert!(self.config.join(file).exists());
    }

    fn given_a_scan_shares_the_known_files_at_once(&mut self, count: usize) {
        let listing = shared_files::list(&self.shares().places());
        let changes = self.shares_mut().scanned(listing);
        assert_eq!(changes.to_hash, []);
        self.record(changes);
        self.then_the_number_of_shared_files_is(count);
    }

    fn given_the_file_is_deleted(&self, path: &str) {
        fs::remove_file(self.dir.path().join(path)).unwrap();
    }

    fn given_the_events_so_far_are_ignored(&mut self) {
        self.events.clear();
    }

    fn given_the_shared_trees_are(&self, path: &str) {
        assert_eq!(self.shares().trees(), [self.dir.path().join(path)]);
    }

    fn given_no_directory_is_shared(&self) {
        self.then_no_directory_is_shared();
    }

    fn given_the_order_in_which_the_files_are_shared(&mut self, count: usize) {
        self.order =
            (0..count).map(|_| self.events.pop_front().unwrap().strip_prefix("shared ").unwrap().to_owned()).collect();
    }

    // The copy that went second, which the content is shared from now.
    fn given_the_second_copy_is_tracked(&mut self, name: &str) {
        let first = self.events.pop_front().unwrap();
        let shared = self.shares().get(&self.hash_of(name)).unwrap();
        assert_ne!(format!("shared {}", shared.name), first);
        self.tracked = shared.path.clone();
    }

    fn given_the_tracked_copy_is_deleted(&self) {
        fs::remove_file(&self.tracked).unwrap();
    }

    fn given_the_hash_of_the_shared_file_is_noted(&mut self) {
        let hash = self.shares().files().next().unwrap().hash;
        self.old_hash = hash;
    }

    fn given_the_file_changes(&mut self, path: &str, size: usize, seed: u64) {
        let file = self.write_file(path, bytes(size, seed));
        set_modified(&file, SystemTime::now() + Duration::from_secs(10));
    }

    fn given_the_file_is_dated_an_hour_later(&self, path: &str) {
        let file = self.dir.path().join(path);
        let modified = fs::metadata(&file).unwrap().modified().unwrap();
        set_modified(&file, modified + Duration::from_secs(3600));
    }

    fn when_the_shares_are_opened(&mut self) {
        self.open();
    }

    fn when_the_shares_are_scanned(&mut self) {
        self.scan();
    }

    fn when_the_tree_is_shared(&mut self, path: &str) {
        let dir = self.dir.path().join(path);
        self.shares_mut().add_directory(&dir, true).unwrap();
        self.scan();
    }

    fn when_the_directory_is_unshared(&mut self, path: &str) {
        let dir = self.dir.path().join(path);
        self.shares_mut().remove_directory(&dir).unwrap();
        self.scan();
    }

    fn when_it_is_asked_whether_the_directory_is_private(&mut self, path: &Path) {
        self.directory = self.dir.path().join(path);
        self.private_directory = self.shares().is_private(&self.directory);
    }

    fn when_the_private_directories_are_shared(&mut self) {
        for path in private_directories() {
            let dir = self.dir.path().join(&path);
            let refusal = self.shares_mut().add_directory(&dir, false);
            self.refusals.push((path, refusal));
        }
    }

    fn when_the_shares_are_opened_and_the_second_file_goes_while_the_directory_is_read(&mut self) {
        self.load();
        let second = self.incoming.join(&self.order[1]);
        let mut files = Vec::new();
        for looked in shared_files::files_in(&self.incoming).unwrap() {
            if files.is_empty() {
                fs::remove_file(&second).unwrap();
            }
            files.push(looked);
        }
        self.take(Listing { files, folders: vec![self.incoming.clone()] });
    }

    fn when_the_shares_are_closed_and_scanned(&mut self) {
        let _ = self.shares_mut().close();
        self.scan();
    }

    fn when_the_incoming_directory_is_read(&mut self) {
        let listing = shared_files::list(&SharedFiles::load(&self.config).places());
        assert_eq!(listing.files, []);
        self.found = shared_files::files_in(&self.incoming)
            .unwrap()
            .map(|looked| match looked {
                shared_files::Looked::Found(found) => found.name,
                shared_files::Looked::Unreadable(path) => panic!("{} cannot be read", path.display()),
            })
            .collect();
    }

    fn given_the_file_asked_for_twice_and_sent_once(&mut self, name: &str, bytes: u64) {
        self.when_the_file_is_asked_for_twice_and_sent_once(name, bytes);
    }

    fn given_the_upload_priority_is(&self, name: &str, expected: UploadPriority) {
        self.then_the_upload_priority_is(name, expected);
    }

    fn given_the_upload_priority_is_set(&mut self, name: &str, priority: UploadPriority) {
        let hash = self.hash_of(name);
        assert!(self.shares_mut().set_priority(&hash, priority));
    }

    fn given_the_upload_priority_of_an_unknown_file_cannot_be_set(&mut self, hash: [u8; 16], priority: UploadPriority) {
        assert!(!self.shares_mut().set_priority(&hash, priority));
    }

    // The value other clients write.
    fn given_known_met_holds_one_record_with_the_upload_priority(&self, code: u64) {
        let records = known_met::read(&fs::read(self.config.join(shared_files::KNOWN_FILE_NAME)).unwrap()).unwrap();
        assert_eq!(records.iter().map(KnownFile::upload_priority).collect::<Vec<_>>(), [Some(code)]);
    }

    fn when_the_file_is_asked_for_twice_and_sent_once(&mut self, name: &str, bytes: u64) {
        let hash = self.hash_of(name);
        let shares = self.shares_mut();
        shares.asked(&hash);
        shares.asked(&hash);
        shares.accepted(&hash);
        shares.sent(&hash, bytes);
    }

    fn when_the_file_is_renamed(&mut self, name: &str, new_name: &str) {
        let hash = self.hash_of(name);
        self.when_an_unknown_file_is_renamed(hash, new_name);
    }

    fn when_an_unknown_file_is_renamed(&mut self, hash: [u8; 16], new_name: &str) {
        let renamed = self.shares_mut().rename(&hash, new_name);
        self.renamed = Some(renamed.map(|renamed| {
            renamed.map(|(file, changes)| {
                self.record(changes);
                file
            })
        }));
    }

    fn when_the_upload_priority_is_set(&mut self, name: &str, priority: UploadPriority) {
        let hash = self.hash_of(name);
        self.shares_mut().set_priority(&hash, priority);
    }

    fn when_the_upload_priority_is_asked_for(&mut self, hash: [u8; 16]) {
        self.priority = Some(self.shares().priority(&hash));
    }

    fn then_its_statistics_are(&self, name: &str, expected: Statistics) {
        assert_eq!(self.shares().statistics(&self.hash_of(name)), expected);
    }

    fn then_the_upload_priority_is(&self, name: &str, expected: UploadPriority) {
        assert_eq!(self.shares().priority(&self.hash_of(name)), expected);
    }

    fn then_the_upload_priority_answered_is(&self, expected: UploadPriority) {
        assert_eq!(self.priority, Some(expected));
    }

    fn then_the_renamed_file_is_named(&self, name: &str) {
        match &self.renamed {
            Some(Ok(Some(file))) => assert_eq!(file.name, name),
            other => panic!("not renamed: {other:?}"),
        }
    }

    fn then_the_file_has_the_content_of(&self, path: &str, original: &str) {
        assert_eq!(fs::read(self.dir.path().join(path)).unwrap(), self.contents[original]);
    }

    fn then_the_file_does_not_exist(&self, path: &str) {
        assert!(!self.dir.path().join(path).exists());
    }

    fn then_the_file_exists(&self, path: &str) {
        assert!(self.dir.path().join(path).exists());
    }

    fn then_the_rename_fails_with(&self, kind: io::ErrorKind) {
        match &self.renamed {
            Some(Err(RenameError::Failed(e))) => assert_eq!(e.kind(), kind),
            other => panic!("not failed: {other:?}"),
        }
    }

    fn then_the_rename_is_refused_as_never_shared(&self) {
        assert!(matches!(self.renamed, Some(Err(RenameError::NeverShared(_)))), "{:?}", self.renamed);
    }

    fn then_nothing_is_renamed(&self) {
        assert!(matches!(self.renamed, Some(Ok(None))), "{:?}", self.renamed);
    }

    fn then_the_incoming_directory_holds_only(&self, name: &str) {
        let names: Vec<String> = fs::read_dir(&self.incoming)
            .unwrap()
            .map(|entry| entry.unwrap().file_name().into_string().unwrap())
            .collect();
        assert_eq!(names, [name]);
    }

    fn then_the_file_is_shared_under_the_name(&self, file: &str, name: &str) {
        assert_eq!(self.shares().get(&self.hash_of(file)).unwrap().name, name);
    }

    fn given_the_aich_tree_is_requested(&mut self, name: &str) {
        let hash = self.hash_of(name);
        self.first_aich_tree = Some(self.shares_mut().aich_tree(&hash).unwrap());
    }

    fn given_the_download(&mut self, name: &str) {
        self.download = Some(self.finished_of(name, None));
    }

    fn given_the_download_with_the_aich_hash(&mut self, name: &str, aich_hash: [u8; 20]) {
        self.link_aich_hash = Some(aich_hash);
        self.download = Some(self.finished_of(name, Some(aich_hash)));
    }

    fn given_the_download_finishes_and_is_renamed(&mut self, name: &str, new_name: &str) {
        self.when_the_download_finishes_and_is_renamed(name, new_name);
    }

    fn given_the_file_gets_an_aich_tree(&mut self, name: &str) {
        self.then_the_file_gets_an_aich_tree(name);
    }

    // The hasher works on a large finished file, whose content is not that of its download: the files after it wait.
    fn given_the_hashing_thread_busy_with_a_large_file(&mut self, seed: u64, hash: [u8; 16]) {
        let large = self.write_file("other/large.bin", bytes(300_000, seed));
        self.busy = true;
        let busy = Finished { hash, size: 300_000, part_hashes: Vec::new(), aich_hash: None };
        let changes = self.shares_mut().finished(&large, busy);
        self.record(changes);
    }

    fn given_the_download_finished_at(&mut self, path: &str) {
        let file = self.dir.path().join(path);
        let download = self.download.clone().unwrap();
        let changes = self.shares_mut().finished(&file, download);
        self.record(changes);
    }

    fn when_the_aich_tree_is_requested(&mut self, name: &str) {
        let hash = self.hash_of(name);
        self.aich_tree = self.shares_mut().aich_tree(&hash);
    }

    // Both before the hasher answers.
    fn when_the_download_finishes_and_is_renamed(&mut self, name: &str, new_name: &str) {
        let busy = std::mem::replace(&mut self.busy, true);
        let done = self.incoming.join(name);
        let download = self.download.clone().unwrap();
        let changes = self.shares_mut().finished(&done, download);
        self.record(changes);
        self.when_the_file_is_renamed(name, new_name);
        self.busy = busy;
    }

    fn when_a_download_finishes_after_a_scan_queued_its_file_and_is_renamed(&mut self, new_name: &str) {
        let completed = self.write_file("completed/done.bin", bytes(600_000, 41));
        let done = self.incoming.join("done.bin");
        fs::rename(&completed, &done).unwrap();
        self.scan();
        let download = self.finished_of("done.bin", None);
        let changes = self.shares_mut().finished(&done, download);
        self.record(changes);
        self.when_the_file_is_renamed("done.bin", new_name);
    }

    fn when_a_directory_is_shared_and_its_file_goes_before_it_is_hashed(&mut self, path: &str, gone: &str) {
        let dir = self.dir.path().join(path);
        self.shares_mut().add_directory(&dir, false).unwrap();
        self.scan();
        fs::remove_file(self.dir.path().join(gone)).unwrap();
    }

    fn when_the_recovery_data_of_the_part_is_asked_for(&mut self, name: &str, part: u32) {
        let hash = self.hash_of(name);
        let master = self.shares().get(&hash).unwrap().aich_hash.unwrap();
        self.recovery = self.shares_mut().aich_recovery(&hash, part, &master);
    }

    fn then_the_same_aich_tree_is_given(&self) {
        assert!(Arc::ptr_eq(self.aich_tree.as_ref().unwrap(), self.first_aich_tree.as_ref().unwrap()));
    }

    fn then_the_aich_tree_covers_the_file(&self, name: &str) {
        assert_eq!(self.first_aich_tree.as_ref().unwrap().size(), self.contents[name].len() as u64);
    }

    // Once the hasher answered.
    fn then_the_file_gets_an_aich_tree(&mut self, name: &str) {
        self.hash_what_waits();
        self.then_the_file_has_an_aich_tree(name);
    }

    fn then_the_file_has_an_aich_tree(&mut self, name: &str) {
        let hash = self.hash_of(name);
        assert!(self.shares_mut().aich_tree(&hash).is_some(), "no AICH tree for {name}");
    }

    fn then_the_file_is_shared_as(&self, file: &str, name: &str) {
        assert_eq!(self.shares().get(&self.hash_of(file)).unwrap().path, self.incoming.join(name));
    }

    // A file that is not what was downloaded keeps no tree.
    fn then_the_file_with_the_hash_keeps_no_aich_tree(&self, hash: [u8; 16]) {
        assert_eq!(self.shares().get(&hash).unwrap().aich_hash, None);
    }

    fn then_the_aich_hash_is_the_files_own_not_the_links(&self, name: &str) {
        let own = self.shares().get(&self.hash_of(name)).unwrap().aich_hash;
        assert!(own.is_some() && own != self.link_aich_hash, "{own:?}");
    }

    fn then_none_of_these_files_is_shared(&self, names: &[&str]) {
        for file in self.shares().files() {
            assert!(!names.contains(&file.name.as_str()), "{} is shared", file.name);
        }
    }

    fn then_the_recovery_data_proves_the_part(&self, name: &str, part: u32) {
        let file = self.shares().get(&self.hash_of(name)).unwrap();
        let data = self.recovery.as_ref().expect("recovery data");
        assert!(aich_recovery::verify(file.size, part, &file.aich_hash.unwrap(), data).is_some());
    }

    fn then_none_is_given_for_another_tree(&mut self, name: &str, part: u32) {
        let hash = self.hash_of(name);
        assert_eq!(self.shares_mut().aich_recovery(&hash, part, &[0x55; 20]), None);
    }

    fn then_the_shares_report(&mut self, expected: &[&str]) {
        let reported: Vec<String> = self.events.drain(..expected.len().min(self.events.len())).collect();
        assert_eq!(reported, expected);
    }

    fn then_the_shares_report_in_any_order(&mut self, expected: &[&str]) {
        let mut reported: Vec<String> = self.events.drain(..expected.len().min(self.events.len())).collect();
        reported.sort();
        let mut expected = expected.to_vec();
        expected.sort_unstable();
        assert_eq!(reported, expected);
    }

    fn then_the_shares_report_nothing(&self) {
        assert!(self.events.is_empty(), "{:?}", self.events);
    }

    fn then_the_number_of_shared_files_is(&self, count: usize) {
        assert_eq!(self.shares().files().count(), count);
    }

    fn then_the_directory_is_private(&self) {
        assert!(self.private_directory, "{}", self.directory.display());
    }

    fn then_the_directory_is_not_private(&self) {
        assert!(!self.private_directory, "{}", self.directory.display());
    }

    fn then_every_private_directory_is_refused(&self) {
        assert!(!self.refusals.is_empty());
        for (path, refusal) in &self.refusals {
            assert!(refusal.is_err(), "{}", path.display());
        }
    }

    fn then_no_directory_is_shared(&self) {
        assert_eq!(self.shares().directories(), Vec::<PathBuf>::new());
    }

    fn then_no_tree_is_shared(&self) {
        assert_eq!(self.shares().trees(), Vec::<PathBuf>::new());
    }

    fn then_the_only_shared_tree_is(&self, path: &str) {
        assert_eq!(self.shares().trees(), [self.dir.path().join(path)]);
    }

    fn then_all_the_files_but_the_second_are_shared(&self) {
        let mut shared: Vec<String> = self.shares().files().map(|file| file.name.clone()).collect();
        shared.sort();
        let mut expected: Vec<String> = self.order.iter().filter(|name| **name != self.order[1]).cloned().collect();
        expected.sort();
        assert_eq!(shared, expected);
    }

    fn then_the_other_copy_is_shared(&self, name: &str) {
        let shared = self.shares().get(&self.hash_of(name)).expect("the other copy");
        assert_ne!(shared.path, self.tracked);
    }

    fn then_no_file_is_shared_under_the_old_hash(&self) {
        assert_eq!(self.shares().get(&self.old_hash), None);
    }

    fn then_the_config_file_is_a_directory(&self, file: &str) {
        assert!(self.config.join(file).is_dir());
    }

    fn then_the_only_shared_file_is(&self, path: &str) {
        let paths: Vec<PathBuf> = self.shares().files().map(|file| file.path.clone()).collect();
        assert_eq!(paths, [self.dir.path().join(path)]);
    }

    fn then_the_files_hashed_are(&self, count: usize) {
        assert_eq!(self.hashed, count);
    }

    fn then_the_files_found_are(&mut self, names: &[&str]) {
        self.found.sort();
        assert_eq!(self.found, names);
    }

    fn load(&mut self) {
        let mut shares = SharedFiles::load(&self.config);
        shares.set_folders(vec![self.incoming.clone()], vec![self.dir.path().join("temp")]);
        self.shares = Some(shares);
    }

    fn open(&mut self) {
        self.load();
        self.scan();
    }

    fn scan(&mut self) {
        let listing = shared_files::list(&self.shares().places());
        self.take(listing);
    }

    fn take(&mut self, listing: Listing) {
        let changes = self.shares_mut().scanned(listing);
        self.record(changes);
    }

    // What changed; then the files to hash, hashed one after the other unless the hasher is busy.
    fn record(&mut self, changes: Changes) {
        self.note(changes);
        if !self.busy {
            self.hash_what_waits();
        }
    }

    fn note(&mut self, changes: Changes) {
        for sharing in changes.sharing {
            self.events.push_back(match sharing {
                Sharing::Shared(file) => format!("shared {}", file.name),
                Sharing::Unshared(file) => format!("unshared {}", file.name),
            });
        }
        self.waiting.extend(changes.to_hash);
    }

    fn hash_what_waits(&mut self) {
        while let Some(found) = self.waiting.pop_front() {
            self.hashed += 1;
            let hashes = shared_files::hash(&found).map_err(|e| e.to_string());
            let more = self.shares_mut().hashed(found, hashes);
            self.note(more);
        }
    }

    fn finished_of(&self, name: &str, aich_hash: Option<[u8; 20]>) -> Finished {
        let data = &self.contents[name];
        Finished { hash: self.hash_of(name), size: data.len() as u64, part_hashes: Vec::new(), aich_hash }
    }

    fn shares(&self) -> &SharedFiles {
        self.shares.as_ref().unwrap()
    }

    fn shares_mut(&mut self) -> &mut SharedFiles {
        self.shares.as_mut().unwrap()
    }

    // The eD2K hash of a file's content, as the hasher gives it.
    fn hash_of(&self, name: &str) -> [u8; 16] {
        let data = &self.contents[name];
        let hashes = file_hasher::hash(&mut data.as_slice(), data.len() as u64, |_| ControlFlow::Continue(())).unwrap();
        hashes.ed2k.file_hash()
    }

    fn write_file(&mut self, path: &str, data: Vec<u8>) -> PathBuf {
        let file = self.dir.path().join(path);
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(&file, &data).unwrap();
        self.contents.insert(file.file_name().unwrap().to_string_lossy().into_owned(), data);
        file
    }
}

fn statistics(counts: [u64; 6]) -> Statistics {
    let [requests, accepted, transferred, total_requests, total_accepted, total_transferred] = counts;
    Statistics { requests, accepted, transferred, total_requests, total_accepted, total_transferred }
}

fn bytes(size: usize, seed: u64) -> Vec<u8> {
    let mut data = vec![0u8; size];
    StdRng::seed_from_u64(seed).fill(&mut data[..]);
    data
}

fn set_modified(file: &Path, time: SystemTime) {
    fs::File::options().write(true).open(file).unwrap().set_modified(time).unwrap();
}

// A junction on Windows, which needs no privilege; a symbolic link elsewhere.
fn link_folder(link: &Path, target: &Path) {
    #[cfg(windows)]
    {
        // With the separators of the system: cmd takes a slash for a switch.
        let (link, target): (PathBuf, PathBuf) = (link.components().collect(), target.components().collect());
        let made =
            std::process::Command::new("cmd").arg("/C").arg("mklink").arg("/J").arg(link).arg(target).output().unwrap();
        assert!(made.status.success(), "{}", String::from_utf8_lossy(&made.stderr));
    }
    #[cfg(unix)]
    std::os::unix::fs::symlink(target, link).unwrap();
}
