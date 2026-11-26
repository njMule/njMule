// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Format detection of IP filter lists regardless of URL name.

use njmule_engine::engine::ip_filter_lists;
use njmule_engine::persist::format::ip_filter_dat::Range;

const EMULE_LIST: &str = "001.002.003.000 - 001.002.003.255 , 000 , somebody\n";
const GUARDIAN_LIST: &str = "somebody:1.2.4.0-1.2.4.255\n";

#[test]
fn should_find_the_form_of_an_ip_filter_dat_whatever_its_name() {
    let mut lab = Lab::default();
    lab.given_the_list(EMULE_LIST);

    lab.when_the_list_is_read_from("https://example.org/ipfilter.zip");

    lab.then_the_ranges_found_are(1);
}

#[test]
fn should_find_the_form_of_a_guardian_list_whatever_its_name() {
    let mut lab = Lab::default();
    lab.given_the_list(GUARDIAN_LIST);

    lab.when_the_list_is_read_from("https://example.org/guarding.p2p.gz");

    lab.then_the_ranges_found_are(1);
}

#[test]
fn should_find_the_form_of_a_list_at_an_address_that_does_not_name_it() {
    let mut lab = Lab::default();
    lab.given_the_list(GUARDIAN_LIST);

    lab.when_the_list_is_read_from("https://example.org/download?list=level1");

    lab.then_the_ranges_found_are(1);
}

#[test]
fn should_find_no_range_in_a_page_that_is_no_list() {
    let mut lab = Lab::default();
    lab.given_the_list("<html>nothing</html>");

    lab.when_the_list_is_read_from("https://x.org/");

    lab.then_the_ranges_found_are(0);
}

#[derive(Default)]
struct Lab {
    list: Vec<u8>,
    ranges: Vec<Range>,
}

impl Lab {
    fn given_the_list(&mut self, content: &str) {
        self.list = content.as_bytes().to_vec();
    }

    fn when_the_list_is_read_from(&mut self, address: &str) {
        self.ranges = ip_filter_lists::ip_ranges(&self.list, address);
    }

    fn then_the_ranges_found_are(&self, count: usize) {
        assert_eq!(self.ranges.len(), count);
    }
}
