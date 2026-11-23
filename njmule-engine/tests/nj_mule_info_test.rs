// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java engine's NjMuleInfoTest, under its names: version, startup notice and bundled legal texts.

use njmule_engine::api::nj_mule_info;

#[test]
fn should_give_a_filtered_semantic_version() {
    let mut info = Info::default();

    info.when_the_version_is_read();

    info.then_the_version_is_semver();
    info.then_the_text_is_not("0.0.0-unknown");
}

#[test]
fn should_follow_the_gpl_recommendation_in_the_startup_notice() {
    let mut info = Info::default();

    info.when_the_startup_notice_is_made_with_the_hints("--warranty", "--license");

    info.then_the_text_contains(&["njMule", "New Jack Mule", nj_mule_info::COPYRIGHT]);
    info.then_the_text_contains(&["ABSOLUTELY NO WARRANTY", "'--warranty'", "'--license'"]);
}

#[test]
fn should_bundle_the_license() {
    let mut info = Info::default();

    info.when_the_license_is_read();

    info.then_the_text_contains(&["GNU GENERAL PUBLIC LICENSE", "Version 3"]);
}

#[test]
fn should_bundle_the_notice() {
    let mut info = Info::default();

    info.when_the_notice_is_read();

    info.then_the_text_contains(&["Third-party components"]);
}

#[test]
fn should_give_sections_fifteen_and_sixteen_as_the_warranty_text() {
    let mut info = Info::default();

    info.when_the_warranty_is_read();

    info.then_the_text_starts_with("15. Disclaimer of Warranty.");
    info.then_the_text_contains(&["16. Limitation of Liability."]);
    info.then_the_text_does_not_contain("17. Interpretation");
}

#[derive(Default)]
struct Info {
    text: String,
}

impl Info {
    fn when_the_version_is_read(&mut self) {
        self.text = nj_mule_info::version().to_owned();
    }

    fn when_the_startup_notice_is_made_with_the_hints(&mut self, warranty_hint: &str, license_hint: &str) {
        self.text = nj_mule_info::startup_notice(warranty_hint, license_hint);
    }

    fn when_the_license_is_read(&mut self) {
        self.text = nj_mule_info::license_text().to_owned();
    }

    fn when_the_notice_is_read(&mut self) {
        self.text = nj_mule_info::notice_text().to_owned();
    }

    fn when_the_warranty_is_read(&mut self) {
        self.text = nj_mule_info::warranty_text().to_owned();
    }

    fn then_the_version_is_semver(&self) {
        assert!(semver::Version::parse(&self.text).is_ok(), "not semver: {}", self.text);
    }

    fn then_the_text_is_not(&self, unexpected: &str) {
        assert_ne!(self.text, unexpected);
    }

    fn then_the_text_contains(&self, parts: &[&str]) {
        for part in parts {
            assert!(self.text.contains(part), "expected {:?} in {:?}", part, self.text);
        }
    }

    fn then_the_text_starts_with(&self, prefix: &str) {
        assert!(self.text.starts_with(prefix), "expected prefix {:?} in {:?}", prefix, self.text);
    }

    fn then_the_text_does_not_contain(&self, part: &str) {
        assert!(!self.text.contains(part), "unexpected {:?} in {:?}", part, self.text);
    }
}
