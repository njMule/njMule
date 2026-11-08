// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's IpFilterListsTest, under its names: IP filter lists joined into one filter, and fetched again once
//! the filter is old enough. Then the form a list is read in, and lists this machine is not fetched from.

#![allow(clippy::unwrap_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::cell::RefCell;
use std::time::{Duration, SystemTime};

use njmule_engine::engine::ip_filter_lists;
use njmule_engine::net::routes::Routes;
use njmule_engine::net::web_lists::WebLists;
use njmule_engine::persist::format::ip_filter_dat::Range;

const DAY: Duration = Duration::from_secs(24 * 60 * 60);

#[test]
fn should_join_the_lists_that_could_be_read_and_tell_the_others() {
    let mut test = Lists::default();
    test.given_the_list("https://one.example/ipfilter.zip", Ok(vec![first()]));
    test.given_the_list("https://two.example/level1.p2p", Err("offline".to_owned()));
    test.given_the_list("https://three.example/empty.dat", Ok(Vec::new()));
    test.given_the_list("https://four.example/guarding.p2p", Ok(vec![second()]));

    test.when_the_lists_are_joined();

    test.then_the_ranges_are(&[first(), second()]);
    test.then_the_warnings_name(&["two.example", "three.example"]);
}

#[test]
fn should_fail_with_the_first_reason_when_no_list_could_be_read() {
    let mut test = Lists::default();
    test.given_the_list("https://one.example/ipfilter.zip", Err("offline".to_owned()));
    test.given_the_list("https://two.example/empty.dat", Ok(Vec::new()));

    test.when_the_lists_are_joined();

    test.then_the_failure_says("offline");
}

// Lists, days, how many days old the filter is (below zero for none), and whether the lists are due.
#[test]
fn should_fetch_the_lists_again_once_the_filter_is_as_old_as_they_say() {
    for (lists, days, age, expected) in
        [(0, 7, 30, false), (1, 0, 30, false), (1, 7, -1, true), (1, 7, 6, false), (1, 7, 7, true), (2, 1, 2, true)]
    {
        let mut test = Lists::default();

        test.when_it_is_asked_whether_the_lists_are_due(lists, days, age);

        test.then_they_are_due(expected);
    }
}

#[test]
fn should_read_a_packed_peer_guardian_list_by_its_name() {
    let mut test = Lists::default();
    test.given_the_text("Some ISP:1.2.3.4-1.2.3.9\n");

    test.when_it_is_read_from("http://lists.example/level1.P2P.GZ");

    test.then_the_ranges_read_are(&[(0x0102_0304, 0x0102_0309, "Some ISP")]);
}

#[test]
fn should_read_a_list_its_name_does_not_tell_as_peer_guardian_first() {
    let mut test = Lists::default();
    test.given_the_text("Some ISP:1.2.3.4-1.2.3.9\n");

    test.when_it_is_read_from("https://lists.example/download?list=1");

    test.then_the_ranges_read_are(&[(0x0102_0304, 0x0102_0309, "Some ISP")]);
}

#[test]
fn should_read_nothing_of_what_is_no_list() {
    let mut test = Lists::default();
    test.given_the_text("<html>not found</html>\n");

    test.when_it_is_read_from("http://lists.example/level1.dat");

    test.then_the_ranges_read_are(&[]);
}

#[test]
fn should_take_the_addresses_of_the_lines_that_are_not_blank() {
    let mut test = Lists::default();

    test.when_the_addresses_are_taken_from(" http://a.example/x \n\n  \nhttp://b.example/y\n");

    test.then_the_addresses_are(&["http://a.example/x", "http://b.example/y"]);
}

#[tokio::test]
async fn should_fetch_no_list_from_a_local_or_private_address() {
    let mut test = Lists::default();

    test.when_the_lists_are_fetched(&["http://127.0.0.1:9/a.dat", "http://10.0.0.1:9/b.dat"]).await;

    test.then_the_failure_says("refusing to fetch from the local or private address 127.0.0.1");
    test.then_the_warnings_name(&["127.0.0.1", "10.0.0.1"]);
}

#[derive(Default)]
struct Lists {
    lists: Vec<String>,
    fetched: Vec<Result<Vec<Range>, String>>,
    warnings: RefCell<Vec<String>>,
    joined: Option<Result<Vec<Range>, String>>,
    due: bool,
    text: Vec<u8>,
    read: Vec<Range>,
    addresses: Vec<String>,
}

impl Lists {
    fn given_the_list(&mut self, address: &str, outcome: Result<Vec<Range>, String>) {
        self.lists.push(address.to_owned());
        self.fetched.push(outcome);
    }

    fn given_the_text(&mut self, text: &str) {
        self.text = text.as_bytes().to_vec();
    }

    fn when_the_lists_are_joined(&mut self) {
        let fetched = std::mem::take(&mut self.fetched);
        let warnings = &self.warnings;
        self.joined =
            Some(ip_filter_lists::joined(&self.lists, fetched, |warning| warnings.borrow_mut().push(warning)));
    }

    fn when_it_is_asked_whether_the_lists_are_due(&mut self, lists: usize, days: u32, age: i32) {
        let addresses: Vec<String> = (0..lists).map(|i| format!("https://list{i}.example/ipfilter.zip")).collect();
        let now = SystemTime::now();
        let written = u32::try_from(age).ok().map(|age| now - DAY * age);
        self.due = ip_filter_lists::is_due(written, now, days, &addresses);
    }

    fn when_it_is_read_from(&mut self, url: &str) {
        self.read = ip_filter_lists::ip_ranges(&self.text, url);
    }

    fn when_the_addresses_are_taken_from(&mut self, text: &str) {
        self.addresses = ip_filter_lists::addresses(text);
    }

    async fn when_the_lists_are_fetched(&mut self, lists: &[&str]) {
        let lists: Vec<String> = lists.iter().map(|&list| list.to_owned()).collect();
        let web = WebLists::create(Routes::direct());
        let warnings = &self.warnings;
        self.joined =
            Some(ip_filter_lists::fetch_joined(&web, &lists, |warning| warnings.borrow_mut().push(warning)).await);
    }

    fn then_the_ranges_are(&self, ranges: &[Range]) {
        assert_eq!(self.joined.as_ref().unwrap().as_ref().unwrap(), ranges);
    }

    fn then_the_warnings_name(&self, hosts: &[&str]) {
        let warnings = self.warnings.borrow();
        assert_eq!(warnings.len(), hosts.len(), "{warnings:?}");
        for (warning, host) in warnings.iter().zip(hosts) {
            assert!(warning.contains(host), "{warning}");
        }
    }

    fn then_the_failure_says(&self, message: &str) {
        let failure = self.joined.as_ref().unwrap().as_ref().unwrap_err();
        assert!(failure.contains(message), "{failure}");
    }

    fn then_they_are_due(&self, expected: bool) {
        assert_eq!(self.due, expected);
    }

    fn then_the_ranges_read_are(&self, ranges: &[(u32, u32, &str)]) {
        let read: Vec<(u32, u32, &str)> =
            self.read.iter().map(|range| (range.start, range.end, range.description.as_str())).collect();
        assert_eq!(read, ranges);
    }

    fn then_the_addresses_are(&self, addresses: &[&str]) {
        assert_eq!(self.addresses, addresses);
    }
}

fn first() -> Range {
    Range { start: 0x0102_0300, end: 0x0102_03FF, level: 100, description: "first".to_owned() }
}

fn second() -> Range {
    Range { start: 0x0506_0700, end: 0x0506_07FF, level: 100, description: "second".to_owned() }
}
