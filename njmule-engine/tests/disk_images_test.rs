// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The macOS tools as a test plays them, hdiutil mounting an image
//! that holds what the test says.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use njmule_engine::update::commands::{CommandResult, Commands};
use njmule_engine::update::{disk_images, swap, update_files};

#[test]
fn should_copy_the_app_out_of_the_image_and_check_its_signature() {
    let mut test = Extracting::new();

    test.when_extracted();

    test.then_no_failure_occurred();
    test.then_the_bundle_is("njMule.app");
    test.then_the_tools_ran_in_order(&["hdiutil attach", "ditto", "hdiutil detach", "codesign --verify"]);
}

#[test]
fn should_refuse_an_app_whose_signature_fails() {
    let mut test = Extracting::new();
    test.given_the_signature_fails();

    test.when_extracted();

    test.then_it_failed_with("codesign");
}

#[test]
fn should_refuse_an_image_without_the_app() {
    let mut test = Extracting::new();
    test.given_the_image_holds(&["Other.app"]);

    test.when_extracted();

    test.then_it_failed_with("no njMule.app");
    test.then_the_tools_ran_in_order(&["hdiutil attach", "hdiutil detach"]);
}

#[test]
fn should_take_the_app_beside_its_uninstaller() {
    let mut test = Extracting::new();
    test.given_the_image_holds(&["Uninstall njMule.app", "njMule.app"]);

    test.when_extracted();

    test.then_no_failure_occurred();
    test.then_the_bundle_is("njMule.app");
}

struct Extracting {
    dir: tempfile::TempDir,
    tools: Tools,
    bundle: Option<PathBuf>,
    failure: Option<io::Error>,
}

struct Tools {
    run: Mutex<Vec<String>>,
    apps: Vec<String>,
    signature: i32,
}

impl Extracting {
    fn new() -> Self {
        let tools = Tools { run: Mutex::default(), apps: vec!["njMule.app".to_owned()], signature: 0 };
        Self { dir: tempfile::tempdir().unwrap(), tools, bundle: None, failure: None }
    }

    fn given_the_signature_fails(&mut self) {
        self.tools.signature = 1;
    }

    fn given_the_image_holds(&mut self, names: &[&str]) {
        self.tools.apps = names.iter().map(|name| (*name).to_owned()).collect();
    }

    fn when_extracted(&mut self) {
        let dmg = self.dir.path().join("njmule-0.2.0-macos-arm64.dmg");
        match disk_images::extract(&dmg, &self.dir.path().join("files"), &self.tools) {
            Ok(bundle) => self.bundle = Some(bundle),
            Err(e) => self.failure = Some(e),
        }
    }

    fn then_no_failure_occurred(&self) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
    }

    fn then_it_failed_with(&self, words: &str) {
        let failure = self.failure.as_ref().expect("a failure");
        assert!(failure.to_string().contains(words), "{failure}");
    }

    fn then_the_bundle_is(&self, name: &str) {
        let bundle = self.bundle.as_ref().unwrap();
        assert_eq!(bundle, &self.dir.path().join("files").join(name));
        assert!(bundle.join("Contents").is_dir());
    }

    fn then_the_tools_ran_in_order(&self, tools: &[&str]) {
        assert_eq!(*self.tools.run.lock().unwrap(), tools);
    }
}

impl Commands for Tools {
    fn run(&self, command: &[String], _timeout: Duration) -> io::Result<CommandResult> {
        let tool = if command[0] == "ditto" { "ditto".to_owned() } else { format!("{} {}", command[0], command[1]) };
        self.run.lock().unwrap().push(tool.clone());
        match tool.as_str() {
            "hdiutil attach" => {
                let at = command.iter().position(|word| word == "-mountpoint").unwrap() + 1;
                let mount = Path::new(&command[at]);
                for app in &self.apps {
                    fs::create_dir_all(mount.join(app).join("Contents"))?;
                }
            }
            "hdiutil detach" => {
                let mount = Path::new(&command[2]);
                update_files::delete_tree(mount)?;
                fs::create_dir_all(mount)?;
            }
            "codesign --verify" => {
                let output = if self.signature == 0 { "" } else { "invalid signature" };
                return Ok(CommandResult { exit_code: self.signature, output: output.to_owned() });
            }
            _ => swap::files()(Path::new(&command[1]), Path::new(&command[2]))?,
        }
        Ok(CommandResult { exit_code: 0, output: String::new() })
    }
}
