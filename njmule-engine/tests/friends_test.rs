// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's FriendsTest, under its names: our friends in `emfriends.met`.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;

use njmule_engine::api::friend::Friend;
use njmule_engine::server::server_list::Address;
use njmule_engine::social::friends::{self, Friends};

const PAL: [u8; 16] = [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF];
const OTHER: [u8; 16] =
    [0xFE, 0xDC, 0xBA, 0x98, 0x76, 0x54, 0x32, 0x10, 0xFE, 0xDC, 0xBA, 0x98, 0x76, 0x54, 0x32, 0x10];

#[test]
fn should_keep_the_known_name_and_address_when_a_friend_is_added_again_without_them() {
    let mut test = FriendsOfAProfile::new();
    test.given_the_friends_of_the_profile();
    test.given_the_friend(friend(PAL, "pal", Some("10.0.0.7:4662")));

    test.when_the_friend_is_added(friend(PAL, "", None));

    test.then_the_friends_are(&[friend(PAL, "pal", Some("10.0.0.7:4662"))]);
    test.then_the_saved_friends_are(&[friend(PAL, "pal", Some("10.0.0.7:4662"))]);
}

#[test]
fn should_replace_the_old_name_and_address_with_new_ones() {
    let mut test = FriendsOfAProfile::new();
    test.given_the_friends_of_the_profile();
    test.given_the_friend(friend(PAL, "pal", Some("10.0.0.7:4662")));

    test.when_the_friend_is_added(friend(PAL, "new pal", Some("10.0.0.8:4663")));

    test.then_the_friends_are(&[friend(PAL, "new pal", Some("10.0.0.8:4663"))]);
}

#[test]
fn should_remove_a_friend_and_keep_the_others_in_order() {
    let mut test = FriendsOfAProfile::new();
    test.given_the_friends_of_the_profile();
    test.given_the_friend(friend(PAL, "pal", None));
    test.given_the_friend(friend(OTHER, "other", None));
    test.given_the_friend_is_known(&PAL);

    test.when_the_friend_is_removed(&PAL);

    test.then_the_friend_is_gone();
    test.then_the_friend_is_unknown(&PAL);
    test.then_the_saved_friends_are(&[friend(OTHER, "other", None)]);
}

#[test]
fn should_not_remove_a_friend_twice() {
    let mut test = FriendsOfAProfile::new();
    test.given_the_friends_of_the_profile();
    test.given_the_friend(friend(PAL, "pal", None));
    test.given_the_friend(friend(OTHER, "other", None));
    test.given_the_friend_was_removed(&PAL);

    test.when_the_friend_is_removed(&PAL);

    test.then_no_friend_is_removed();
}

#[test]
fn should_count_a_broken_file_as_no_friends() {
    let mut test = FriendsOfAProfile::new();
    test.given_a_broken_friends_file();

    test.when_the_friends_of_the_profile_are_loaded();

    test.then_there_are_no_friends();
}

struct FriendsOfAProfile {
    dir: tempfile::TempDir,
    friends: Option<Friends>,
    removed: bool,
}

impl FriendsOfAProfile {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), friends: None, removed: false }
    }

    fn given_the_friends_of_the_profile(&mut self) {
        self.when_the_friends_of_the_profile_are_loaded();
    }

    fn given_the_friend(&mut self, friend: Friend) {
        self.when_the_friend_is_added(friend);
    }

    fn given_the_friend_is_known(&self, user_hash: &[u8; 16]) {
        assert!(self.friends().contains(user_hash));
    }

    fn given_the_friend_was_removed(&mut self, user_hash: &[u8; 16]) {
        assert!(self.friends_mut().remove(user_hash).unwrap());
    }

    fn given_a_broken_friends_file(&self) {
        fs::write(self.dir.path().join(friends::FILE_NAME), [0x0E, 5, 0, 0, 0, 1]).unwrap();
    }

    fn when_the_friend_is_added(&mut self, friend: Friend) {
        self.friends_mut().add(&friend).unwrap();
    }

    fn when_the_friend_is_removed(&mut self, user_hash: &[u8; 16]) {
        self.removed = self.friends_mut().remove(user_hash).unwrap();
    }

    fn when_the_friends_of_the_profile_are_loaded(&mut self) {
        self.friends = Some(Friends::load(self.dir.path()));
    }

    fn then_the_friends_are(&self, expected: &[Friend]) {
        assert_eq!(self.friends().all(), expected);
    }

    fn then_the_saved_friends_are(&self, expected: &[Friend]) {
        assert_eq!(Friends::load(self.dir.path()).all(), expected);
    }

    fn then_the_friend_is_gone(&self) {
        assert!(self.removed);
    }

    fn then_the_friend_is_unknown(&self, user_hash: &[u8; 16]) {
        assert!(!self.friends().contains(user_hash));
    }

    fn then_no_friend_is_removed(&self) {
        assert!(!self.removed);
    }

    fn then_there_are_no_friends(&self) {
        assert_eq!(self.friends().all(), Vec::new());
    }

    fn friends(&self) -> &Friends {
        self.friends.as_ref().expect("the friends loaded")
    }

    fn friends_mut(&mut self) -> &mut Friends {
        self.friends.as_mut().expect("the friends loaded")
    }
}

fn friend(user_hash: [u8; 16], name: &str, address: Option<&str>) -> Friend {
    Friend::new(user_hash, name, address.map(|address| Address::parse(address).unwrap()))
}
