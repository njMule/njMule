// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The Java AutostartTest, under its names: the start at login against a pretend registry and temp folders, never
//! touching the real system.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use njmule_engine::app::autostart::{Autostart, LinuxAutostart, SystemAutostart, WindowsAutostart};
use njmule_engine::app::link_association::{CommandResult, Shell};

const BOM: char = '\u{FEFF}';
const WINDOWS_PROGRAM: &str = r"C:\Program Files\njMule\njmule-gui.exe";

#[test]
fn should_offer_nothing_to_a_java_launcher() {
    let mut test = Scenario::new();
    test.given_the_program(r"C:\jdk\bin\java.exe");

    test.when_the_start_at_login_is_looked_for();

    test.then_there_is_none();
}

#[test]
fn should_write_the_run_value_with_the_program_and_its_arguments_on_windows() {
    let mut test = Scenario::new();
    test.given_the_arguments(&["--config-dir", r"D:\njMule data\"]);
    test.given_the_start_at_login();

    test.when_it_is_turned_on();

    test.then_the_imported_registry_file_contains(r"[HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run]");
    test.then_the_imported_registry_file_contains(
        r#""njMule"="\"C:\\Program Files\\njMule\\njmule-gui.exe\" \"--config-dir\" \"D:\\njMule data\\\\\"""#,
    );
    test.then_no_work_file_is_left();
}

#[test]
fn should_read_the_run_value_back_on_windows() {
    let mut test = Scenario::new();
    test.given_the_run_value(r#"\"C:\\Program Files\\njMule\\njmule-gui.exe\""#);
    test.given_the_start_at_login();

    test.when_the_system_is_asked();

    test.then_it_is_on(true);
    test.then_no_work_file_is_left();
}

#[test]
fn should_take_a_value_of_another_copy_as_off_on_windows() {
    let mut test = Scenario::new();
    test.given_the_run_value(r#"\"D:\\old\\njmule-gui.exe\""#);
    test.given_the_start_at_login();

    test.when_the_system_is_asked();

    test.then_it_is_on(false);
}

#[test]
fn should_delete_the_run_value_on_windows() {
    let mut test = Scenario::new();
    test.given_the_run_value(r#"\"C:\\Program Files\\njMule\\njmule-gui.exe\""#);
    test.given_the_start_at_login();

    test.when_it_is_turned_off();

    test.then_the_commands_include(&["reg", "delete", WindowsAutostart::KEY, "/v", "njMule", "/f"]);
}

#[test]
fn should_take_a_value_disabled_in_the_task_manager_as_off_on_windows() {
    let mut test = Scenario::new();
    test.given_the_run_value(r#"\"C:\\Program Files\\njMule\\njmule-gui.exe\""#);
    test.given_the_task_manager_flag("03");
    test.given_the_start_at_login();

    test.when_the_system_is_asked();

    test.then_it_is_on(false);
}

#[test]
fn should_take_a_value_enabled_in_the_task_manager_as_on_on_windows() {
    let mut test = Scenario::new();
    test.given_the_run_value(r#"\"C:\\Program Files\\njMule\\njmule-gui.exe\""#);
    test.given_the_task_manager_flag("02");
    test.given_the_start_at_login();

    test.when_the_system_is_asked();

    test.then_it_is_on(true);
}

#[test]
fn should_enable_again_a_value_disabled_in_the_task_manager_on_windows() {
    let mut test = Scenario::new();
    test.given_the_run_value(r#"\"C:\\Program Files\\njMule\\njmule-gui.exe\""#);
    test.given_the_task_manager_flag("03");
    test.given_the_start_at_login();

    test.when_it_is_turned_on();

    test.then_the_commands_include(&["reg", "delete", WindowsAutostart::APPROVED_KEY, "/v", "njMule", "/f"]);
}

#[test]
fn should_delete_nothing_without_a_run_value_on_windows() {
    let mut test = Scenario::new();
    test.given_the_start_at_login();

    test.when_it_is_turned_off();

    test.then_nothing_was_deleted();
}

#[test]
fn should_write_an_autostart_entry_on_linux() {
    let mut test = Scenario::new();
    test.given_linux();
    test.given_the_arguments(&["--config-dir", "/home/ann/my files/100%"]);
    test.given_the_start_at_login();

    test.when_it_is_turned_on();

    test.then_the_linux_entry_contains(r#"Exec=/usr/lib/njmule/njmule-gui --config-dir "/home/ann/my files/100%%""#);
    test.then_the_linux_entry_contains("X-GNOME-Autostart-enabled=true");
    test.then_the_linux_entry_contains("TryExec=/usr/lib/njmule/njmule-gui");
    test.then_it_is_on_now(true);
}

#[test]
fn should_use_the_default_config_folder_without_xdg_config_home() {
    let mut test = Scenario::new();
    test.given_linux();
    test.given_no_xdg_config_home();
    test.given_the_start_at_login();

    test.when_it_is_turned_on();

    test.then_the_file_exists(test.dir.path().join("home/.config/autostart/org.njmule.njMule.desktop"));
}

#[test]
fn should_take_a_hidden_entry_as_off_on_linux() {
    let mut test = Scenario::new();
    test.given_linux();
    test.given_the_start_at_login();
    test.given_it_was_turned_on();
    test.given_the_user_hid_the_entry();

    test.when_the_system_is_asked();

    test.then_it_is_on(false);
}

#[test]
fn should_follow_an_entry_removed_elsewhere_on_linux() {
    let mut test = Scenario::new();
    test.given_linux();
    test.given_the_start_at_login();
    test.given_it_was_turned_on();
    test.given_the_user_removed_the_entry();

    test.when_the_system_is_asked();

    test.then_it_is_on(false);
}

#[test]
fn should_escape_the_reserved_characters_of_an_exec_line() {
    let cases = [
        ("plain", "plain"),
        ("a b", "\"a b\""),
        ("say \"$hi\"", "\"say \\\\\"\\\\$hi\\\\\"\""),
        ("C:\\x", "\"C:\\\\\\\\x\""),
        ("", "\"\""),
    ];
    for (argument, expected) in cases {
        let mut test = Scenario::new();

        test.when_the_exec_argument_is_written_for(argument);

        test.then_the_exec_argument_is(expected);
    }
}

#[test]
fn should_write_a_launch_agent_on_mac_os() {
    let mut test = Scenario::new();
    test.given_mac_os();
    test.given_the_arguments(&["--config-dir", "/Users/ann/a&b"]);
    test.given_the_start_at_login();

    test.when_it_is_turned_on();

    test.then_the_launch_agent_contains("<string>/Applications/njMule.app/Contents/MacOS/njmule-gui</string>");
    test.then_the_launch_agent_contains("<string>/Users/ann/a&amp;b</string>");
    test.then_the_launch_agent_contains("<key>RunAtLoad</key>\n    <true/>");
    test.then_it_is_on_now(true);
}

#[test]
fn should_remove_the_launch_agent_on_mac_os() {
    let mut test = Scenario::new();
    test.given_mac_os();
    test.given_the_start_at_login();
    test.given_it_was_turned_on();

    test.when_it_is_turned_off();

    test.then_it_is_on_now(false);
}

#[test]
fn should_refuse_control_characters_in_autostart_arguments() {
    let dir = tempfile::tempdir().unwrap();
    let registry = Arc::new(PretendRegistry::default());
    let outcome = SystemAutostart::for_program(
        dir.path().to_path_buf(),
        registry,
        "/usr/lib/njmule/njmule-gui",
        &["bad\narg".to_owned()],
        "Linux",
        None,
        "/home/ann",
    );
    assert!(outcome.is_err());
}

#[derive(Default)]
struct RegistryState {
    run_value: Option<String>,
    approved_flag: Option<String>,
    commands: Vec<Vec<String>>,
    imported: Vec<String>,
}

#[derive(Default)]
struct PretendRegistry {
    state: Mutex<RegistryState>,
}

impl PretendRegistry {
    fn run_export(run_value: Option<&str>) -> Option<String> {
        run_value.map(|value| {
            format!(
                "Windows Registry Editor Version 5.00\r\n\r\n\
                 [HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n\
                 \"Other\"=\"x.exe\"\r\n\
                 \"njMule\"=\"{value}\"\r\n"
            )
        })
    }

    fn approved_export(flag: Option<&str>) -> Option<String> {
        flag.map(|flag| {
            format!(
                "Windows Registry Editor Version 5.00\r\n\r\n\
                 [HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run]\r\n\
                 \"Other\"=hex:02,00,00,00,00,00,00,00,00,00,00,00\r\n\
                 \"njMule\"=hex:{flag},00,00,00,a1,b2,c3,d4,e5,f6,07,18\r\n"
            )
        })
    }
}

impl Shell for PretendRegistry {
    fn run(&self, command: &[String]) -> io::Result<CommandResult> {
        let mut state = self.state.lock().unwrap();
        state.commands.push(command.to_vec());
        let tool = command[1].as_str();
        if tool == "import" {
            let text = read_utf16le_exact(Path::new(&command[2]))?;
            state.imported.push(text);
        } else if tool == "export" {
            let approved = command[2] == WindowsAutostart::APPROVED_KEY;
            let exported = if approved {
                Self::approved_export(state.approved_flag.as_deref())
            } else {
                Self::run_export(state.run_value.as_deref())
            };
            let Some(exported) = exported else {
                return Ok(CommandResult { exit: 1, output: "ERROR".to_owned() });
            };
            write_utf16le_exact(Path::new(&command[3]), &format!("{BOM}{exported}"))?;
        }
        Ok(CommandResult { exit: 0, output: String::new() })
    }
}

fn read_utf16le_exact(path: &Path) -> io::Result<String> {
    let bytes = fs::read(path)?;
    let (decoded, _) = encoding_rs::UTF_16LE.decode_without_bom_handling(&bytes);
    Ok(decoded.into_owned())
}

fn write_utf16le_exact(path: &Path, text: &str) -> io::Result<()> {
    let mut bytes = Vec::with_capacity(text.len() * 2);
    for unit in text.encode_utf16() {
        bytes.extend_from_slice(&unit.to_le_bytes());
    }
    fs::write(path, bytes)
}

struct Scenario {
    dir: tempfile::TempDir,
    program: String,
    arguments: Vec<String>,
    os: String,
    xdg_config_home: String,
    registry: Arc<PretendRegistry>,
    autostart: Option<SystemAutostart>,
    on: bool,
    exec_argument: String,
}

impl Scenario {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            program: WINDOWS_PROGRAM.to_owned(),
            arguments: Vec::new(),
            os: "Windows 11".to_owned(),
            xdg_config_home: String::new(),
            registry: Arc::new(PretendRegistry::default()),
            autostart: None,
            on: false,
            exec_argument: String::new(),
        }
    }

    fn given_the_program(&mut self, path: &str) {
        path.clone_into(&mut self.program);
    }

    fn given_the_arguments(&mut self, values: &[&str]) {
        self.arguments = values.iter().map(|value| (*value).to_owned()).collect();
    }

    fn given_linux(&mut self) {
        self.os = "Linux".to_owned();
        self.program = "/usr/lib/njmule/njmule-gui".to_owned();
        self.xdg_config_home = self.dir.path().join("xdg").to_string_lossy().into_owned();
    }

    fn given_no_xdg_config_home(&mut self) {
        self.xdg_config_home.clear();
    }

    fn given_mac_os(&mut self) {
        self.os = "Mac OS X".to_owned();
        self.program = "/Applications/njMule.app/Contents/MacOS/njmule-gui".to_owned();
    }

    fn given_the_run_value(&self, exported: &str) {
        self.registry.state.lock().unwrap().run_value = Some(exported.to_owned());
    }

    fn given_the_task_manager_flag(&self, first_byte: &str) {
        self.registry.state.lock().unwrap().approved_flag = Some(first_byte.to_owned());
    }

    fn given_the_start_at_login(&mut self) {
        self.when_the_start_at_login_is_looked_for();
    }

    fn given_it_was_turned_on(&self) {
        self.when_it_is_turned_on();
    }

    fn given_the_user_hid_the_entry(&self) {
        let entry = self.linux_entry();
        let before = fs::read_to_string(&entry).unwrap();
        fs::write(&entry, format!("{before}Hidden=true\n")).unwrap();
    }

    fn given_the_user_removed_the_entry(&self) {
        fs::remove_file(self.linux_entry()).unwrap();
    }

    fn when_the_start_at_login_is_looked_for(&mut self) {
        self.autostart = SystemAutostart::for_program(
            self.dir.path().to_path_buf(),
            Arc::clone(&self.registry) as Arc<dyn Shell>,
            &self.program,
            &self.arguments,
            &self.os,
            Some(&self.xdg_config_home),
            &self.dir.path().join("home").to_string_lossy(),
        )
        .unwrap();
    }

    fn when_it_is_turned_on(&self) {
        self.autostart.as_ref().unwrap().turn_on().unwrap();
    }

    fn when_it_is_turned_off(&self) {
        self.autostart.as_ref().unwrap().turn_off().unwrap();
    }

    fn when_the_system_is_asked(&mut self) {
        self.on = self.autostart.as_ref().unwrap().is_on().unwrap();
    }

    fn when_the_exec_argument_is_written_for(&mut self, argument: &str) {
        self.exec_argument = LinuxAutostart::exec_argument(argument);
    }

    fn then_there_is_none(&self) {
        assert!(self.autostart.is_none());
    }

    fn then_it_is_on(&self, expected: bool) {
        assert_eq!(self.on, expected);
    }

    fn then_it_is_on_now(&self, expected: bool) {
        assert_eq!(self.autostart.as_ref().unwrap().is_on().unwrap(), expected);
    }

    fn then_the_imported_registry_file_contains(&self, text: &str) {
        let state = self.registry.state.lock().unwrap();
        assert_eq!(state.imported.len(), 1);
        assert!(state.imported[0].contains(text), "missing {text:?} in {:?}", state.imported[0]);
    }

    fn then_the_commands_include(&self, expected: &[&str]) {
        let expected: Vec<String> = expected.iter().map(|part| (*part).to_owned()).collect();
        let state = self.registry.state.lock().unwrap();
        assert!(state.commands.contains(&expected), "missing {expected:?} in {:?}", state.commands);
    }

    fn then_nothing_was_deleted(&self) {
        let state = self.registry.state.lock().unwrap();
        assert!(state.commands.iter().all(|command| command[1] != "delete"));
    }

    fn then_no_work_file_is_left(&self) {
        let entries: Vec<_> = fs::read_dir(self.dir.path()).unwrap().collect();
        assert!(entries.is_empty(), "left behind: {entries:?}");
    }

    fn then_the_linux_entry_contains(&self, line: &str) {
        let content = fs::read_to_string(self.linux_entry()).unwrap();
        assert!(content.lines().any(|candidate| candidate == line), "missing {line:?} in:\n{content}");
    }

    fn then_the_file_exists(&self, file: PathBuf) {
        assert!(file.is_file(), "not a regular file: {}", file.display());
    }

    fn then_the_exec_argument_is(&self, expected: &str) {
        assert_eq!(self.exec_argument, expected);
    }

    fn then_the_launch_agent_contains(&self, text: &str) {
        let agent = self.dir.path().join("home/Library/LaunchAgents/org.njmule.njMule.plist");
        let content = fs::read_to_string(agent).unwrap();
        assert!(content.contains(text), "missing {text:?} in:\n{content}");
    }

    fn linux_entry(&self) -> PathBuf {
        self.dir.path().join("xdg/autostart/org.njmule.njMule.desktop")
    }
}
