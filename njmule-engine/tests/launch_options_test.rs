// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Command-line parsing and validation of `njmule`.

#![allow(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::indexing_slicing)]

use std::path::{Component, Path, PathBuf};

use njmule_engine::api::ed2k_link::Ed2kLink;
use njmule_engine::api::import_options::ImportOptions;
use njmule_engine::app::launch_options::{Action, LaunchOptions, ServiceAction, UsageError, WebUiSetup};
use njmule_engine::server::server_list::Address;

fn file_link() -> String {
    format!("ed2k://|file|a.iso|10|{}|/", "ab".repeat(16))
}

const NUL_NAME: &str = "a\0b";

fn normalize_abs(path: &Path) -> PathBuf {
    let abs = std::path::absolute(path).unwrap();
    let mut out = PathBuf::new();
    for component in abs.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                let _ = out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

#[test]
fn should_run_by_default() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&[]);

    test.then_the_action_is(Action::Run);
    test.then_there_is_no_config_dir();
    test.then_there_is_no_log_level();
    test.then_there_is_no_web_interface_setup();
}

#[test]
fn should_parse_the_service_installation_with_the_web_interface_options() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--service=install", "--webui-lan", "--webui-password"]);

    test.then_the_action_is(Action::Service);
    test.then_the_service_action_is(ServiceAction::Install);
}

#[test]
fn should_refuse_other_options_with_a_service_command() {
    for option in ["--webui-lan", "--config-dir=.", "--headless"] {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&["--service=start", option]);

        test.then_usage_error_contains("cannot be combined");
    }
}

#[test]
fn should_open_the_desktop_app_on_the_njmule_of_an_address_without_a_command() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--remote-url", "http://nas:4711/api/v1"]);

    test.then_the_action_is(Action::Run);
    test.then_the_desktop_app_shows("http://nas:4711/api/v1");
}

#[test]
fn should_run_a_command_on_the_njmule_of_an_address() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--remote=status", "--remote-url", "http://nas:4711/api/v1"]);

    test.then_the_action_is(Action::Remote);
    test.then_the_desktop_app_shows_no_other_njmule();
}

#[test]
fn should_tell_the_services_profile_in_the_help() {
    let mut test = Scenario::new();

    test.when_the_help_is_read();

    test.then_the_help_tells("in %ProgramData%\\njMule as the virtual account");
}

#[test]
fn should_refuse_an_unknown_service_command() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--service=restart"]);

    test.then_usage_error_contains("install, uninstall, start, stop or status");
}

#[test]
fn should_refuse_the_service_output_without_a_service_command() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--service-output", "said.txt"]);

    test.then_usage_error_contains("--service-output applies to --service");
}

#[test]
fn should_parse_the_config_dir_and_the_log_level() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_arguments_are_parsed(&["--config-dir", &dir, "--log-level", "debug"]);

    test.then_the_config_dir_is_the_temp_folder();
    test.then_the_log_level_is("DEBUG");
}

#[test]
fn should_parse_a_config_dir_joined_to_its_option() {
    let mut test = Scenario::new();
    let arg = format!("--config-dir={}", test.dir.path().display());

    test.when_the_arguments_are_parsed(&[&arg]);

    test.then_the_config_dir_is_the_temp_folder();
}

#[test]
fn should_parse_the_option_of_an_action() {
    let cases = [
        ("-h", Action::Help),
        ("-V", Action::Version),
        ("--license", Action::License),
        ("--warranty", Action::Warranty),
        ("--notice", Action::Notice),
    ];
    for (option, action) in cases {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&[option]);

        test.then_the_action_is(action);
    }
}

#[test]
fn should_collect_the_files_to_hash() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--hash", "a.iso", "b.iso", "--log-level", "info"]);

    test.then_the_action_is(Action::Hash);
    test.then_the_files_to_hash_are(&["a.iso", "b.iso"]);
}

#[test]
fn should_refuse_a_hash_without_files() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--hash"]);

    test.then_usage_error_contains("--hash");
}

#[test]
fn should_collect_the_network_tasks() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&[
        "--headless",
        "--add-servers",
        "server.met",
        "--connect-server",
        "1.2.3.4:4661",
        "--search",
        "ubuntu iso",
        "--global",
    ]);

    test.then_the_action_is(Action::Run);
    test.then_it_runs_headless();
    test.then_the_servers_to_add_come_from("server.met");
    test.then_the_server_to_connect_to_is(Address::parse("1.2.3.4:4661").unwrap());
    test.then_the_search_is("ubuntu iso");
    test.then_the_search_is_global();
    test.then_the_run_is_one_shot(true);
}

#[test]
fn should_end_the_run_after_a_hello() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--hello", "localhost:61426"]);

    test.then_the_hello_goes_to(Address::parse("localhost:61426").unwrap());
    test.then_the_run_is_one_shot(true);
}

#[test]
fn should_keep_running_once_connected_to_a_server() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--connect-server", "s.example:4242"]);

    test.then_the_run_is_one_shot(false);
}

#[test]
fn should_collect_a_download_with_its_sources() {
    let mut test = Scenario::new();
    let link = format!("{}|sources,127.0.0.1:61426|/", file_link());

    test.when_the_arguments_are_parsed(&["--download", &link]);

    test.then_the_number_of_downloads_is(1);
    test.then_the_number_of_sources_of_the_first_download_is(1);
    test.then_the_run_is_one_shot(true);
}

#[test]
fn should_collect_a_kad_search_and_its_bootstrap() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--kad-bootstrap", "127.0.0.1:14897", "--search", "x", "--kad"]);

    test.then_the_kad_bootstrap_is(Address::parse("127.0.0.1:14897").unwrap());
    test.then_the_search_goes_through_kad();
}

#[test]
fn should_refuse_kad_without_a_search() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--kad"]);

    test.then_usage_error_contains("--kad applies to --search");
}

#[test]
fn should_refuse_kad_with_a_global_search() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--search", "x", "--global", "--kad"]);

    test.then_the_message_is("--kad applies to --search, without --global");
}

#[test]
fn should_share_folders_without_ending_the_run() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--share", "videos", "music"]);

    test.then_the_shared_folders_are(&["videos", "music"]);
    test.then_the_run_is_one_shot(false);
}

#[test]
fn should_refuse_a_download_of_a_link_that_is_not_a_file() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--download", "ed2k://|server|1.2.3.4|4661|/"]);

    test.then_usage_error_contains("Not an ed2k file link");
}

#[test]
fn should_refuse_a_server_address_that_cannot_be_read() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--connect-server", "nowhere"]);

    test.then_usage_error_contains("Invalid address for --connect-server");
}

#[test]
fn should_refuse_global_without_a_search() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--global"]);

    test.then_usage_error_contains("--global applies to --search");
}

#[test]
fn should_take_a_link_for_the_running_instance_as_an_argument() {
    let mut test = Scenario::new();
    let link = file_link();

    test.when_the_arguments_are_parsed(&[&link, "--log-level", "info"]);

    test.then_the_action_is(Action::Run);
    test.then_the_only_link_is(&link);
    test.then_the_links_do_not_end_the_run();
}

#[test]
fn should_take_a_server_list_link_for_the_running_instance_to_follow() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["ed2k://|serverlist|https://example.org/server.met|/"]);

    test.then_the_only_link_is_a_server_list();
}

#[test]
fn should_refuse_a_link_that_cannot_be_read() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["ed2k://nothing"]);

    test.then_usage_error_occurred();
}

#[test]
fn should_collect_an_emule_import() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_arguments_are_parsed(&["--import-emule", &dir, "--dry-run", "--import-downloads"]);

    test.then_the_import_is_of_the_temp_folder();
    test.then_the_import_options_are(ImportOptions { dry_run: true, identity: false, downloads: true });
    test.then_the_run_is_one_shot(true);
}

#[test]
fn should_collect_an_import_that_adopts_the_emule_identity() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--import-emule", "x", "--adopt-emule-identity"]);

    test.then_the_import_options_are(ImportOptions { dry_run: false, identity: true, downloads: false });
}

#[test]
fn should_refuse_an_import_option_without_the_import() {
    for option in ["--dry-run", "--adopt-emule-identity", "--import-downloads"] {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&[option]);

        test.then_usage_error_contains("apply to --import-emule");
    }
}

#[test]
fn should_refuse_an_unknown_option() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--bogus"]);

    test.then_usage_error_contains("Unknown option");
    test.then_usage_error_contains("--bogus");
}

#[test]
fn should_refuse_a_config_dir_without_its_folder() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--config-dir"]);

    test.then_usage_error_contains("--config-dir");
}

#[test]
fn should_refuse_an_option_in_place_of_the_log_level() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--log-level", "--help"]);

    test.then_usage_error_contains("--log-level");
}

#[test]
fn should_refuse_an_unknown_log_level() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--log-level", "loud"]);

    test.then_the_message_is("Invalid log level: loud");
}

#[test]
fn should_refuse_a_path_the_file_system_cannot_name() {
    let cases =
        [("--config-dir", "directory"), ("--share", "directory"), ("--add-servers", "file"), ("--hash", "file")];
    for (option, kind) in cases {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&[option, NUL_NAME]);

        test.then_the_message_is(&format!("Invalid {kind}: {NUL_NAME}"));
    }
}

#[test]
fn should_parse_exit() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--exit"]);

    test.then_the_action_is(Action::Exit);
}

#[test]
fn should_parse_exit_with_where_and_how_to_run() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_arguments_are_parsed(&["--exit", "--config-dir", &dir, "--log-level", "info", "--headless"]);

    test.then_the_action_is(Action::Exit);
}

#[test]
fn should_put_help_and_version_before_exit() {
    for (option, action) in [("--help", Action::Help), ("--version", Action::Version)] {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&["--exit", option]);

        test.then_the_action_is(action);
    }
}

#[test]
fn should_refuse_exit_with_something_else_to_do() {
    let link = file_link();
    let cases: [Vec<&str>; 8] = [
        vec!["--exit", &link],
        vec!["--webui-password", "--exit"],
        vec!["--exit", "--webui-password-file", "secret"],
        vec!["--exit", "--hash", "a.iso"],
        vec!["--exit", "--license"],
        vec!["--exit", "--search", "linux"],
        vec!["--exit", "--connect-server", "1.2.3.4:4661"],
        vec!["--exit", "--import-emule", "x"],
    ];
    for args in cases {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&args);

        test.then_usage_error_contains("--exit");
    }
}

#[test]
fn should_parse_the_check_for_a_new_version() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_arguments_are_parsed(&["--check-update", "--config-dir", &dir]);

    test.then_the_action_is(Action::CheckUpdate);
}

#[test]
fn should_parse_the_update() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_arguments_are_parsed(&["--update", "--config-dir", &dir]);

    test.then_the_action_is(Action::Update);
}

#[test]
fn should_refuse_the_update_with_links() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--update", "ed2k://|server|203.0.113.10|4661|/"]);

    test.then_usage_error_contains("--update cannot be combined with links");
}

#[test]
fn should_parse_the_installation_handed_over_with_the_processes_to_wait_for() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--apply-update", "apply.properties", "--wait", "4242", "--wait", "77"]);

    test.then_the_action_is(Action::ApplyUpdate);
    test.then_the_plan_is("apply.properties");
    test.then_it_waits_for(&[4242, 77]);
}

#[test]
fn should_wait_for_an_old_process_before_a_run() {
    let mut test = Scenario::new();
    let dir = test.dir.path().to_string_lossy().into_owned();

    test.when_the_arguments_are_parsed(&["--wait", "4242", "--config-dir", &dir]);

    test.then_the_action_is(Action::Run);
    test.then_it_waits_for(&[4242]);
}

#[test]
fn should_refuse_a_process_to_wait_for_that_is_none() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--wait", "-3"]);

    test.then_usage_error_contains("Invalid process for --wait: -3");
}

#[test]
fn should_keep_the_hidden_options_out_of_the_help() {
    let mut test = Scenario::new();

    test.when_the_help_is_read();

    test.then_the_help_tells("--update");
    test.then_the_help_does_not_tell("--apply-update");
    test.then_the_help_does_not_tell("--wait");
}

#[test]
fn should_refuse_the_check_for_a_new_version_with_something_else_to_do() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--check-update", "--search", "linux"]);

    test.then_usage_error_contains("--check-update");
}

#[test]
fn should_parse_the_web_interface_password_and_its_port() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-password", "--webui-port", "8080"]);

    test.then_the_action_is(Action::WebUiPassword);
    test.then_the_web_interface_setup_is(WebUiSetup { port: Some(8080), ..WebUiSetup::default() });
}

#[test]
fn should_parse_the_web_interface_password_for_the_lan() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-password", "--webui-lan"]);

    test.then_the_web_interface_setup_is(WebUiSetup { lan: true, ..WebUiSetup::default() });
}

#[test]
fn should_parse_a_web_interface_password_file_for_every_start() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-password-file", "secret", "--webui-lan", "--webui-api"]);

    test.then_the_action_is(Action::Run);
    test.then_the_web_interface_setup_is(WebUiSetup {
        lan: true,
        api: true,
        password_file: Some(PathBuf::from("secret")),
        ..WebUiSetup::default()
    });
}

#[test]
fn should_parse_a_web_interface_password_file_instead_of_asking() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-password", "--webui-password-file", "secret"]);

    test.then_the_action_is(Action::WebUiPassword);
    test.then_the_web_interface_setup_is(WebUiSetup {
        password_file: Some(PathBuf::from("secret")),
        ..WebUiSetup::default()
    });
}

#[test]
fn should_take_the_web_interface_options_of_the_service_without_a_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--service=install", "--webui-lan"]);

    test.then_the_action_is(Action::Service);
    test.then_the_web_interface_setup_is(WebUiSetup { lan: true, keep_password: true, ..WebUiSetup::default() });
}

#[test]
fn should_refuse_the_web_interface_port_without_the_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-port", "8080"]);

    test.then_usage_error_contains("apply to --webui-password");
}

#[test]
fn should_refuse_the_web_interface_lan_without_the_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-lan"]);

    test.then_usage_error_contains("apply to --webui-password");
}

#[test]
fn should_refuse_an_invalid_web_interface_port() {
    for port in ["65536", "0", "x"] {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&["--webui-password", "--webui-port", port]);

        test.then_the_message_is(&format!("Invalid port for --webui-port: {port}"));
    }
}

#[test]
fn should_parse_the_host_names_of_the_web_interface_repeated_or_separated_by_commas() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&[
        "--webui-password-file",
        "secret",
        "--webui-host",
        "NAS.local",
        "--webui-host",
        "njmule.example.com,fe80::1",
    ]);

    test.then_the_web_interface_setup_is(WebUiSetup {
        password_file: Some(PathBuf::from("secret")),
        hosts: vec!["nas.local".to_owned(), "njmule.example.com".to_owned(), "[fe80::1]".to_owned()],
        ..WebUiSetup::default()
    });
}

#[test]
fn should_parse_the_trusted_proxies_of_the_web_interface_repeated_or_separated_by_commas() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&[
        "--webui-password-file",
        "secret",
        "--webui-proxy",
        "172.17.0.0/16",
        "--webui-proxy",
        "::1,10.0.0.5",
    ]);

    test.then_the_web_interface_setup_is(WebUiSetup {
        password_file: Some(PathBuf::from("secret")),
        proxies: vec!["172.17.0.0/16".to_owned(), "::1".to_owned(), "10.0.0.5".to_owned()],
        ..WebUiSetup::default()
    });
}

#[test]
fn should_refuse_a_proxy_named_by_its_host_name() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-password", "--webui-proxy", "proxy.example"]);

    test.then_the_message_is("Invalid proxy for --webui-proxy: proxy.example");
}

#[test]
fn should_refuse_the_trusted_proxies_without_the_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-proxy", "172.17.0.1"]);

    test.then_usage_error_contains("apply to --webui-password");
}

#[test]
fn should_parse_the_guest_password_file_of_the_web_interface() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-password-file", "secret", "--webui-guest-password-file", "guest"]);

    test.then_the_web_interface_setup_is(WebUiSetup {
        password_file: Some(PathBuf::from("secret")),
        guest_password_file: Some(PathBuf::from("guest")),
        ..WebUiSetup::default()
    });
}

#[test]
fn should_take_the_guest_of_the_service_without_a_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--service=install", "--webui-no-guest"]);

    test.then_the_action_is(Action::Service);
    test.then_the_web_interface_setup_is(WebUiSetup { keep_password: true, no_guest: true, ..WebUiSetup::default() });
}

#[test]
fn should_refuse_the_guest_password_file_without_the_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-guest-password-file", "guest"]);

    test.then_usage_error_contains("apply to --webui-password");
}

#[test]
fn should_refuse_a_guest_password_file_with_no_guest() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&[
        "--webui-password-file",
        "secret",
        "--webui-guest-password-file",
        "guest",
        "--webui-no-guest",
    ]);

    test.then_the_message_is("--webui-guest-password-file cannot be combined with --webui-no-guest");
}

#[test]
fn should_refuse_the_web_interface_host_names_without_the_password() {
    let mut test = Scenario::new();

    test.when_the_arguments_are_parsed(&["--webui-host", "nas.local"]);

    test.then_usage_error_contains("apply to --webui-password");
}

#[test]
fn should_refuse_a_web_interface_host_with_a_port_a_scheme_or_no_host_name() {
    for name in ["nas.local:4711", "https://njmule.example.com", "[nas.local]", "bad name"] {
        let mut test = Scenario::new();

        test.when_the_arguments_are_parsed(&["--webui-password", "--webui-host", name]);

        test.then_the_message_is(&format!("Invalid host name for --webui-host: {name}"));
    }
}

struct Scenario {
    dir: tempfile::TempDir,
    options: Option<LaunchOptions>,
    failure: Option<UsageError>,
    help: String,
}

impl Scenario {
    fn new() -> Self {
        Self { dir: tempfile::tempdir().unwrap(), options: None, failure: None, help: String::new() }
    }

    fn when_the_arguments_are_parsed(&mut self, args: &[&str]) {
        match LaunchOptions::parse(args) {
            Ok(options) => self.options = Some(options),
            Err(error) => self.failure = Some(error),
        }
    }

    fn when_the_help_is_read(&mut self) {
        self.help = LaunchOptions::usage();
    }

    fn parsed(&self) -> &LaunchOptions {
        assert!(self.failure.is_none(), "expected parse success, got {:?}", self.failure);
        self.options.as_ref().unwrap()
    }

    fn then_the_plan_is(&self, expected: &str) {
        assert_eq!(self.parsed().apply_update.as_deref(), Some(Path::new(expected)));
    }

    fn then_it_waits_for(&self, pids: &[u64]) {
        assert_eq!(self.parsed().wait_for, pids);
    }

    fn then_the_help_tells(&self, text: &str) {
        assert!(self.help.contains(text), "missing {text:?} in:\n{}", self.help);
    }

    fn then_the_help_does_not_tell(&self, text: &str) {
        assert!(!self.help.contains(text), "unexpected {text:?} in:\n{}", self.help);
    }

    fn then_the_action_is(&self, expected: Action) {
        assert_eq!(self.parsed().action, expected);
    }

    fn then_the_desktop_app_shows(&self, api: &str) {
        assert_eq!(self.parsed().remote_desktop(), Some(api));
    }

    fn then_the_desktop_app_shows_no_other_njmule(&self) {
        assert!(self.parsed().remote_desktop().is_none());
    }

    fn then_the_service_action_is(&self, expected: ServiceAction) {
        assert_eq!(self.parsed().service.as_ref().map(|task| task.action), Some(expected));
    }

    fn then_there_is_no_config_dir(&self) {
        assert!(self.parsed().config_dir.is_none());
    }

    fn then_there_is_no_log_level(&self) {
        assert!(self.parsed().log_level.is_none());
    }

    fn then_there_is_no_web_interface_setup(&self) {
        assert!(self.parsed().web_ui.is_none());
    }

    fn then_the_config_dir_is_the_temp_folder(&self) {
        assert_eq!(self.parsed().config_dir, Some(normalize_abs(self.dir.path())));
    }

    fn then_the_log_level_is(&self, expected: &str) {
        assert_eq!(self.parsed().log_level.as_deref(), Some(expected));
    }

    fn then_the_files_to_hash_are(&self, expected: &[&str]) {
        let expected: Vec<PathBuf> = expected.iter().map(PathBuf::from).collect();
        assert_eq!(self.parsed().files, expected);
    }

    fn then_it_runs_headless(&self) {
        assert!(self.parsed().headless);
    }

    fn then_the_servers_to_add_come_from(&self, expected: &str) {
        assert_eq!(self.parsed().network.server_met.as_deref(), Some(Path::new(expected)));
    }

    fn then_the_server_to_connect_to_is(&self, expected: Address) {
        assert_eq!(self.parsed().network.server, Some(expected));
    }

    fn then_the_search_is(&self, expected: &str) {
        assert_eq!(self.parsed().network.search.as_deref(), Some(expected));
    }

    fn then_the_search_is_global(&self) {
        assert!(self.parsed().network.global);
    }

    fn then_the_run_is_one_shot(&self, expected: bool) {
        assert_eq!(self.parsed().network.is_one_shot(), expected);
    }

    fn then_the_hello_goes_to(&self, expected: Address) {
        assert_eq!(self.parsed().network.hello, Some(expected));
    }

    fn then_the_number_of_downloads_is(&self, expected: usize) {
        assert_eq!(self.parsed().network.downloads.len(), expected);
    }

    fn then_the_number_of_sources_of_the_first_download_is(&self, expected: usize) {
        assert_eq!(self.parsed().network.downloads[0].sources.len(), expected);
    }

    fn then_the_kad_bootstrap_is(&self, expected: Address) {
        assert_eq!(self.parsed().network.kad_bootstrap, Some(expected));
    }

    fn then_the_search_goes_through_kad(&self) {
        assert!(self.parsed().network.kad);
    }

    fn then_the_shared_folders_are(&self, folders: &[&str]) {
        let expected: Vec<PathBuf> = folders.iter().map(|folder| normalize_abs(Path::new(folder))).collect();
        assert_eq!(self.parsed().network.shares, expected);
    }

    fn then_the_only_link_is(&self, link: &str) {
        assert_eq!(self.parsed().links, vec![Ed2kLink::parse(link).unwrap()]);
    }

    fn then_the_links_do_not_end_the_run(&self) {
        assert!(!self.parsed().network.is_one_shot());
    }

    fn then_the_only_link_is_a_server_list(&self) {
        assert_eq!(self.parsed().links.len(), 1);
        assert!(matches!(self.parsed().links[0], Ed2kLink::ServerList(_)));
    }

    fn then_the_import_is_of_the_temp_folder(&self) {
        assert_eq!(self.parsed().network.emule_import.as_ref().unwrap().dir, normalize_abs(self.dir.path()));
    }

    fn then_the_import_options_are(&self, expected: ImportOptions) {
        assert_eq!(self.parsed().network.emule_import.as_ref().unwrap().options, expected);
    }

    fn then_the_web_interface_setup_is(&self, expected: WebUiSetup) {
        assert_eq!(self.parsed().web_ui, Some(expected));
    }

    fn then_usage_error_occurred(&self) {
        assert!(self.failure.is_some(), "expected usage error");
    }

    fn then_usage_error_contains(&self, part: &str) {
        let message = &self.failure.as_ref().expect("expected usage error").0;
        assert!(message.contains(part), "missing {part:?} in {message:?}");
    }

    fn then_the_message_is(&self, expected: &str) {
        assert_eq!(self.failure.as_ref().expect("expected usage error").0, expected);
    }
}
