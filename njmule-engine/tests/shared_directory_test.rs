// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The examples of the Java engine's SharedDirectoryTest: which folders deserve a second thought before they are
//! shared.

#![allow(clippy::unwrap_used)]

use std::fs;
use std::path::{Path, PathBuf};

use njmule_engine::api::shared_directory::SharedDirectory;

#[test]
fn should_take_the_root_for_sensitive() {
    let mut check = Check::new();

    check.when_the_root_of_the_home_is_checked();

    check.then_it_is_sensitive();
}

#[test]
fn should_take_the_personal_documents_for_sensitive() {
    let mut check = Check::new();

    check.when_the_folder_of_the_home_is_checked(&["Documents"]);

    check.then_it_is_sensitive();
}

#[test]
fn should_take_a_folder_inside_the_desktop_for_sensitive() {
    let mut check = Check::new();

    check.when_the_folder_of_the_home_is_checked(&["Desktop", "photos"]);

    check.then_it_is_sensitive();
}

#[test]
fn should_not_take_the_personal_music_for_sensitive() {
    let mut check = Check::new();

    check.when_the_folder_of_the_home_is_checked(&["Music"]);

    check.then_it_is_not_sensitive();
}

#[test]
fn should_take_a_system_directory_for_sensitive() {
    let mut check = Check::new();

    check.when_the_system_directory_is_checked();

    check.then_it_is_sensitive();
}

#[test]
fn should_take_the_home_for_sensitive() {
    let mut check = Check::new();

    check.when_the_home_is_checked();

    check.then_it_is_sensitive();
}

#[test]
fn should_take_the_directory_of_all_homes_for_sensitive() {
    let mut check = Check::new();

    check.when_the_directory_of_all_homes_is_checked();

    check.then_it_is_sensitive();
}

#[test]
fn should_take_the_ssh_keys_for_sensitive() {
    let mut check = Check::new();

    check.when_the_folder_of_the_home_is_checked(&[".ssh"]);

    check.then_it_is_sensitive();
}

#[test]
fn should_take_a_folder_inside_the_gnupg_keys_for_sensitive() {
    let mut check = Check::new();

    check.when_the_folder_of_the_home_is_checked(&[".gnupg", "private-keys-v1.d"]);

    check.then_it_is_sensitive();
}

#[test]
fn should_take_a_directory_containing_a_sensitive_one_for_sensitive() {
    for directory in ["home", ""] {
        let mut check = Check::new();
        check.given_the_directories(&["home/Documents", "home/Music"]);
        check.given_the_sensitive_directories(&["home/Documents"]);

        check.when_the_directory_is_checked(directory);

        check.then_it_is_sensitive();
    }
}

#[test]
fn should_take_a_directory_inside_a_sensitive_one_for_sensitive() {
    let mut check = Check::new();
    check.given_the_directories(&["home/Documents", "home/Music"]);
    check.given_the_sensitive_directories(&["home/Documents"]);

    check.when_the_directory_is_checked("home/Documents/taxes");

    check.then_it_is_sensitive();
}

#[test]
fn should_not_take_a_directory_beside_a_sensitive_one_for_sensitive() {
    let mut check = Check::new();
    check.given_the_directories(&["home/Documents", "home/Music"]);
    check.given_the_sensitive_directories(&["home/Documents"]);

    check.when_the_directory_is_checked("home/Music");

    check.then_it_is_not_sensitive();
}

#[test]
fn should_not_take_a_sibling_with_the_same_prefix_for_sensitive() {
    let mut check = Check::new();
    check.given_the_directories(&["home/Documents", "home/Music"]);
    check.given_the_sensitive_directories(&["home/Documents"]);

    check.when_the_directory_is_checked("home2");

    check.then_it_is_not_sensitive();
}

#[test]
fn should_judge_a_link_by_where_it_leads() {
    let mut check = Check::new();
    check.given_the_directories(&["home/Documents", "share"]);
    check.given_a_link_to("share/docs", "home/Documents");
    check.given_the_sensitive_directories(&["home/Documents"]);

    check.when_the_directory_is_checked("share/docs");

    check.then_it_is_sensitive();
}

#[test]
fn should_not_take_the_directory_beside_a_sensitive_link_for_sensitive() {
    let mut check = Check::new();
    check.given_the_directories(&["home/Documents", "share"]);
    check.given_a_link_to("share/docs", "home/Documents");
    check.given_the_sensitive_directories(&["share/docs"]);

    check.when_the_directory_is_checked("share/other");

    check.then_it_is_not_sensitive();
}

#[test]
fn should_take_where_a_sensitive_link_leads_for_sensitive() {
    let mut check = Check::new();
    check.given_the_directories(&["home/Documents", "share"]);
    check.given_a_link_to("share/docs", "home/Documents");
    check.given_the_sensitive_directories(&["share/docs"]);

    check.when_the_directory_is_checked("home/Documents");

    check.then_it_is_sensitive();
}

struct Check {
    dir: tempfile::TempDir,
    sensitive_directories: Vec<PathBuf>,
    sensitive: bool,
}

impl Check {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), sensitive_directories: Vec::new(), sensitive: false }
    }

    fn given_the_directories(&self, relatives: &[&str]) {
        for relative in relatives {
            fs::create_dir_all(self.dir.path().join(relative)).unwrap();
        }
    }

    fn given_a_link_to(&self, link: &str, target: &str) {
        link_folder(&self.dir.path().join(link), &self.dir.path().join(target));
    }

    fn given_the_sensitive_directories(&mut self, relatives: &[&str]) {
        self.sensitive_directories = relatives.iter().map(|relative| self.dir.path().join(relative)).collect();
    }

    fn when_the_root_of_the_home_is_checked(&mut self) {
        self.sensitive = SharedDirectory::is_sensitive(home().ancestors().last().unwrap());
    }

    fn when_the_home_is_checked(&mut self) {
        self.sensitive = SharedDirectory::is_sensitive(&home());
    }

    fn when_the_directory_of_all_homes_is_checked(&mut self) {
        self.sensitive = SharedDirectory::is_sensitive(home().parent().unwrap());
    }

    fn when_the_folder_of_the_home_is_checked(&mut self, names: &[&str]) {
        let folder = names.iter().fold(home(), |folder, name| folder.join(name));
        self.sensitive = SharedDirectory::is_sensitive(&folder);
    }

    fn when_the_system_directory_is_checked(&mut self) {
        let system = std::env::var_os("SystemRoot")
            .map_or_else(|| PathBuf::from("/etc"), |root| Path::new(&root).join("System32"));
        self.sensitive = SharedDirectory::is_sensitive(&system);
    }

    fn when_the_directory_is_checked(&mut self, relative: &str) {
        self.sensitive =
            SharedDirectory::is_sensitive_among(&self.dir.path().join(relative), &self.sensitive_directories);
    }

    fn then_it_is_sensitive(&self) {
        assert!(self.sensitive);
    }

    fn then_it_is_not_sensitive(&self) {
        assert!(!self.sensitive);
    }
}

fn home() -> PathBuf {
    dirs::home_dir().unwrap()
}

// A symbolic link; on Windows a junction, which needs no privilege.
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
