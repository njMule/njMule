// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The commands the association runs against a pretend system, never
//! touching the real one.

#![allow(
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::indexing_slicing,
    clippy::arithmetic_side_effects
)]

use std::collections::HashSet;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use njmule_engine::app::link_association::{
    self, CommandResult, DESKTOP_ID, LinkAssociation, LinuxLinks, Shell, WindowsLinks,
};

const PROGRAM: &str = r"C:\Program Files\njMule\njmule-gui.exe";
const PROGRAM_OUTSIDE_ASCII: &str = "C:\\Users\\J\u{00FC}rgen\\AppData\\Local\\Programs\\njMule\\njmule-gui.exe";

fn installed_path() -> PathBuf {
    PathBuf::from("njMule").join("njmule-gui.exe")
}

fn our_list() -> String {
    format!("[Default Applications]\nx-scheme-handler/ed2k={DESKTOP_ID}\n")
}

#[test]
fn should_not_take_the_links_of_another_program_for_ours_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM));
    test.given_the_ed2k_command_in_the_registry(r#""C:\Program Files (x86)\eMule\emule.exe" "%1""#);

    test.when_it_is_asked_whether_the_links_are_ours();

    test.then_the_answer_is(false);
}

#[test]
fn should_save_the_ed2k_key_before_replacing_it_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM));
    test.given_the_ed2k_command_in_the_registry(r#""C:\Program Files (x86)\eMule\emule.exe" "%1""#);

    test.when_the_links_are_taken();

    test.then_the_key_was_saved_first();
    test.then_the_last_import_starts_with("\u{FEFF}Windows Registry Editor Version 5.00");
    test.then_the_last_import_contains(r"[HKEY_CURRENT_USER\Software\Classes\ed2k\shell\open\command]");
    test.then_the_last_import_contains(r#"@="\"C:\\Program Files\\njMule\\njmule-gui.exe\" \"%1\"""#);
    test.then_the_links_are_ours();
}

#[test]
fn should_restore_the_saved_ed2k_key_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM));
    test.given_the_ed2k_command_in_the_registry(r#""C:\Program Files (x86)\eMule\emule.exe" "%1""#);
    test.given_the_links_were_taken();

    test.when_the_links_are_given_back();

    test.then_the_import_before_the_last_contains(r"[-HKEY_CURRENT_USER\Software\Classes\ed2k]");
    test.then_the_saved_key_comes_back();
    test.then_there_is_no_backup();
}

#[test]
fn should_recognise_a_program_folder_outside_ascii_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM_OUTSIDE_ASCII));

    test.when_the_links_are_taken();

    test.then_the_links_are_ours();
}

#[test]
fn should_give_back_the_links_of_a_program_folder_outside_ascii_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM_OUTSIDE_ASCII));
    test.given_the_links_were_taken();

    test.when_the_links_are_given_back();

    test.then_the_last_import_contains(r"[-HKEY_CURRENT_USER\Software\Classes\ed2k]");
    test.then_no_export_is_left_behind();
}

#[test]
fn should_give_back_nothing_that_is_not_ours_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM));
    test.given_the_ed2k_command_in_the_registry(r#""C:\eMule\emule.exe" "%1""#);

    test.when_the_links_are_given_back();

    test.then_nothing_was_imported();
}

#[test]
fn should_save_nothing_without_a_handler_before_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM));
    test.given_the_links_were_taken();

    test.when_the_links_are_taken();

    test.then_no_key_was_saved();
    test.then_there_is_no_backup();
}

#[test]
fn should_remove_the_key_without_a_handler_before_on_windows() {
    let mut test = Scenario::new();
    test.given_the_program_on_windows(Path::new(PROGRAM));
    test.given_the_links_were_taken();
    test.given_the_links_were_taken();

    test.when_the_links_are_given_back();

    test.then_the_number_of_imports_is(3);
    test.then_the_last_import_contains(r"[-HKEY_CURRENT_USER\Software\Classes\ed2k]");
    test.then_the_links_are_not_ours();
}

#[test]
fn should_find_no_default_value_in_an_export_without_a_string() {
    let exports =
        ["Windows Registry Editor Version 5.00\r\n\r\n[HKEY]\r\n", "@=\"\r\n@=dword:00000001\r\n@=\"unterminated\r\n"];
    for reg in exports {
        let mut test = Scenario::new();

        test.when_the_default_value_is_read_from(reg);

        test.then_there_is_no_default_value();
    }
}

#[test]
fn should_read_the_default_value_of_an_exported_key() {
    let exports = [
        ("[HKEY]\n@=\"\\\"C:\\\\njMule\\\\njmule-gui.exe\\\" \\\"%1\\\"\"\n", "\"C:\\njMule\\njmule-gui.exe\" \"%1\""),
        ("@=\"ends with \\\"", "ends with \\"),
    ];
    for (reg, expected) in exports {
        let mut test = Scenario::new();

        test.when_the_default_value_is_read_from(reg);

        test.then_the_default_value_is(expected);
    }
}

#[test]
fn should_make_nj_mule_the_default_handler_on_linux() {
    let mut test = Scenario::new();
    test.given_the_default_handler("amule.desktop");
    test.given_the_linux_association();

    test.when_the_links_are_taken();

    test.then_nj_mule_was_made_the_default_handler();
    test.then_the_links_are_ours();
}

#[test]
fn should_bring_the_previous_default_back_on_linux() {
    let mut test = Scenario::new();
    test.given_the_default_handler("amule.desktop");
    test.given_the_linux_association();
    test.given_the_links_were_taken();

    test.when_the_links_are_given_back();

    test.then_the_default_handler_is("amule.desktop");
}

#[test]
fn should_take_our_line_out_of_the_user_list_with_no_default_before_on_linux() {
    let mut test = Scenario::new();
    test.given_the_user_list(&format!(
        "[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k={DESKTOP_ID}\n"
    ));
    test.given_the_default_handler("");
    test.given_the_linux_association();
    test.given_the_links_were_taken();

    test.when_the_links_are_given_back();

    test.then_the_user_list_is("[Default Applications]\ntext/html=firefox.desktop\n");
}

#[test]
fn should_take_only_our_entry_out_of_the_user_list_on_linux() {
    let mut test = Scenario::new();
    test.given_the_user_list(&format!(
        "[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k={DESKTOP_ID};amule.desktop;\n\
         [Added Associations]\nx-scheme-handler/ed2k={DESKTOP_ID};\n"
    ));
    test.given_the_default_handler(DESKTOP_ID);
    test.given_the_linux_association();

    test.when_the_links_are_given_back();

    test.then_the_user_list_is(
        "[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k=amule.desktop;\n[Added Associations]\n",
    );
}

#[test]
fn should_keep_a_user_list_kept_elsewhere_a_link_on_linux() {
    let mut test = Scenario::new();
    if !test.given_the_user_list_kept_elsewhere(&our_list()) {
        return;
    }
    test.given_the_default_handler(DESKTOP_ID);
    test.given_the_linux_association();

    test.when_the_links_are_given_back();

    test.then_the_user_list_is_still_a_link();
    test.then_the_list_kept_elsewhere_is("[Default Applications]\n");
}

#[test]
fn should_keep_what_came_first_when_taking_twice_on_linux() {
    let mut test = Scenario::new();
    test.given_the_default_handler(DESKTOP_ID);
    test.given_the_linux_association();

    test.when_the_links_are_taken();

    test.then_nj_mule_was_made_the_default_handler();
    test.then_no_previous_handler_is_kept();
}

#[test]
fn should_give_back_nothing_that_is_not_ours_on_linux() {
    let mut test = Scenario::new();
    test.given_the_user_list("[Default Applications]\nx-scheme-handler/ed2k=amule.desktop;\n");
    test.given_the_default_handler("amule.desktop");
    test.given_the_linux_association();

    test.when_the_links_are_given_back();

    test.then_only_the_default_handler_was_asked_for();
    test.then_the_user_list_is_left_as_it_was();
}

#[test]
fn should_leave_a_user_list_without_our_entry_as_it_is_on_linux() {
    let mut test = Scenario::new();
    test.given_the_user_list(
        "[Default Applications]\nx-scheme-handler/ed2k=amule.desktop;\ntext/html=firefox.desktop\n",
    );
    test.given_the_default_handler(DESKTOP_ID);
    test.given_the_linux_association();

    test.when_the_links_are_given_back();

    test.then_the_user_list_is_left_as_it_was();
}

#[test]
fn should_not_take_the_links_for_ours_when_xdg_mime_knows_no_default_on_linux() {
    let mut test = Scenario::new();
    test.given_no_default_handler();
    test.given_the_linux_association();

    test.when_it_is_asked_whether_the_links_are_ours();

    test.then_the_answer_is(false);
}

#[test]
fn should_keep_that_there_was_no_default_when_xdg_mime_answers_none_on_linux() {
    let mut test = Scenario::new();
    test.given_no_default_handler();
    test.given_the_linux_association();

    test.when_the_links_are_taken();

    test.then_the_previous_handler_is_kept();
}

#[test]
fn should_leave_nothing_behind_when_xdg_mime_answered_no_default_on_linux() {
    let mut test = Scenario::new();
    test.given_no_default_handler();
    test.given_the_linux_association();
    test.given_the_links_were_taken();

    test.when_the_links_are_given_back();

    test.then_no_previous_handler_is_kept();
    test.then_there_is_no_user_list_to_clean();
}

#[test]
fn should_report_a_command_that_fails_with_its_output() {
    let mut test = Scenario::new();
    test.given_the_default_handler("amule.desktop");
    test.given_setting_a_default_fails_with("xdg-mime: no desktop environment\n");
    test.given_the_linux_association();

    test.when_trying_to_take_the_links();

    test.then_the_message_is(&format!(
        "xdg-mime default {DESKTOP_ID} x-scheme-handler/ed2k failed: xdg-mime: no desktop environment"
    ));
}

#[test]
fn should_let_only_the_installed_desktop_program_take_links() {
    let programs = [
        (PathBuf::from("njMule").join("njmule-gui.exe"), true),
        (PathBuf::from("usr").join("bin").join("njmule-gui"), true),
        (PathBuf::from("tools").join("bin").join("other.exe"), false),
    ];
    for (program, expected) in programs {
        let mut test = Scenario::new();

        test.when_it_is_asked_whether_it_is_the_desktop_program(&program);

        test.then_the_answer_is(expected);
    }
}

#[test]
fn should_associate_nothing_in_a_development_build() {
    let mut test = Scenario::new();

    test.when_the_association_of_this_system_is_looked_for();

    test.then_there_is_no_association();
}

#[test]
fn should_associate_no_other_program_on_windows() {
    let mut test = Scenario::new();

    test.when_the_association_is_looked_for(PathBuf::from("bin").join("other"), "Windows 11");

    test.then_there_is_no_association();
}

#[test]
fn should_associate_nothing_on_mac_os_which_hands_links_over_as_system_events() {
    let mut test = Scenario::new();

    test.when_the_association_is_looked_for(installed_path(), "Mac OS X");

    test.then_there_is_no_association();
}

#[test]
fn should_associate_the_installed_program_on_windows() {
    let mut test = Scenario::new();

    test.when_the_association_is_looked_for(installed_path(), "Windows 11");

    test.then_the_windows_command_is(&format!("\"{}\" \"%1\"", installed_path().display()));
}

#[test]
fn should_start_the_window_beside_the_engine_on_windows() {
    let mut test = Scenario::new();
    test.given_a_file(r"C:\Program Files\njMule\njmule-gui.exe");

    test.when_the_desktop_program_is_looked_for(r"C:\Program Files\njMule\njmule.exe", "Windows 11");

    test.then_the_desktop_program_is(r"C:\Program Files\njMule\njmule-gui.exe");
}

#[test]
fn should_keep_an_engine_without_window_beside_on_windows() {
    let mut test = Scenario::new();

    test.when_the_desktop_program_is_looked_for(r"C:\njmule\njmule.exe", "Windows 11");

    test.then_the_desktop_program_is(r"C:\njmule\njmule.exe");
}

#[test]
fn should_start_the_window_beside_the_engine_on_linux() {
    let mut test = Scenario::new();
    test.given_a_file("/usr/lib/njmule/njmule-gui");

    test.when_the_desktop_program_is_looked_for("/usr/lib/njmule/njmule", "Linux");

    test.then_the_desktop_program_is("/usr/lib/njmule/njmule-gui");
}

#[test]
fn should_keep_an_engine_without_window_beside_on_linux() {
    let mut test = Scenario::new();

    test.when_the_desktop_program_is_looked_for("/opt/njmule/njmule", "Linux");

    test.then_the_desktop_program_is("/opt/njmule/njmule");
}

#[test]
fn should_clean_the_user_list_of_the_desktop_configuration_on_linux() {
    let mut test = Scenario::new();
    test.given_our_entry_in_the_list_of_the_configuration_directory();
    test.given_our_entry_in_the_list_of_the_home();
    test.given_the_default_handler(DESKTOP_ID);
    test.given_the_installed_program_on_linux_with_the_configuration_directory(Some("xdg"));

    test.when_the_links_are_given_back();

    test.then_the_list_of_the_configuration_directory_is("[Default Applications]\n");
    test.then_the_list_of_the_home_is(&our_list());
}

#[test]
fn should_clean_the_user_list_of_the_home_without_a_configuration_directory_on_linux() {
    for xdg_config_home in [None, Some(" ")] {
        let mut test = Scenario::new();
        test.given_our_entry_in_the_list_of_the_home();
        test.given_the_default_handler(DESKTOP_ID);
        test.given_the_installed_program_on_linux_with_the_configuration_directory(xdg_config_home);

        test.when_the_links_are_given_back();

        test.then_the_list_of_the_home_is("[Default Applications]\n");
    }
}

#[test]
fn should_run_a_command_and_keep_its_output() {
    let mut test = Scenario::new();

    test.when_the_engine_runs_with("--version");

    test.then_the_exit_code_is_zero();
    test.then_the_output_contains("njMule");
}

#[test]
fn should_keep_the_output_of_a_command_that_fails() {
    let mut test = Scenario::new();

    test.when_the_engine_runs_with("--no-such-option");

    test.then_the_exit_code_is_not_zero();
    test.then_the_output_contains("--no-such-option");
}

#[test]
fn should_fail_to_run_a_program_that_does_not_exist() {
    let mut test = Scenario::new();

    test.when_a_program_that_does_not_exist_is_run();

    test.then_an_io_error_occurred();
}

struct PretendSystemState {
    program: PathBuf,
    command: Option<String>,
    exported: bool,
    imported: Vec<String>,
    current_default: Option<String>,
    default_failure: Option<String>,
    commands: Vec<Vec<String>>,
}

struct PretendSystem {
    state: Mutex<PretendSystemState>,
}

impl PretendSystem {
    fn new(program: PathBuf) -> Self {
        Self {
            state: Mutex::new(PretendSystemState {
                program,
                command: None,
                exported: false,
                imported: Vec::new(),
                current_default: None,
                default_failure: None,
                commands: Vec::new(),
            }),
        }
    }

    fn run_reg(&self, args: &[String]) -> io::Result<CommandResult> {
        let mut state = self.state.lock().unwrap();
        match args[1].as_str() {
            "query" => {
                return Ok(match &state.command {
                    None => CommandResult { exit: 1, output: "not found".to_owned() },
                    Some(command) => {
                        CommandResult { exit: 0, output: format!("    (Default)    REG_SZ    {command}\r\n") }
                    }
                });
            }
            "export" => {
                if args[2] == WindowsLinks::KEY {
                    fs::write(&args[3], "eMule's key")?;
                    state.exported = true;
                } else {
                    let Some(command) = state.command.clone() else {
                        return Ok(CommandResult { exit: 1, output: "not found".to_owned() });
                    };
                    let key = args[2].replace("HKCU", "HKEY_CURRENT_USER");
                    let content = format!(
                        "\u{FEFF}Windows Registry Editor Version 5.00\r\n\r\n[{key}]\r\n@={}\r\n\r\n",
                        WindowsLinks::reg_string(&command)
                    );
                    write_utf16le_exact(Path::new(&args[3]), &content)?;
                }
            }
            "import" => {
                let file = Path::new(&args[2]);
                let backup = file.file_name().is_some_and(|name| name == WindowsLinks::BACKUP);
                let body = if backup { fs::read_to_string(file)? } else { read_utf16le_exact(file)? };
                state.imported.push(body.clone());
                if body.contains("[-HKEY") {
                    state.command = None;
                } else if body.contains("njmule-gui.exe") {
                    state.command = Some(format!("\"{}\" \"%1\"", state.program.display()));
                } else {
                    state.command = Some(r#""C:\eMule\emule.exe" "%1""#.to_owned());
                }
            }
            other => return Err(io::Error::other(format!("unexpected {other}"))),
        }
        Ok(CommandResult { exit: 0, output: String::new() })
    }

    fn run_xdg_mime(&self, args: &[String]) -> CommandResult {
        let mut state = self.state.lock().unwrap();
        state.commands.push(args.to_vec());
        if args[1] == "default" {
            if let Some(failure) = &state.default_failure {
                return CommandResult { exit: 3, output: failure.clone() };
            }
            state.current_default = Some(args[2].clone());
        }
        match &state.current_default {
            None => CommandResult { exit: 4, output: "no default\n".to_owned() },
            Some(current) => CommandResult { exit: 0, output: format!("{current}\n") },
        }
    }
}

impl Shell for PretendSystem {
    fn run(&self, command: &[String]) -> io::Result<CommandResult> {
        if command[0] == "reg" { self.run_reg(command) } else { Ok(self.run_xdg_mime(command)) }
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

fn create_file_symlink(link: &Path, target: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, link)
    }
    #[cfg(windows)]
    {
        std::os::windows::fs::symlink_file(target, link)
    }
}

struct Scenario {
    dir: tempfile::TempDir,
    system: Arc<PretendSystem>,
    user_list_before: String,
    association: Option<LinkAssociation>,
    found: Option<LinkAssociation>,
    answer: bool,
    default_value: Option<String>,
    result: Option<CommandResult>,
    failure: Option<io::Error>,
    files: HashSet<String>,
    desktop_program: String,
}

impl Scenario {
    fn new() -> Self {
        Self {
            dir: tempfile::tempdir().unwrap(),
            system: Arc::new(PretendSystem::new(PathBuf::from(PROGRAM))),
            user_list_before: String::new(),
            association: None,
            found: None,
            answer: false,
            default_value: None,
            result: None,
            failure: None,
            files: HashSet::new(),
            desktop_program: String::new(),
        }
    }

    fn given_a_file(&mut self, path: &str) {
        self.files.insert(path.to_owned());
    }

    fn given_the_program_on_windows(&mut self, program: &Path) {
        self.system = Arc::new(PretendSystem::new(program.to_path_buf()));
        self.association = Some(LinkAssociation::Windows(WindowsLinks::new(
            self.dir.path().to_path_buf(),
            Arc::clone(&self.system) as Arc<dyn Shell>,
            program.to_path_buf(),
        )));
    }

    fn given_the_ed2k_command_in_the_registry(&self, command: &str) {
        self.system.state.lock().unwrap().command = Some(command.to_owned());
    }

    fn given_the_default_handler(&self, desktop_id: &str) {
        self.system.state.lock().unwrap().current_default = Some(desktop_id.to_owned());
    }

    fn given_no_default_handler(&self) {
        self.system.state.lock().unwrap().current_default = None;
    }

    fn given_setting_a_default_fails_with(&self, output: &str) {
        self.system.state.lock().unwrap().default_failure = Some(output.to_owned());
    }

    fn given_the_user_list(&mut self, content: &str) {
        fs::write(self.mime_apps(), content).unwrap();
        content.clone_into(&mut self.user_list_before);
    }

    fn given_the_user_list_kept_elsewhere(&self, content: &str) -> bool {
        let dotfiles = self.dir.path().join("dotfiles");
        fs::create_dir_all(&dotfiles).unwrap();
        let real = dotfiles.join("mimeapps.list");
        fs::write(&real, content).unwrap();
        create_file_symlink(&self.mime_apps(), &real).is_ok()
    }

    fn given_our_entry_in_the_list_of_the_configuration_directory(&self) {
        let xdg = self.dir.path().join("xdg");
        fs::create_dir_all(&xdg).unwrap();
        fs::write(xdg.join("mimeapps.list"), our_list()).unwrap();
    }

    fn given_our_entry_in_the_list_of_the_home(&self) {
        let config = self.dir.path().join("home/.config");
        fs::create_dir_all(&config).unwrap();
        fs::write(config.join("mimeapps.list"), our_list()).unwrap();
    }

    fn given_the_linux_association(&mut self) {
        self.association = Some(LinkAssociation::Linux(LinuxLinks::new(
            self.dir.path().to_path_buf(),
            Arc::clone(&self.system) as Arc<dyn Shell>,
            self.mime_apps(),
        )));
    }

    fn given_the_installed_program_on_linux_with_the_configuration_directory(&mut self, xdg_config_home: Option<&str>) {
        let xdg = xdg_config_home.map(|value| {
            if value.trim().is_empty() {
                value.to_owned()
            } else {
                self.dir.path().join(value).to_string_lossy().into_owned()
            }
        });
        self.association = LinkAssociation::for_program(
            self.dir.path().to_path_buf(),
            Arc::clone(&self.system) as Arc<dyn Shell>,
            PathBuf::from("usr").join("bin").join("njmule-gui"),
            "Linux",
            xdg.as_deref(),
            &self.dir.path().join("home").to_string_lossy(),
        );
        assert!(self.association.is_some());
    }

    fn given_the_links_were_taken(&self) {
        self.association.as_ref().unwrap().take().unwrap();
    }

    fn when_it_is_asked_whether_the_links_are_ours(&mut self) {
        self.answer = self.association.as_ref().unwrap().is_ours().unwrap();
    }

    fn when_the_links_are_taken(&self) {
        self.association.as_ref().unwrap().take().unwrap();
    }

    fn when_trying_to_take_the_links(&mut self) {
        self.failure = self.association.as_ref().unwrap().take().err();
    }

    fn when_the_links_are_given_back(&self) {
        self.association.as_ref().unwrap().give_back().unwrap();
    }

    fn when_the_default_value_is_read_from(&mut self, reg: &str) {
        self.default_value = WindowsLinks::default_value(reg);
    }

    fn when_it_is_asked_whether_it_is_the_desktop_program(&mut self, program: &Path) {
        self.answer = link_association::is_desktop_program(program);
    }

    fn when_the_desktop_program_is_looked_for(&mut self, command: &str, os: &str) {
        let files = self.files.clone();
        self.desktop_program =
            link_association::desktop_program(command, os, |path| files.iter().any(|file| Path::new(file) == path));
    }

    fn when_the_association_of_this_system_is_looked_for(&mut self) {
        self.found = LinkAssociation::for_this_system(self.dir.path().to_path_buf());
    }

    fn when_the_association_is_looked_for(&mut self, program: PathBuf, os: &str) {
        self.found = LinkAssociation::for_program(
            self.dir.path().to_path_buf(),
            Arc::clone(&self.system) as Arc<dyn Shell>,
            program,
            os,
            None,
            "home",
        );
    }

    fn when_the_engine_runs_with(&mut self, option: &str) {
        let command = vec![env!("CARGO_BIN_EXE_njmule").to_owned(), option.to_owned()];
        self.result = Some(link_association::run_command(&command).unwrap());
    }

    fn when_a_program_that_does_not_exist_is_run(&mut self) {
        let missing = self.dir.path().join("no-such-program").to_string_lossy().into_owned();
        self.failure = link_association::run_command(&[missing]).err();
    }

    fn then_the_answer_is(&self, expected: bool) {
        assert_eq!(self.answer, expected);
    }

    fn then_the_links_are_ours(&self) {
        assert!(self.association.as_ref().unwrap().is_ours().unwrap());
    }

    fn then_the_links_are_not_ours(&self) {
        assert!(!self.association.as_ref().unwrap().is_ours().unwrap());
    }

    fn then_the_key_was_saved_first(&self) {
        assert!(self.system.state.lock().unwrap().exported, "eMule's key is saved first");
    }

    fn then_no_key_was_saved(&self) {
        assert!(!self.system.state.lock().unwrap().exported, "neither before nor once ours");
    }

    fn then_the_last_import_starts_with(&self, start: &str) {
        let state = self.system.state.lock().unwrap();
        let last = state.imported.last().unwrap();
        assert!(last.starts_with(start), "{last:?} did not start with {start:?}");
    }

    fn then_the_last_import_contains(&self, text: &str) {
        let state = self.system.state.lock().unwrap();
        let last = state.imported.last().unwrap();
        assert!(last.contains(text), "missing {text:?} in {last:?}");
    }

    fn then_the_import_before_the_last_contains(&self, text: &str) {
        let state = self.system.state.lock().unwrap();
        let before_last = &state.imported[state.imported.len() - 2];
        assert!(before_last.contains(text), "missing {text:?} in {before_last:?}");
    }

    fn then_the_saved_key_comes_back(&self) {
        let state = self.system.state.lock().unwrap();
        assert_eq!(state.imported.last().unwrap(), "eMule's key");
    }

    fn then_the_number_of_imports_is(&self, expected: usize) {
        assert_eq!(self.system.state.lock().unwrap().imported.len(), expected);
    }

    fn then_nothing_was_imported(&self) {
        assert!(self.system.state.lock().unwrap().imported.is_empty());
    }

    fn then_there_is_no_backup(&self) {
        assert!(!self.dir.path().join(WindowsLinks::BACKUP).exists());
    }

    fn then_no_export_is_left_behind(&self) {
        let entries: Vec<_> = fs::read_dir(self.dir.path()).unwrap().collect();
        assert!(entries.is_empty(), "export left behind: {entries:?}");
    }

    fn then_there_is_no_default_value(&self) {
        assert!(self.default_value.is_none());
    }

    fn then_the_default_value_is(&self, expected: &str) {
        assert_eq!(self.default_value.as_deref(), Some(expected));
    }

    fn then_nj_mule_was_made_the_default_handler(&self) {
        let expected: Vec<String> =
            ["xdg-mime", "default", DESKTOP_ID, "x-scheme-handler/ed2k"].into_iter().map(str::to_owned).collect();
        assert!(self.system.state.lock().unwrap().commands.contains(&expected));
    }

    fn then_only_the_default_handler_was_asked_for(&self) {
        let expected: Vec<String> =
            ["xdg-mime", "query", "default", "x-scheme-handler/ed2k"].into_iter().map(str::to_owned).collect();
        assert_eq!(self.system.state.lock().unwrap().commands, vec![expected]);
    }

    fn then_the_default_handler_is(&self, expected: &str) {
        assert_eq!(self.system.state.lock().unwrap().current_default.as_deref(), Some(expected));
    }

    fn then_the_previous_handler_is_kept(&self) {
        assert!(self.dir.path().join(LinuxLinks::PREVIOUS).exists());
    }

    fn then_no_previous_handler_is_kept(&self) {
        assert!(!self.dir.path().join(LinuxLinks::PREVIOUS).exists());
    }

    fn then_the_user_list_is(&self, expected: &str) {
        assert_eq!(fs::read_to_string(self.mime_apps()).unwrap(), expected);
    }

    fn then_the_user_list_is_left_as_it_was(&self) {
        self.then_the_user_list_is(&self.user_list_before);
    }

    fn then_there_is_no_user_list_to_clean(&self) {
        assert!(!self.mime_apps().exists());
    }

    fn then_the_user_list_is_still_a_link(&self) {
        assert!(fs::symlink_metadata(self.mime_apps()).unwrap().file_type().is_symlink());
    }

    fn then_the_list_kept_elsewhere_is(&self, expected: &str) {
        assert_eq!(fs::read_to_string(self.dir.path().join("dotfiles/mimeapps.list")).unwrap(), expected);
    }

    fn then_the_list_of_the_configuration_directory_is(&self, expected: &str) {
        assert_eq!(fs::read_to_string(self.dir.path().join("xdg/mimeapps.list")).unwrap(), expected);
    }

    fn then_the_list_of_the_home_is(&self, expected: &str) {
        assert_eq!(fs::read_to_string(self.dir.path().join("home/.config/mimeapps.list")).unwrap(), expected);
    }

    fn then_the_desktop_program_is(&self, expected: &str) {
        assert_eq!(self.desktop_program, expected);
    }

    fn then_there_is_no_association(&self) {
        assert!(self.found.is_none());
    }

    fn then_the_windows_command_is(&self, expected: &str) {
        match self.found.as_ref().unwrap() {
            LinkAssociation::Windows(windows) => assert_eq!(windows.command(), expected),
            LinkAssociation::Linux(_) => panic!("expected Windows association"),
        }
    }

    fn then_the_exit_code_is_zero(&self) {
        assert_eq!(self.result.as_ref().unwrap().exit, 0);
    }

    fn then_the_exit_code_is_not_zero(&self) {
        assert_ne!(self.result.as_ref().unwrap().exit, 0);
    }

    fn then_the_output_contains(&self, text: &str) {
        let output = &self.result.as_ref().unwrap().output;
        assert!(output.contains(text), "missing {text:?} in {output:?}");
    }

    fn then_an_io_error_occurred(&self) {
        assert!(self.failure.is_some());
    }

    fn then_the_message_is(&self, expected: &str) {
        assert_eq!(self.failure.as_ref().unwrap().to_string(), expected);
    }

    fn mime_apps(&self) -> PathBuf {
        self.dir.path().join("mimeapps.list")
    }
}
