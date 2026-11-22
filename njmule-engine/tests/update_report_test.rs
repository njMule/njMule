// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `org.njmule.app.UpdateReportTest`.

use njmule_engine::api::install_kind::InstallKind;
use njmule_engine::app::update_report::UpdateReport;

const RELEASE: &str = "https://github.com/njMule/njMule/releases/download/v0.2.0/";

#[test]
fn should_tell_how_to_install_the_new_version_by_the_way_this_one_was_installed() {
    let cases = [
        (
            InstallKind::Msi,
            "njmule-0.2.0-windows-x64.msi",
            format!("Download and install {RELEASE}njmule-0.2.0-windows-x64.msi"),
        ),
        (InstallKind::Deb, "njmule_0.2.0_amd64.deb", "sudo apt install ./njmule_0.2.0_amd64.deb".to_owned()),
        (InstallKind::Rpm, "njmule-0.2.0-1.x86_64.rpm", "sudo dnf install ./njmule-0.2.0-1.x86_64.rpm".to_owned()),
        (InstallKind::Synology, "njmule-0.2.0-synology-x86_64.spk", "Package Center with Manual Install".to_owned()),
        (InstallKind::Docker, "njmule-0.2.0-linux-x64.tar.xz", "docker compose pull".to_owned()),
    ];
    for (installation, file, advice) in cases {
        let mut test = Scenario::new();
        test.given_a_new_version_for(installation, file);

        test.when_printed();

        test.then_the_output_says(&["njMule 0.2.0 is available (this is 0.1.0).", &advice]);
    }
}

#[test]
fn should_send_a_copy_built_from_the_sources_to_the_page_of_the_release() {
    let mut test = Scenario::new();
    test.given_a_new_version_without_package();

    test.when_printed();

    test.then_the_output_says(&["Download and install https://github.com/njMule/njMule/releases/tag/v0.2.0"]);
}

#[test]
fn should_say_that_this_is_the_newest_version() {
    let mut test = Scenario::new();
    test.given_no_newer_version(None);

    test.when_printed();

    test.then_the_output_says(&["njMule 0.1.0 is the newest version."]);
}

#[test]
fn should_say_that_it_has_not_looked_yet() {
    let mut test = Scenario::new();
    test.given_no_check_yet();

    test.when_printed();

    test.then_the_output_says(&["njMule 0.1.0 has not looked for a new version yet."]);
}

#[test]
fn should_tell_why_the_check_failed() {
    let mut test = Scenario::new();
    test.given_no_newer_version(Some("HTTP 503 from github.com"));

    test.when_printed();

    test.then_the_errors_say("Cannot check for a new version: HTTP 503 from github.com");
}

struct Scenario {
    out: Vec<u8>,
    err: Vec<u8>,
    report: Option<UpdateReport>,
}

impl Scenario {
    fn new() -> Self {
        Self { out: Vec::new(), err: Vec::new(), report: None }
    }

    fn given_a_new_version_for(&mut self, installation: InstallKind, file: &str) {
        self.report = Some(UpdateReport {
            current: "0.1.0".to_owned(),
            installation,
            checked: true,
            failure: None,
            available: Some("0.2.0".to_owned()),
            page: Some("https://github.com/njMule/njMule/releases/tag/v0.2.0".to_owned()),
            download: Some(format!("{RELEASE}{file}")),
        });
    }

    fn given_a_new_version_without_package(&mut self) {
        self.report = Some(UpdateReport {
            current: "0.1.0".to_owned(),
            installation: InstallKind::Unknown,
            checked: true,
            failure: None,
            available: Some("0.2.0".to_owned()),
            page: Some("https://github.com/njMule/njMule/releases/tag/v0.2.0".to_owned()),
            download: None,
        });
    }

    fn given_no_newer_version(&mut self, failure: Option<&str>) {
        self.report = Some(UpdateReport {
            current: "0.1.0".to_owned(),
            installation: InstallKind::Msi,
            checked: true,
            failure: failure.map(str::to_owned),
            available: None,
            page: None,
            download: None,
        });
    }

    fn given_no_check_yet(&mut self) {
        self.report = Some(UpdateReport {
            current: "0.1.0".to_owned(),
            installation: InstallKind::Msi,
            checked: false,
            failure: None,
            available: None,
            page: None,
            download: None,
        });
    }

    fn when_printed(&mut self) {
        if let Some(report) = &self.report {
            report.print(&mut self.out, &mut self.err);
        }
    }

    fn then_the_output_says(&self, texts: &[&str]) {
        let out = String::from_utf8_lossy(&self.out);
        for text in texts {
            assert!(out.contains(text), "missing {text:?} in:\n{out}");
        }
    }

    fn then_the_errors_say(&self, text: &str) {
        let err = String::from_utf8_lossy(&self.err);
        assert!(err.contains(text), "missing {text:?} in:\n{err}");
    }
}
