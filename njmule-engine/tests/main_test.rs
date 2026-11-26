// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! End-to-end checks against the compiled `njmule` executable.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::indexing_slicing, clippy::arithmetic_side_effects)]

use std::fs;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use njmule_engine::api::nj_mule_info;
use njmule_engine::app::link_handoff::{self, LinkHandoff};
use njmule_engine::config::app_directories::AppDirectories;
use njmule_engine::engine::Engine;
use njmule_engine::engine::switches::Switches;
use njmule_engine::remote::passwords;
use njmule_engine::remote::web_ui_settings::{self, WebUiSettings};

use crate::free_port;

const EXIT_OK: i32 = 0;
const EXIT_FAILURE: i32 = 1;
const EXIT_USAGE: i32 = 2;
const PASSWORD_FILE: &str = "webui-password";
const SERVER_LIST: &str = "ed2k://|serverlist|http://127.0.0.1:9/server.met|/";
const NODES_LIST: &str = "ed2k://|nodeslist|http://127.0.0.1:9/nodes.dat|/";
const SEARCH: &str = "ed2k://|search|linux|/";

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_njmule")
}

#[test]
fn should_print_the_version() {
    let mut lab = Lab::new();

    lab.when_run_with(&["--version"]);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[nj_mule_info::version(), "GPL-3.0-or-later"]);
}

#[test]
fn should_print_the_text_an_option_asks_for() {
    let cases = [
        ("--license", "GNU GENERAL PUBLIC LICENSE"),
        ("--warranty", "Disclaimer of Warranty"),
        ("--notice", "Third-party components"),
        ("--help", "--config-dir"),
    ];
    for (option, text) in cases {
        let mut lab = Lab::new();

        lab.when_run_with(&[option]);

        lab.then_the_exit_code_is(EXIT_OK);
        lab.then_the_output_contains(&[text]);
    }
}

#[test]
fn should_exit_with_the_usage_on_invalid_arguments() {
    let mut lab = Lab::new();

    lab.when_run_with(&["--nope"]);

    lab.then_the_exit_code_is(EXIT_USAGE);
    lab.then_the_errors_contain(&["Unknown option", "Usage:"]);
}

#[test]
fn should_print_the_link_of_a_file_to_hash() {
    let mut lab = Lab::new();
    lab.given_the_file("abc.txt", "abc");

    lab.when_the_file_is_hashed("abc.txt");

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[
        "ed2k://|file|abc.txt|3|A448017AAF21D8525FC10AE87AA6729D|h=VGMT4NSHA2AWVOR6EVYXQUGCNSONBWE5|/",
        "3 bytes, 0 part hashes",
    ]);
}

#[test]
fn should_fail_to_hash_a_missing_file() {
    let mut lab = Lab::new();

    lab.when_the_file_is_hashed("missing");

    lab.then_the_exit_code_is(EXIT_FAILURE);
    lab.then_the_errors_contain(&["Cannot hash"]);
}

#[test]
fn should_start_the_engine_in_the_given_directory_and_stop() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_written("config/njmule.properties");

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_file_exists("config/njmule.properties");
    lab.then_a_log_file_exists();
    lab.then_the_output_contains(&["ABSOLUTELY NO WARRANTY"]);
}

#[test]
fn should_hand_the_links_of_a_second_start_to_the_running_one() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_written(&handoff_path());
    let link = format!("ed2k://|file|handed.iso|1000|{}|/", "cd".repeat(16));
    lab.given_a_second_start_handed_its_links_over(&["--config-dir", &lab.dir_str(), &link]);
    lab.given_the_instance_has_written("temp/001.part.met");

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_file_exists("temp/001.part.met");
    lab.then_the_file_does_not_exist(&handoff_path());
}

#[test]
fn should_stop_nothing_for_a_link_to_an_unusable_list_address() {
    let mut lab = Lab::new();
    let file_link = format!("ed2k://|file|after.iso|1000|{}|/", "ef".repeat(16));
    lab.given_an_instance_started_with_control(&[
        "--config-dir",
        &lab.dir_str(),
        "ed2k://|serverlist|http://|/",
        "ed2k://|nodeslist|http://a b/nodes.dat|/",
        &file_link,
    ]);
    lab.given_the_instance_has_written("temp/001.part.met");

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_errors_contain(&["Cannot follow ed2k://|serverlist|http://|/", "Cannot follow ed2k://|nodeslist|"]);
    lab.then_the_file_exists("temp/001.part.met");
}

#[tokio::test]
async fn should_add_the_server_of_a_server_link() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str(), "ed2k://|server|1.2.3.4|4661|/"]);
    lab.given_the_instance_has_saved_the_server_within(Duration::from_secs(10));

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_servers_of_the_next_start_include("1.2.3.4:4661").await;
}

#[test]
fn should_tell_on_exit_that_no_instance_runs() {
    let mut lab = Lab::new();

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "--exit"]);

    lab.then_the_exit_code_is_not_zero();
    lab.then_the_errors_contain(&["No njMule runs"]);
}

#[test]
fn should_take_a_damaged_handoff_file_for_no_instance_on_exit() {
    let mut lab = Lab::new();
    lab.given_the_file(&handoff_path(), &format!("70000 {}", "ab".repeat(32)));

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "--exit"]);

    lab.then_the_exit_code_is_not_zero();
}

#[tokio::test]
async fn should_ask_the_running_one_to_stop_on_exit() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_without_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_written(&handoff_path());

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "--exit"]);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_another_instance_can_start_at_once().await;
    lab.then_the_output_contains(&["has exited"]);
    lab.then_the_background_instance_ended_with(EXIT_OK);
}

#[tokio::test]
async fn should_take_an_instance_for_gone_once_its_engine_is_closed() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = AppDirectories::resolve_current(Some(dir.path())).unwrap();
    let config = dirs.config.clone();
    let engine = Engine::start_with(dirs, Switches::of(&[])).await.unwrap();
    assert!(!LinkHandoff::await_gone(&config, Duration::from_millis(300)));

    engine.stop().await;

    assert!(LinkHandoff::await_gone(&config, Duration::from_millis(300)));
}

#[test]
fn should_refuse_a_web_interface_password_too_short() {
    let mut lab = Lab::new();

    lab.when_run_with_input("short\nshort\n", &["--config-dir", &lab.dir_str(), "--webui-password"]);

    lab.then_the_exit_code_is(EXIT_USAGE);
    lab.then_the_errors_contain(&[&format!("at least {}", passwords::MIN_LENGTH)]);
}

#[test]
fn should_refuse_web_interface_passwords_that_differ() {
    let mut lab = Lab::new();

    lab.when_run_with_input("long enough\ndifferent\n", &["--config-dir", &lab.dir_str(), "--webui-password"]);

    lab.then_the_exit_code_is(EXIT_USAGE);
    lab.then_the_errors_contain(&["differ"]);
}

#[test]
fn should_refuse_a_web_interface_password_not_typed() {
    let mut lab = Lab::new();

    lab.when_run_with_input("", &["--config-dir", &lab.dir_str(), "--webui-password"]);

    lab.then_the_exit_code_is(EXIT_USAGE);
}

#[test]
fn should_refuse_a_web_interface_password_typed_once() {
    let mut lab = Lab::new();

    lab.when_run_with_input("long enough\n", &["--config-dir", &lab.dir_str(), "--webui-password"]);

    lab.then_the_exit_code_is(EXIT_USAGE);
    lab.then_the_errors_contain(&[&format!("at least {}", passwords::MIN_LENGTH)]);
}

#[test]
fn should_set_the_web_interface_password() {
    let mut lab = Lab::new();
    let port = free_port::free_port();

    lab.when_run_with_input(
        "long enough\nlong enough\n",
        &["--config-dir", &lab.dir_str(), "--webui-password", "--webui-port", &port.to_string()],
    );

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[&format!("http://localhost:{port}/")]);
    lab.then_the_web_interface_file_contains(&["enabled=true", "pbkdf2-sha512$"]);
    lab.then_the_web_interface_file_does_not_contain("long enough");
}

#[test]
fn should_set_no_web_interface_password_that_cannot_be_saved() {
    let mut lab = Lab::new();
    lab.given_the_file("config", "a file where the directory goes");

    lab.when_run_with_input("long enough\nlong enough\n", &["--config-dir", &lab.dir_str(), "--webui-password"]);

    lab.then_the_exit_code_is(EXIT_FAILURE);
    lab.then_the_errors_contain(&["Cannot set the web interface password"]);
}

#[tokio::test]
async fn should_start_the_web_interface_with_the_password_set() {
    let mut lab = Lab::new();
    let port = free_port::free_port();
    lab.when_run_with_input(
        "long enough\nlong enough\n",
        &["--config-dir", &lab.dir_str(), "--webui-password", "--webui-port", &port.to_string()],
    );
    lab.then_the_exit_code_is(EXIT_OK);

    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_printed("Web interface on port");
    let response = crate::web_client::exchange(port, &crate::web_client::request(port, "GET", "/login", &[], "")).await;

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    assert_eq!(response.status, 200);
    assert!(String::from_utf8_lossy(&response.body).contains("type=\"password\""));
}

#[test]
fn should_report_an_unreadable_web_interface_file() {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join(web_ui_settings::FILE_NAME), "enabled=true\nport=99999\n").unwrap();

    let settings = WebUiSettings::load(dir.path());

    assert_eq!(settings, WebUiSettings::default());
}

#[test]
fn should_end_main_quietly_after_an_action_that_succeeded() {
    let mut lab = Lab::new();

    lab.when_run_with(&["--version"]);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[nj_mule_info::version()]);
}

#[test]
fn should_read_the_password_from_standard_input_without_a_terminal() {
    let mut lab = Lab::new();

    lab.when_run_with_input(
        "long enough\nlong enough\n",
        &["--config-dir", &lab.dir_str(), "--webui-password", "--webui-lan"],
    );

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&["Web interface password: ", "The same password again: ", "open to other computers"]);
    assert!(WebUiSettings::load(&lab.dir.path().join("config")).lan);
}

#[test]
fn should_refuse_a_password_whose_input_ends_before_the_second_one() {
    let mut lab = Lab::new();

    lab.when_run_with_input("long enough\n", &["--config-dir", &lab.dir_str(), "--webui-password"]);

    lab.then_the_exit_code_is(EXIT_USAGE);
}

#[test]
fn should_set_the_web_interface_password_of_a_file_without_asking() {
    let mut lab = Lab::new();
    let port = free_port::free_port();
    lab.given_the_file(PASSWORD_FILE, "long enough\n");
    let pw_path = lab.dir.path().join(PASSWORD_FILE).to_string_lossy().into_owned();

    lab.when_run_with(&[
        "--config-dir",
        &lab.dir_str(),
        "--webui-password",
        "--webui-password-file",
        &pw_path,
        "--webui-port",
        &port.to_string(),
    ]);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[&format!("http://localhost:{port}/")]);
    lab.then_the_output_does_not_contain("Web interface password: ");
    lab.then_the_web_interface_password_is("long enough");
}

#[test]
fn should_start_the_web_interface_with_the_password_of_its_file() {
    let mut lab = Lab::new();
    let port = free_port::free_port();
    lab.given_the_file(PASSWORD_FILE, "long enough\n");
    let pw_path = lab.dir.path().join(PASSWORD_FILE).to_string_lossy().into_owned();

    lab.given_an_instance_started_with_control(&[
        "--config-dir",
        &lab.dir_str(),
        "--webui-password-file",
        &pw_path,
        "--webui-port",
        &port.to_string(),
    ]);
    lab.given_the_instance_has_printed(&format!("Web interface on port {port}"));

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[&format!("Web interface on port {port}")]);
    lab.then_the_web_interface_password_is("long enough");
    lab.then_the_web_interface_file_does_not_contain("long enough");
}

#[test]
fn should_not_start_without_its_web_interface_password_file() {
    let mut lab = Lab::new();
    let pw_path = lab.dir.path().join(PASSWORD_FILE).to_string_lossy().into_owned();

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "--webui-password-file", &pw_path]);

    lab.then_the_exit_code_is(EXIT_USAGE);
    lab.then_the_errors_contain(&[&format!("{PASSWORD_FILE} does not exist")]);
    lab.then_the_file_does_not_exist("config/njmule.properties");
}

#[test]
fn should_tell_why_it_cannot_look_for_a_new_version() {
    let mut lab = Lab::new();
    lab.given_a_profile_without_networks();

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "-Dnjmule.update.disabled=true", "--check-update"]);

    lab.then_the_exit_code_is(EXIT_FAILURE);
    lab.then_the_errors_contain(&["Cannot check for a new version: update checks are turned off"]);
}

#[test]
fn should_tell_why_it_cannot_update() {
    let mut lab = Lab::new();
    lab.given_a_profile_without_networks();

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "-Dnjmule.update.disabled=true", "--update"]);

    lab.then_the_exit_code_is(EXIT_FAILURE);
    lab.then_the_errors_contain(&["Cannot check for a new version: update checks are turned off"]);
}

#[test]
fn should_fail_an_installation_without_a_plan() {
    let mut lab = Lab::new();
    let plan = lab.dir.path().join("apply.properties").to_string_lossy().into_owned();

    lab.when_run_with(&["--apply-update", &plan]);

    lab.then_the_exit_code_is(EXIT_FAILURE);
}

#[test]
fn should_report_an_engine_that_cannot_start() {
    let mut lab = Lab::new();
    lab.given_the_file("config", "a file where the directory goes");

    lab.when_run_with(&["--config-dir", &lab.dir_str()]);

    lab.then_the_exit_code_is(EXIT_FAILURE);
    lab.then_the_errors_contain(&[&format!("Cannot start {}", nj_mule_info::NAME)]);
    lab.then_the_output_contains(&["ABSOLUTELY NO WARRANTY"]);
}

#[test]
fn should_stop_the_engine_when_a_network_task_fails() {
    let mut lab = Lab::new();
    let missing = lab.dir.path().join("missing.met").to_string_lossy().into_owned();

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "--add-servers", &missing]);

    lab.then_the_exit_code_is(EXIT_FAILURE);
    lab.then_the_errors_contain(&["Failed: "]);
    assert!(LinkHandoff::await_gone(&lab.dir.path().join("config"), Duration::from_secs(5)));
}

#[test]
fn should_leave_the_links_of_a_one_shot_run_alone() {
    let mut lab = Lab::new();
    let emule = lab.dir.path().join("emule");
    fs::create_dir_all(&emule).unwrap();
    let njmule = lab.dir.path().join("njmule");
    let link = format!("ed2k://|file|left.iso|1000|{}|/", "ab".repeat(16));

    lab.when_run_with(&[
        "--config-dir",
        &njmule.to_string_lossy(),
        "--log-level",
        "WARN",
        "--import-emule",
        &emule.to_string_lossy(),
        "--dry-run",
        &link,
    ]);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_contains(&[&format!("What would be imported from {}", emule.display())]);
    lab.then_the_output_does_not_contain("already running");
    lab.then_the_file_does_not_exist("njmule/temp/001.part.met");
}

#[test]
fn should_leave_the_web_interface_off_on_a_busy_port() {
    let mut lab = Lab::new();
    let busy = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let busy_port = busy.local_addr().unwrap().port();
    let config = lab.dir.path().join("config");
    fs::create_dir_all(&config).unwrap();
    let settings = WebUiSettings {
        enabled: true,
        port: busy_port,
        lan: false,
        password_hash: "pbkdf2-sha512$1$AA$AA".to_owned(),
        ..WebUiSettings::default()
    };
    settings.save(&config).unwrap();

    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_printed("njmule-control ");

    lab.when_the_instance_is_stopped();
    drop(busy);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_output_does_not_contain("Web interface on port");
    lab.then_the_errors_contain(&[&format!("Cannot start the web interface on port {busy_port}")]);
}

#[test]
fn should_run_the_instance_alone_without_its_handoff_file() {
    let mut lab = Lab::new();
    lab.given_the_file(&format!("{}/kept", handoff_path()), "");

    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_printed("njmule-control ");

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_errors_contain(&["Later starts cannot hand their links over"]);
    lab.then_the_file_exists(&format!("{}/kept", handoff_path()));
}

#[test]
fn should_report_the_links_that_cannot_be_followed() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_with_control(&[
        "--config-dir",
        &lab.dir_str(),
        SERVER_LIST,
        NODES_LIST,
        "ed2k://|server|::1|4661|/",
        SEARCH,
    ]);
    lab.given_the_instance_has_reported(&[
        "Cannot take the server list: ",
        "Cannot take the Kad contacts: ",
        "Cannot add the server: ",
        &format!("Not acting on {SEARCH}"),
    ]);

    lab.when_the_instance_is_stopped();

    lab.then_the_exit_code_is(EXIT_OK);
}

#[test]
fn should_exit_cleanly_while_control_stdin_stays_open_and_log_stopping() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_printed("njmule-control ");

    lab.when_run_with(&["--config-dir", &lab.dir_str(), "--exit"]);

    lab.then_the_exit_code_is(EXIT_OK);
    lab.then_the_background_instance_ended_with(EXIT_OK);
    let logs = fs::read_dir(lab.dir.path().join("logs"))
        .unwrap()
        .filter_map(Result::ok)
        .filter_map(|e| fs::read_to_string(e.path()).ok())
        .collect::<Vec<_>>()
        .join("\n");
    assert!(logs.contains("Stopping njMule"), "expected 'Stopping njMule' in logs:\n{logs}");
}

#[cfg(unix)]
#[test]
fn should_exit_with_143_and_log_stopping_on_sigterm() {
    let mut lab = Lab::new();
    lab.given_an_instance_started_with_control(&["--config-dir", &lab.dir_str()]);
    lab.given_the_instance_has_printed("njmule-control ");

    let pid = lab.running.as_ref().unwrap().child.id().to_string();
    let status = Command::new("kill").args(["-TERM", &pid]).status().unwrap();
    assert!(status.success(), "kill -TERM failed");

    lab.then_the_background_instance_ended_with(143);
    let logs = fs::read_dir(lab.dir.path().join("logs"))
        .unwrap()
        .filter_map(Result::ok)
        .filter_map(|e| fs::read_to_string(e.path()).ok())
        .collect::<Vec<_>>()
        .join("\n");
    assert!(logs.contains("Stopping njMule"), "expected 'Stopping njMule' in logs:\n{logs}");
}

fn handoff_path() -> String {
    format!("config/{}", link_handoff::FILE_NAME)
}

struct Running {
    child: Child,
    stdin: Option<ChildStdin>,
    out_buf: Arc<Mutex<String>>,
    err_buf: Arc<Mutex<String>>,
    out_thread: Option<JoinHandle<()>>,
    err_thread: Option<JoinHandle<()>>,
}

impl Running {
    fn spawn(args: &[&str], control: bool) -> Self {
        let mut cmd = Command::new(bin());
        cmd.args(args).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
        if control {
            cmd.env("NJMULE_CONTROL_ENDPOINT", "1");
        } else {
            cmd.env_remove("NJMULE_CONTROL_ENDPOINT");
        }
        let mut child = cmd.spawn().unwrap();
        let stdin = child.stdin.take();
        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take().unwrap();
        let out_buf = Arc::new(Mutex::new(String::new()));
        let err_buf = Arc::new(Mutex::new(String::new()));
        let out_thread = Some(drain(stdout, Arc::clone(&out_buf)));
        let err_thread = Some(drain(stderr, Arc::clone(&err_buf)));
        Self { child, stdin, out_buf, err_buf, out_thread, err_thread }
    }

    fn stop(&mut self) -> (i32, String, String) {
        drop(self.stdin.take());
        self.wait()
    }

    fn wait(&mut self) -> (i32, String, String) {
        let status = self.child.wait().unwrap();
        if let Some(t) = self.out_thread.take() {
            let _ = t.join();
        }
        if let Some(t) = self.err_thread.take() {
            let _ = t.join();
        }
        let out = self.out_buf.lock().unwrap().clone();
        let err = self.err_buf.lock().unwrap().clone();
        (status.code().unwrap_or(-1), out, err)
    }
}

impl Drop for Running {
    fn drop(&mut self) {
        drop(self.stdin.take());
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn drain<R: Read + Send + 'static>(mut reader: R, buf: Arc<Mutex<String>>) -> JoinHandle<()> {
    thread::spawn(move || {
        let mut chunk = [0u8; 4096];
        loop {
            match reader.read(&mut chunk) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    buf.lock().unwrap().push_str(&String::from_utf8_lossy(&chunk[..n]));
                }
            }
        }
    })
}

struct Lab {
    dir: tempfile::TempDir,
    exit_code: i32,
    out: String,
    err: String,
    running: Option<Running>,
}

impl Lab {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), exit_code: -1, out: String::new(), err: String::new(), running: None }
    }

    fn dir_str(&self) -> String {
        self.dir.path().to_string_lossy().into_owned()
    }

    fn given_the_file(&self, path: &str, content: &str) {
        let file = self.dir.path().join(path);
        fs::create_dir_all(file.parent().unwrap()).unwrap();
        fs::write(file, content).unwrap();
    }

    fn given_a_profile_without_networks(&self) {
        let port = free_port::free_port();
        self.given_the_file(
            "config/njmule.properties",
            &format!("net.tcpPort={port}\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n"),
        );
    }

    fn given_an_instance_started_with_control(&mut self, args: &[&str]) {
        self.running = Some(Running::spawn(args, true));
    }

    fn given_an_instance_started_without_control(&mut self, args: &[&str]) {
        self.running = Some(Running::spawn(args, false));
    }

    fn given_the_instance_has_written(&self, path: &str) {
        let file = self.dir.path().join(path);
        wait_until(&format!("file {path}"), || file.exists());
    }

    fn given_the_instance_has_printed(&self, text: &str) {
        let buf = Arc::clone(&self.running.as_ref().unwrap().out_buf);
        wait_until(&format!("stdout containing {text:?}"), || buf.lock().unwrap().contains(text));
    }

    fn given_the_instance_has_reported(&self, texts: &[&str]) {
        let buf = Arc::clone(&self.running.as_ref().unwrap().err_buf);
        wait_until(&format!("stderr containing {texts:?}"), || {
            let content = buf.lock().unwrap();
            texts.iter().all(|t| content.contains(t))
        });
    }

    fn given_the_instance_has_saved_the_server_within(&self, timeout: Duration) {
        let met = self.dir.path().join("config").join("server.met");
        let deadline = Instant::now() + timeout;
        while Instant::now() < deadline {
            if let Ok(bytes) = fs::read(&met)
                && bytes.windows(4).any(|w| w == [1, 2, 3, 4])
            {
                return;
            }
            thread::sleep(Duration::from_millis(20));
        }
    }

    fn given_a_second_start_handed_its_links_over(&self, args: &[&str]) {
        let output = Command::new(bin()).args(args).output().unwrap();
        assert_eq!(output.status.code(), Some(0), "stderr: {}", String::from_utf8_lossy(&output.stderr));
        let stdout = String::from_utf8_lossy(&output.stdout);
        assert!(stdout.contains("already running"), "stdout: {stdout}");
    }

    fn when_run_with(&mut self, args: &[&str]) {
        let output = Command::new(bin()).args(args).env_remove("NJMULE_CONTROL_ENDPOINT").output().unwrap();
        self.exit_code = output.status.code().unwrap_or(-1);
        self.out = String::from_utf8_lossy(&output.stdout).into_owned();
        self.err = String::from_utf8_lossy(&output.stderr).into_owned();
    }

    fn when_run_with_input(&mut self, input: &str, args: &[&str]) {
        let mut child = Command::new(bin())
            .args(args)
            .env_remove("NJMULE_CONTROL_ENDPOINT")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(input.as_bytes());
        }
        let output = child.wait_with_output().unwrap();
        self.exit_code = output.status.code().unwrap_or(-1);
        self.out = String::from_utf8_lossy(&output.stdout).into_owned();
        self.err = String::from_utf8_lossy(&output.stderr).into_owned();
    }

    fn when_the_file_is_hashed(&mut self, name: &str) {
        let path = self.dir.path().join(name).to_string_lossy().into_owned();
        self.when_run_with(&["--hash", &path]);
    }

    fn when_the_instance_is_stopped(&mut self) {
        let mut running = self.running.take().unwrap();
        let (code, out, err) = running.stop();
        self.exit_code = code;
        self.out = out;
        self.err = err;
    }

    fn then_the_exit_code_is(&self, expected: i32) {
        assert_eq!(self.exit_code, expected, "stdout:\n{}\nstderr:\n{}", self.out, self.err);
    }

    fn then_the_exit_code_is_not_zero(&self) {
        assert_ne!(self.exit_code, 0, "stdout:\n{}\nstderr:\n{}", self.out, self.err);
    }

    fn then_the_output_contains(&self, texts: &[&str]) {
        for text in texts {
            assert!(self.out.contains(text), "expected {text:?} in stdout:\n{}\nstderr:\n{}", self.out, self.err);
        }
    }

    fn then_the_output_does_not_contain(&self, text: &str) {
        assert!(!self.out.contains(text), "unexpected {text:?} in stdout:\n{}", self.out);
    }

    fn then_the_errors_contain(&self, texts: &[&str]) {
        for text in texts {
            assert!(self.err.contains(text), "expected {text:?} in stderr:\n{}\nstdout:\n{}", self.err, self.out);
        }
    }

    fn then_the_file_exists(&self, path: &str) {
        assert!(self.dir.path().join(path).exists(), "missing file: {path}");
    }

    fn then_the_file_does_not_exist(&self, path: &str) {
        assert!(!self.dir.path().join(path).exists(), "unexpected file: {path}");
    }

    fn then_a_log_file_exists(&self) {
        let logs = self.dir.path().join("logs");
        let has_log = fs::read_dir(&logs)
            .unwrap()
            .filter_map(Result::ok)
            .any(|e| e.file_name().to_string_lossy().starts_with("njmule"));
        assert!(has_log, "expected log file in {}", logs.display());
    }

    fn then_the_web_interface_file_contains(&self, texts: &[&str]) {
        let content = fs::read_to_string(self.dir.path().join("config").join(web_ui_settings::FILE_NAME)).unwrap();
        for text in texts {
            assert!(content.contains(text), "expected {text:?} in {content}");
        }
    }

    fn then_the_web_interface_file_does_not_contain(&self, text: &str) {
        let content = fs::read_to_string(self.dir.path().join("config").join(web_ui_settings::FILE_NAME)).unwrap();
        assert!(!content.contains(text), "unexpected {text:?} in {content}");
    }

    fn then_the_web_interface_password_is(&self, password: &str) {
        let saved = WebUiSettings::load(&self.dir.path().join("config"));
        assert!(saved.usable(), "on, with a password");
        assert!(passwords::matches(password, &saved.password_hash), "the password of the file");
    }

    async fn then_the_servers_of_the_next_start_include(&self, address: &str) {
        let dirs = AppDirectories::resolve_current(Some(self.dir.path())).unwrap();
        let engine = Engine::start_with(dirs, Switches::of(&[])).await.unwrap();
        let servers = engine.servers().await;
        engine.stop().await;
        assert!(
            servers.iter().any(|s| s.address == address),
            "expected {address} in {:?}",
            servers.iter().map(|s| &s.address).collect::<Vec<_>>()
        );
    }

    async fn then_another_instance_can_start_at_once(&self) {
        let dirs = AppDirectories::resolve_current(Some(self.dir.path())).unwrap();
        let expected_config: PathBuf = self.dir.path().join("config");
        let engine = Engine::start_with(dirs, Switches::of(&[])).await.unwrap();
        assert_eq!(engine.directories().config, expected_config);
        engine.stop().await;
    }

    fn then_the_background_instance_ended_with(&mut self, expected: i32) {
        let mut running = self.running.take().unwrap();
        let (code, out, err) = running.wait();
        assert_eq!(code, expected, "bg stdout:\n{out}\nbg stderr:\n{err}");
    }
}

fn wait_until(what: &str, mut condition: impl FnMut() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(20);
    while !condition() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        thread::sleep(Duration::from_millis(20));
    }
}
