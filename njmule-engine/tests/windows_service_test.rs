// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Twin of Java's `org.njmule.app.WindowsServiceTest`.

#![allow(clippy::unwrap_used, clippy::indexing_slicing)]

use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use njmule_engine::app::launch_options::LaunchOptions;
use njmule_engine::app::web_ui_password::PasswordSource;
use njmule_engine::app::windows_service::{self, Windows};
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::remote::web_ui_settings::{self, WebUiSettings};
use tempfile::TempDir;
use zeroize::Zeroizing;

const PASSWORD: &str = "correct horse";
const KEPT_HASH: &str = "pbkdf2-sha512$1$AA$AA";

#[test]
fn should_install_the_service_with_its_profile_its_rights_and_a_firewall_rule() {
    let mut test = Scenario::new();
    test.given_a_password_file();
    let file = test.password_file.to_string_lossy().into_owned();

    test.when_run(&["--service=install", "--webui-password-file", &file]);

    test.then_the_exit_code_is(windows_service::EXIT_OK);
    let expected = vec![
        vec![test.host(), "status".to_owned()],
        test.closing_the_profile(),
        vec![test.host(), "install".to_owned()],
        vec![
            "icacls".to_owned(),
            test.profile().to_string_lossy().into_owned(),
            "/grant".to_owned(),
            r"NT SERVICE\njMule:(OI)(CI)M".to_owned(),
            "/T".to_owned(),
            "/Q".to_owned(),
        ],
        vec![
            "icacls".to_owned(),
            test.app().to_string_lossy().into_owned(),
            "/grant".to_owned(),
            r"NT SERVICE\njMule:(OI)(CI)RX".to_owned(),
            "/T".to_owned(),
            "/Q".to_owned(),
        ],
        vec![
            "netsh".to_owned(),
            "advfirewall".to_owned(),
            "firewall".to_owned(),
            "delete".to_owned(),
            "rule".to_owned(),
            "name=njMule (service)".to_owned(),
        ],
        vec![
            "netsh".to_owned(),
            "advfirewall".to_owned(),
            "firewall".to_owned(),
            "add".to_owned(),
            "rule".to_owned(),
            "name=njMule (service)".to_owned(),
            "dir=in".to_owned(),
            "action=allow".to_owned(),
            format!("program={}", test.program.display()),
            "enable=yes".to_owned(),
            "profile=private,domain".to_owned(),
        ],
        vec![test.host(), "start".to_owned()],
    ];
    test.then_the_commands_were(&expected);
    test.then_the_profile_has_a_web_interface_password();
    test.then_it_said("http://localhost:4711/");
}

#[test]
fn should_close_the_profile_to_other_users_before_writing_the_password() {
    let mut test = Scenario::new();
    test.given_a_password_file();
    let file = test.password_file.to_string_lossy().into_owned();

    test.when_run(&["--service=install", "--webui-password-file", &file]);

    test.then_the_profile_was_closed_without_a_password_in_it();
    test.then_the_profile_has_a_web_interface_password();
}

#[test]
fn should_restart_a_service_that_runs_so_that_it_takes_its_new_settings() {
    let mut test = Scenario::new();
    test.given_a_password_file();
    test.given_the_service_is(4);
    let file = test.password_file.to_string_lossy().into_owned();

    test.when_run(&["--service=install", "--webui-password-file", &file]);

    test.then_the_exit_code_is(windows_service::EXIT_OK);
    test.then_the_service_was_stopped_and_started_again();
}

#[test]
fn should_change_the_web_interface_of_the_service_and_keep_its_password() {
    let mut test = Scenario::new();
    test.given_the_profile_has_a_web_interface_password();

    test.when_run(&["--service=install", "--webui-lan"]);

    test.then_the_exit_code_is(windows_service::EXIT_OK);
    test.then_no_password_was_asked();
    test.then_the_profile_keeps_its_password();
    test.then_the_web_interface_is_open_to_other_computers();
}

#[test]
fn should_ask_for_the_rights_with_the_web_interface_options_alone() {
    let mut test = Scenario::new();
    test.given_no_administrators_rights();

    test.when_run(&["--service=install", "--webui-lan"]);

    test.then_it_asked_for_the_rights_with(&["--service=install", "--webui-lan", "--service-output"]);
}

#[test]
fn should_ask_for_the_web_interface_password_of_a_new_profile() {
    let mut test = Scenario::new();

    test.when_run(&["--service=install"]);

    test.then_the_exit_code_is(windows_service::EXIT_OK);
    test.then_the_profile_has_a_web_interface_password();
}

#[test]
fn should_ask_for_the_administrators_rights_and_show_what_the_service_said() {
    let mut test = Scenario::new();
    test.given_no_administrators_rights();

    test.when_run(&["--service=install", "--webui-lan", "--webui-password-file", "password.txt"]);

    let expected_path = std::path::absolute(Path::new("password.txt")).unwrap().to_string_lossy().into_owned();
    test.then_the_exit_code_is(windows_service::EXIT_OK);
    test.then_it_asked_for_the_rights_with(&[
        "--service=install",
        "--webui-lan",
        "--webui-password-file",
        &expected_path,
    ]);
    test.then_it_said("The service njMule runs.");
    test.then_the_commands_were(&[]);
}

#[test]
fn should_change_nothing_when_the_rights_are_refused() {
    let mut test = Scenario::new();
    test.given_no_administrators_rights();
    test.given_the_rights_are_refused();

    test.when_run(&["--service=start"]);

    test.then_the_exit_code_is(windows_service::EXIT_FAILURE);
    test.then_it_complained("did not get the administrator's rights");
}

#[test]
fn should_remove_the_service_and_keep_its_profile() {
    let mut test = Scenario::new();
    test.given_the_service_is(4);

    test.when_run(&["--service=uninstall"]);

    test.then_the_exit_code_is(windows_service::EXIT_OK);
    let expected = vec![
        vec![test.host(), "status".to_owned()],
        vec![test.host(), "stop".to_owned()],
        vec![
            "icacls".to_owned(),
            test.app().to_string_lossy().into_owned(),
            "/remove".to_owned(),
            r"NT SERVICE\njMule".to_owned(),
            "/T".to_owned(),
            "/Q".to_owned(),
        ],
        vec![
            "icacls".to_owned(),
            test.profile().to_string_lossy().into_owned(),
            "/remove".to_owned(),
            r"NT SERVICE\njMule".to_owned(),
            "/T".to_owned(),
            "/Q".to_owned(),
        ],
        vec![test.host(), "uninstall".to_owned()],
        vec![
            "netsh".to_owned(),
            "advfirewall".to_owned(),
            "firewall".to_owned(),
            "delete".to_owned(),
            "rule".to_owned(),
            "name=njMule (service)".to_owned(),
        ],
    ];
    test.then_the_commands_were(&expected);
    let profile_text = format!("its profile stays in {}", test.profile().display());
    test.then_it_said(&profile_text);
}

#[test]
fn should_keep_the_service_and_its_rights_when_it_does_not_stop() {
    let mut test = Scenario::new();
    test.given_the_service_is(4);
    test.given_the_service_fails_to("stop", 1053);

    test.when_run(&["--service=uninstall"]);

    test.then_the_exit_code_is(windows_service::EXIT_FAILURE);
    test.then_it_complained("Windows error 1053");
    let expected = vec![vec![test.host(), "status".to_owned()], vec![test.host(), "stop".to_owned()]];
    test.then_the_commands_were(&expected);
}

#[test]
fn should_tell_that_the_service_is_not_installed() {
    let mut test = Scenario::new();
    test.given_the_service_is(1060);

    test.when_run(&["--service=status"]);

    test.then_the_exit_code_is(windows_service::EXIT_FAILURE);
    test.then_it_said("is not installed");
}

#[test]
fn should_report_a_windows_error_of_a_step() {
    let mut test = Scenario::new();
    test.given_the_service_fails_to("stop", 1053);

    test.when_run(&["--service=stop"]);

    test.then_the_exit_code_is(windows_service::EXIT_FAILURE);
    test.then_it_complained("Windows error 1053");
}

#[test]
fn should_leave_other_systems_to_their_own_services() {
    let mut test = Scenario::new();
    test.given_another_system();

    test.when_run(&["--service=install"]);

    test.then_the_exit_code_is(windows_service::EXIT_USAGE);
    test.then_it_complained("systemd");
}

#[test]
fn should_quote_an_argument_as_the_c_runtime_of_windows_reads_it() {
    let cases = [
        ("--service=start", "--service=start"),
        (r"C:\Program Files\njMule\a.txt", r#""C:\Program Files\njMule\a.txt""#),
        (r"C:\a b\", r#""C:\a b\\""#),
        (r#"say "hi""#, r#""say \"hi\"""#),
    ];
    for (arg, expected) in cases {
        let mut test = Scenario::new();

        test.when_the_command_line_is_made_of(arg);

        test.then_the_command_line_is(expected);
    }
}

struct FakePasswordSource {
    asked: bool,
}

impl PasswordSource for FakePasswordSource {
    fn read(&mut self, _prompt: &str) -> io::Result<Option<Zeroizing<String>>> {
        self.asked = true;
        Ok(Some(Zeroizing::new(PASSWORD.to_owned())))
    }
}

struct FakeWindows {
    dir: PathBuf,
    program: PathBuf,
    windows: bool,
    elevated: bool,
    elevated_says: String,
    elevated_args: Vec<String>,
    commands: Vec<Vec<String>>,
    answers: HashMap<String, i32>,
    settings_when_closed: bool,
}

impl Windows for FakeWindows {
    fn applies(&self) -> bool {
        self.windows
    }

    fn elevated(&mut self) -> io::Result<bool> {
        Ok(self.elevated)
    }

    fn program(&self) -> Option<PathBuf> {
        Some(self.program.clone())
    }

    fn program_data(&self) -> PathBuf {
        self.dir.join("ProgramData")
    }

    fn own_folder(&self) -> io::Result<PathBuf> {
        Ok(self.dir.join("own"))
    }

    fn run(&mut self, command: &[String]) -> io::Result<i32> {
        self.commands.push(command.to_vec());
        if command.iter().any(|arg| arg == "/inheritance:r") {
            let config = AppDirectories::under_root(&self.dir.join("ProgramData").join("njMule")).unwrap().config;
            self.settings_when_closed = config.join(web_ui_settings::FILE_NAME).exists();
        }
        let ours = command.first().is_some_and(|first| first.ends_with(windows_service::HOST));
        Ok(if ours { self.answers.get(&command[1]).copied().unwrap_or(0) } else { 0 })
    }

    fn run_elevated(&mut self, _program: &Path, args: &[String]) -> io::Result<i32> {
        self.elevated_args = args.to_vec();
        let pos = args.iter().position(|arg| arg == "--service-output").unwrap();
        let output = PathBuf::from(&args[pos.saturating_add(1)]);
        fs::write(&output, &self.elevated_says)?;
        Ok(if self.elevated_says.trim().is_empty() { 1 } else { 0 })
    }
}

struct Scenario {
    dir: TempDir,
    program: PathBuf,
    password_file: PathBuf,
    system: FakeWindows,
    out: Vec<u8>,
    err: Vec<u8>,
    asked: bool,
    code: i32,
    line: String,
}

impl Scenario {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let app = dir.path().join("app");
        fs::create_dir_all(&app).unwrap();
        let program = app.join("njmule.exe");
        fs::write(&program, b"").unwrap();
        fs::write(app.join(windows_service::HOST), b"").unwrap();
        let system = FakeWindows {
            dir: dir.path().to_path_buf(),
            program: program.clone(),
            windows: true,
            elevated: true,
            elevated_says: "The service njMule runs.\n".to_owned(),
            elevated_args: Vec::new(),
            commands: Vec::new(),
            answers: HashMap::new(),
            settings_when_closed: true,
        };
        let password_file = dir.path().join("password.txt");
        Self {
            dir,
            program,
            password_file,
            system,
            out: Vec::new(),
            err: Vec::new(),
            asked: false,
            code: -1,
            line: String::new(),
        }
    }

    fn app(&self) -> PathBuf {
        self.program.parent().unwrap().to_path_buf()
    }

    fn host(&self) -> String {
        self.app().join(windows_service::HOST).to_string_lossy().into_owned()
    }

    fn profile(&self) -> PathBuf {
        self.dir.path().join("ProgramData").join("njMule")
    }

    fn closing_the_profile(&self) -> Vec<String> {
        vec![
            "icacls".to_owned(),
            self.profile().to_string_lossy().into_owned(),
            "/inheritance:r".to_owned(),
            "/grant:r".to_owned(),
            "*S-1-5-18:(OI)(CI)F".to_owned(),
            "*S-1-5-32-544:(OI)(CI)F".to_owned(),
            "/Q".to_owned(),
        ]
    }

    fn given_a_password_file(&mut self) {
        self.password_file = self.dir.path().join("password.txt");
        fs::write(&self.password_file, format!("{PASSWORD}\n")).unwrap();
    }

    fn given_the_profile_has_a_web_interface_password(&self) {
        let config = AppDirectories::under_root(&self.profile()).unwrap().config;
        WebUiSettings {
            enabled: true,
            port: 4711,
            lan: false,
            password_hash: KEPT_HASH.to_owned(),
            api: false,
            ..WebUiSettings::default()
        }
        .save(&config)
        .unwrap();
    }

    fn given_no_administrators_rights(&mut self) {
        self.system.elevated = false;
    }

    fn given_the_rights_are_refused(&mut self) {
        self.system.elevated_says.clear();
    }

    fn given_the_service_is(&mut self, state: i32) {
        self.system.answers.insert("status".to_owned(), state);
    }

    fn given_the_service_fails_to(&mut self, verb: &str, error: i32) {
        self.system.answers.insert(verb.to_owned(), error);
    }

    fn given_another_system(&mut self) {
        self.system.windows = false;
    }

    fn when_run(&mut self, args: &[&str]) {
        let cli = LaunchOptions::parse(args).unwrap();
        let mut passwords = FakePasswordSource { asked: false };
        self.code = windows_service::run(&mut self.system, &cli, &mut self.out, &mut self.err, &mut passwords);
        self.asked = passwords.asked;
    }

    fn when_the_command_line_is_made_of(&mut self, arg: &str) {
        self.line = windows_service::command_line(&[arg.to_owned()]);
    }

    fn then_the_exit_code_is(&self, expected: i32) {
        assert_eq!(self.code, expected);
    }

    fn then_the_commands_were(&self, expected: &[Vec<String>]) {
        assert_eq!(self.system.commands, expected);
    }

    fn then_the_profile_was_closed_without_a_password_in_it(&self) {
        assert!(self.system.commands.contains(&self.closing_the_profile()));
        assert!(!self.system.settings_when_closed);
    }

    fn then_the_service_was_stopped_and_started_again(&self) {
        let len = self.system.commands.len();
        assert_eq!(
            &self.system.commands[len.saturating_sub(2)..len],
            &[vec![self.host(), "stop".to_owned()], vec![self.host(), "start".to_owned()]]
        );
    }

    fn then_no_password_was_asked(&self) {
        assert!(!self.asked);
    }

    fn then_the_profile_keeps_its_password(&self) {
        let config = AppDirectories::under_root(&self.profile()).unwrap().config;
        assert_eq!(WebUiSettings::load(&config).password_hash, KEPT_HASH);
    }

    fn then_the_web_interface_is_open_to_other_computers(&self) {
        let config = AppDirectories::under_root(&self.profile()).unwrap().config;
        assert!(WebUiSettings::load(&config).lan);
    }

    fn then_the_profile_has_a_web_interface_password(&self) {
        let config = AppDirectories::under_root(&self.profile()).unwrap().config;
        assert!(!WebUiSettings::load(&config).password_hash.is_empty());
    }

    fn then_it_asked_for_the_rights_with(&self, prefix: &[&str]) {
        assert!(
            self.system.elevated_args.len() >= prefix.len()
                && self.system.elevated_args[..prefix.len()].iter().map(String::as_str).eq(prefix.iter().copied()),
            "args were {:?}",
            self.system.elevated_args
        );
        assert!(self.system.elevated_args.iter().any(|arg| arg == "--service-output"));
    }

    fn then_it_said(&self, part: &str) {
        let out = String::from_utf8_lossy(&self.out);
        assert!(out.contains(part), "missing {part:?} in:\n{out}");
    }

    fn then_it_complained(&self, part: &str) {
        let err = String::from_utf8_lossy(&self.err);
        assert!(err.contains(part), "missing {part:?} in:\n{err}");
    }

    fn then_the_command_line_is(&self, expected: &str) {
        assert_eq!(self.line, expected);
    }
}
