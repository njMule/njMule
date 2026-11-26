// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the uninstaller's `--restore-links` does against a pretend
//! registry, never touching the real one.

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

use njmule_engine::app::autostart::Autostart;
use njmule_engine::app::launch_options::{Action, LaunchOptions, UsageError};
use njmule_engine::app::link_association::{CommandResult, LinkAssociation, Shell, WindowsLinks};
use njmule_engine::app::restore_links::{self, EXIT_FAILURE, EXIT_OK};

const PROGRAM: &str = r"C:\Users\Ann\AppData\Local\Programs\njMule\njmule-gui.exe";
const EMULE: &str = r#""C:\Program Files\eMule\emule.exe" "%1""#;

fn ours() -> String {
    format!("\"{PROGRAM}\" \"%1\"")
}

#[test]
fn should_give_the_links_back_to_the_handler_saved_before() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(&ours());
    test.given_the_saved_handler_is(EMULE);

    test.when_the_links_are_restored();

    test.then_the_links_open_in(EMULE);
    test.then_the_saved_handler_is_gone();
    test.then_the_exit_code_is(EXIT_OK);
}

#[test]
fn should_stop_starting_at_login_as_the_uninstallers_ask() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(EMULE);
    test.given_it_starts_at_login();

    test.when_the_links_are_restored();

    test.then_it_starts_at_login(false);
    test.then_the_output_says("no longer starts when you log in");
    test.then_the_exit_code_is(EXIT_OK);
}

#[test]
fn should_remove_our_handler_when_none_was_saved() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(&ours());

    test.when_the_links_are_restored();

    test.then_no_program_opens_the_links();
    test.then_the_exit_code_is(EXIT_OK);
}

#[test]
fn should_leave_the_links_of_another_program() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(EMULE);
    test.given_the_saved_handler_is(r#""C:\old\client.exe" "%1""#);

    test.when_the_links_are_restored();

    test.then_the_links_open_in(EMULE);
    test.then_nothing_was_imported();
    test.then_the_output_says("left as they are");
}

#[test]
fn should_remove_our_handler_without_a_configuration_directory() {
    let mut test = Scenario::new();
    test.given_no_configuration_directory();
    test.given_the_links_open_in(&ours());

    test.when_the_links_are_restored();

    test.then_no_program_opens_the_links();
    test.then_the_configuration_directory_still_does_not_exist();
    test.then_the_exit_code_is(EXIT_OK);
}

#[test]
fn should_do_nothing_where_this_program_cannot_open_links() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(&ours());
    test.given_a_program_that_cannot_open_links();

    test.when_the_links_are_restored();

    test.then_the_links_open_in(&ours());
    test.then_the_output_says("nothing to give back");
    test.then_the_exit_code_is(EXIT_OK);
}

#[test]
fn should_tell_when_the_registry_refuses_the_change() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(&ours());
    test.given_the_registry_refuses_imports();

    test.when_the_links_are_restored();

    test.then_the_exit_code_is(EXIT_FAILURE);
    test.then_the_errors_say("Cannot give the ed2k links back");
}

#[test]
fn should_tell_when_the_start_at_login_cannot_be_turned_off() {
    let mut test = Scenario::new();
    test.given_the_configuration_directory();
    test.given_the_links_open_in(&ours());
    test.given_it_starts_at_login();
    test.given_the_start_at_login_cannot_be_turned_off();

    test.when_the_links_are_restored();

    test.then_the_exit_code_is(EXIT_FAILURE);
    test.then_the_errors_say("Cannot stop njMule from starting at login: access denied");
}

#[test]
fn should_read_the_option_with_a_configuration_directory() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_command_line_is(&["--restore-links", "--config-dir", &dir]);

    test.then_the_action_is(Action::RestoreLinks);
}

#[test]
fn should_refuse_the_option_beside_another_action() {
    let mut test = Scenario::new();

    test.when_the_command_line_is(&["--restore-links", "--exit"]);

    test.then_the_command_line_is_refused("cannot be combined");
}

#[test]
fn should_refuse_the_option_beside_links() {
    let mut test = Scenario::new();

    test.when_the_command_line_is(&["--restore-links", "ed2k://|server|1.2.3.4|4661|/"]);

    test.then_the_command_line_is_refused("cannot be combined with links");
}

#[derive(Default)]
struct AutostartState {
    on: bool,
    refuses_to_turn_off: bool,
}

#[derive(Clone, Default)]
struct PretendAutostart(Arc<Mutex<AutostartState>>);

impl Autostart for PretendAutostart {
    fn is_on(&self) -> io::Result<bool> {
        Ok(self.0.lock().unwrap().on)
    }

    fn turn_on(&self) -> io::Result<()> {
        self.0.lock().unwrap().on = true;
        Ok(())
    }

    fn turn_off(&self) -> io::Result<()> {
        let mut state = self.0.lock().unwrap();
        if state.refuses_to_turn_off {
            return Err(io::Error::other("access denied"));
        }
        state.on = false;
        Ok(())
    }
}

#[derive(Default)]
struct RegistryState {
    command: Option<String>,
    refuse_imports: bool,
    imported: Vec<String>,
}

#[derive(Default)]
struct PretendRegistry(Mutex<RegistryState>);

impl Shell for PretendRegistry {
    fn run(&self, args: &[String]) -> io::Result<CommandResult> {
        let mut state = self.0.lock().unwrap();
        match args[1].as_str() {
            "query" => Ok(CommandResult { exit: i32::from(state.command.is_none()), output: String::new() }),
            "export" => {
                let Some(command) = state.command.clone() else {
                    return Ok(CommandResult { exit: 1, output: "not found".to_owned() });
                };
                let text = format!(
                    "\u{FEFF}Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\x]\r\n@={}\r\n",
                    WindowsLinks::reg_string(&command)
                );
                let mut bytes = Vec::with_capacity(text.len() * 2);
                for unit in text.encode_utf16() {
                    bytes.extend_from_slice(&unit.to_le_bytes());
                }
                fs::write(&args[3], bytes)?;
                Ok(CommandResult { exit: 0, output: String::new() })
            }
            "import" => {
                if state.refuse_imports {
                    return Ok(CommandResult { exit: 1, output: "access denied".to_owned() });
                }
                let file = Path::new(&args[2]);
                let backup = file.file_name().is_some_and(|name| name == WindowsLinks::BACKUP);
                let body = if backup {
                    fs::read_to_string(file)?
                } else {
                    let bytes = fs::read(file)?;
                    encoding_rs::UTF_16LE.decode_without_bom_handling(&bytes).0.into_owned()
                };
                state.imported.push(body.clone());
                state.command = if backup {
                    Some(body)
                } else if body.contains("[-HKEY") {
                    None
                } else {
                    Some(ours())
                };
                Ok(CommandResult { exit: 0, output: String::new() })
            }
            other => Err(io::Error::other(format!("unexpected {other}"))),
        }
    }
}

struct Scenario {
    dir: tempfile::TempDir,
    registry: Arc<PretendRegistry>,
    autostart: PretendAutostart,
    config: PathBuf,
    desktop_program: bool,
    out: Vec<u8>,
    err: Vec<u8>,
    exit: i32,
    options: Option<LaunchOptions>,
    failure: Option<UsageError>,
}

impl Scenario {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        let config = dir.path().join("config");
        Self {
            dir,
            registry: Arc::new(PretendRegistry::default()),
            autostart: PretendAutostart::default(),
            config,
            desktop_program: true,
            out: Vec::new(),
            err: Vec::new(),
            exit: -1,
            options: None,
            failure: None,
        }
    }

    fn given_the_configuration_directory(&mut self) {
        self.config = self.dir.path().join("config");
        fs::create_dir_all(&self.config).unwrap();
    }

    fn given_no_configuration_directory(&mut self) {
        self.config = self.dir.path().join("never-created");
    }

    fn given_the_links_open_in(&self, command: &str) {
        self.registry.0.lock().unwrap().command = Some(command.to_owned());
    }

    fn given_the_saved_handler_is(&self, command: &str) {
        fs::write(self.config.join(WindowsLinks::BACKUP), command).unwrap();
    }

    fn given_it_starts_at_login(&self) {
        self.autostart.turn_on().unwrap();
    }

    fn given_the_start_at_login_cannot_be_turned_off(&self) {
        self.autostart.0.lock().unwrap().refuses_to_turn_off = true;
    }

    fn given_a_program_that_cannot_open_links(&mut self) {
        self.desktop_program = false;
    }

    fn given_the_registry_refuses_imports(&self) {
        self.registry.0.lock().unwrap().refuse_imports = true;
    }

    fn when_the_links_are_restored(&mut self) {
        let desktop_program = self.desktop_program;
        let registry = Arc::clone(&self.registry);
        let autostart = self.autostart.clone();
        self.exit = restore_links::restore(
            &self.config,
            |config_dir| {
                desktop_program.then(|| {
                    LinkAssociation::Windows(WindowsLinks::new(
                        config_dir.to_path_buf(),
                        Arc::clone(&registry) as Arc<dyn Shell>,
                        PathBuf::from(PROGRAM),
                    ))
                })
            },
            |_| Some(Box::new(autostart.clone()) as Box<dyn Autostart>),
            &mut self.out,
            &mut self.err,
        );
    }

    fn when_the_command_line_is(&mut self, args: &[&str]) {
        match LaunchOptions::parse(args) {
            Ok(options) => self.options = Some(options),
            Err(error) => self.failure = Some(error),
        }
    }

    fn then_the_links_open_in(&self, command: &str) {
        assert_eq!(self.registry.0.lock().unwrap().command.as_deref(), Some(command));
    }

    fn then_no_program_opens_the_links(&self) {
        assert!(self.registry.0.lock().unwrap().command.is_none());
    }

    fn then_the_saved_handler_is_gone(&self) {
        assert!(!self.config.join(WindowsLinks::BACKUP).exists());
    }

    fn then_nothing_was_imported(&self) {
        assert!(self.registry.0.lock().unwrap().imported.is_empty());
    }

    fn then_the_configuration_directory_still_does_not_exist(&self) {
        assert!(!self.config.exists());
    }

    fn then_it_starts_at_login(&self, expected: bool) {
        assert_eq!(self.autostart.is_on().unwrap(), expected);
    }

    fn then_the_exit_code_is(&self, expected: i32) {
        assert_eq!(self.exit, expected);
    }

    fn then_the_output_says(&self, text: &str) {
        let out = String::from_utf8_lossy(&self.out);
        assert!(out.contains(text), "missing {text:?} in {out:?}");
    }

    fn then_the_errors_say(&self, text: &str) {
        let err = String::from_utf8_lossy(&self.err);
        assert!(err.contains(text), "missing {text:?} in {err:?}");
    }

    fn then_the_action_is(&self, expected: Action) {
        assert!(self.failure.is_none(), "{:?}", self.failure);
        assert_eq!(self.options.as_ref().unwrap().action, expected);
    }

    fn then_the_command_line_is_refused(&self, reason: &str) {
        let message = &self.failure.as_ref().expect("expected usage error").0;
        assert!(message.contains(reason), "missing {reason:?} in {message:?}");
    }
}
