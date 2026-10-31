// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the desktop program does without opening its window, as the Windows launcher it replaces did:
//!
//! - `--version` writes njMule's version;
//! - `--restore-links` hands the eD2K links back to the program that had them, through the engine;
//! - on Windows, a copy of it out of the folder Windows Installer replaces installs a new version:
//!   `--apply-update <msi> --wait <pid>... --ui passive|quiet [--all-users] --log <file> --result <file>
//!   [--relaunch <program> <arguments>...]` waits for those processes to end, runs msiexec on the package, writes its
//!   exit code to the result file, which the next start of njMule reads, and starts njMule again when asked;
//! - on Windows, `--keep-awake <pid>` keeps the computer from sleeping while that njMule transfers files, and
//!   `--uninstall-profile` asks the uninstaller's question whether the user's data goes too.

use std::path::PathBuf;
use std::process::Command;
use std::time::Duration;

/// The processes an update waits for: the old njMule and the window that started it.
const MAX_WAITS: usize = 8;
/// How long a start waits for each process it was told to.
const WAIT: Duration = Duration::from_secs(60);

/// A command of the desktop program, from its command line.
#[derive(Debug, PartialEq)]
pub enum Verb {
    Version,
    RestoreLinks(Vec<String>),
    ApplyUpdate(Update),
    KeepAwake(u32),
    UninstallProfile,
}

/// A Windows Installer package to install for an update, as njMule asked for it.
#[derive(Debug, Default, PartialEq)]
pub struct Update {
    pub msi: String,
    pub waits: Vec<u32>,
    pub quiet: bool,
    pub all_users: bool,
    pub log: Option<String>,
    pub result: Option<String>,
    pub relaunch: Vec<String>,
}

/// The command `args` (the program's name first) names, if any; none opens the window.
pub fn verb(args: &[String]) -> Option<Result<Verb, String>> {
    let first = args.get(1)?;
    match first.as_str() {
        "--version" if args.len() == 2 => Some(Ok(Verb::Version)),
        "--restore-links" => Some(Ok(Verb::RestoreLinks(args[1..].to_vec()))),
        "--apply-update" if args.get(2).is_some_and(|msi| msi.to_ascii_lowercase().ends_with(".msi")) => {
            Some(update(&args[1..]).map(Verb::ApplyUpdate))
        }
        "--keep-awake" if args.len() == 3 => {
            Some(args[2].parse().map(Verb::KeepAwake).map_err(|_| format!("Not a process: {}", args[2])))
        }
        "--uninstall-profile" if args.len() == 2 => Some(Ok(Verb::UninstallProfile)),
        _ => None,
    }
}

/// The processes `--wait <pid>` names: the njMule an update could not replace, which the next start waits for.
pub fn waits(args: &[String]) -> Vec<u32> {
    args.windows(2).filter(|pair| pair[0] == "--wait").filter_map(|pair| pair[1].parse().ok()).collect()
}

/// Waits up to a minute for each of `pids` to end.
pub fn await_ended(pids: &[u32]) {
    for &pid in pids {
        #[cfg(windows)]
        windows::wait_for(pid, WAIT.as_millis() as u32);
        #[cfg(unix)]
        {
            let end = std::time::Instant::now() + WAIT;
            // SAFETY: signal 0 sends nothing; it only asks whether the process is there.
            while std::time::Instant::now() < end && unsafe { libc::kill(pid as libc::pid_t, 0) } == 0 {
                std::thread::sleep(Duration::from_millis(100));
            }
        }
    }
}

/// The update `args` describe, from `--apply-update` on.
fn update(args: &[String]) -> Result<Update, String> {
    let mut update = Update::default();
    let mut words = args.iter();
    while let Some(word) = words.next() {
        let mut value = || words.next().cloned().ok_or_else(|| format!("{word} wants a value"));
        match word.as_str() {
            "--apply-update" => update.msi = value()?,
            "--wait" => {
                let pid = value()?.parse::<u32>().map_err(|_| "--wait wants a process".to_owned())?;
                if pid != 0 && update.waits.len() < MAX_WAITS {
                    update.waits.push(pid);
                }
            }
            "--ui" => update.quiet = value()? == "quiet",
            "--all-users" => update.all_users = true,
            "--log" => update.log = Some(value()?),
            "--result" => update.result = Some(value()?),
            "--relaunch" => {
                update.relaunch = words.by_ref().cloned().collect();
                if update.relaunch.is_empty() {
                    return Err("--relaunch wants a program".into());
                }
            }
            _ => return Err(format!("Unknown option {word}")),
        }
    }
    Ok(update)
}

/// The arguments of msiexec for `update`, but the property that installs for every user (written as is).
pub fn msiexec_args(update: &Update) -> Vec<String> {
    let mut args = vec!["/i".to_owned(), update.msi.clone()];
    args.push(if update.quiet { "/quiet" } else { "/passive" }.to_owned());
    args.push("/norestart".to_owned());
    if let Some(log) = &update.log {
        args.extend(["/l*v".to_owned(), log.clone()]);
    }
    args
}

/// Runs `verb` and returns the program's exit code.
pub fn run(verb: Verb, version: &str) -> i32 {
    match verb {
        Verb::Version => {
            attach_console();
            println!("njMule {version}");
            0
        }
        Verb::RestoreLinks(args) => engine(&args),
        #[cfg(windows)]
        Verb::ApplyUpdate(update) => windows::apply_update(&update),
        #[cfg(windows)]
        Verb::KeepAwake(pid) => windows::keep_awake(pid),
        #[cfg(windows)]
        Verb::UninstallProfile => windows::uninstall_profile(),
        #[cfg(not(windows))]
        Verb::ApplyUpdate(_) | Verb::KeepAwake(_) | Verb::UninstallProfile => {
            eprintln!("Only on Windows.");
            2
        }
    }
}

/// Tells the user why a command cannot run; a window on Windows, where nobody sees the program's output.
pub fn fail(message: &str) {
    #[cfg(windows)]
    windows::fail(message);
    #[cfg(not(windows))]
    eprintln!("{message}");
}

/// The engine beside this program.
pub fn engine_beside() -> PathBuf {
    let name = if cfg!(windows) { "njmule.exe" } else { "njmule" };
    std::env::current_exe().ok().and_then(|exe| exe.parent().map(|dir| dir.join(name))).unwrap_or(name.into())
}

/// Runs the engine with `args`, as the desktop's, and returns its exit code.
fn engine(args: &[String]) -> i32 {
    let mut command = Command::new(engine_beside());
    command.args(args).env("NJMULE_DESKTOP", "1");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    match command.status() {
        Ok(status) => status.code().unwrap_or(1),
        Err(e) => {
            fail(&format!("njMule cannot start {}: {e}", engine_beside().display()));
            1
        }
    }
}

/// The console of the command line that started this window program, so that its output shows there.
fn attach_console() {
    #[cfg(windows)]
    // SAFETY: no pointers; a program that has a console already, or a parent without one, just keeps its own.
    unsafe {
        windows_sys::Win32::System::Console::AttachConsole(windows_sys::Win32::System::Console::ATTACH_PARENT_PROCESS);
    }
}

#[cfg(windows)]
mod windows {
    use super::{msiexec_args, Update};
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::process::CommandExt;
    use std::process::Command;
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
    use windows_sys::Win32::System::Power::{SetThreadExecutionState, ES_CONTINUOUS, ES_SYSTEM_REQUIRED};
    use windows_sys::Win32::System::Threading::{OpenProcess, WaitForSingleObject, INFINITE, PROCESS_SYNCHRONIZE};
    use windows_sys::Win32::UI::Controls::{
        TASKDIALOGCONFIG, TDCBF_OK_BUTTON, TDF_ALLOW_DIALOG_CANCELLATION, TD_INFORMATION_ICON,
    };
    use windows_sys::Win32::UI::Shell::{
        SHFileOperationW, FOF_ALLOWUNDO, FOF_NOCONFIRMATION, FOF_NOERRORUI, FOF_SILENT, FO_DELETE, SHFILEOPSTRUCTW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        AllowSetForegroundWindow, MessageBoxW, IDOK, IDYES, MB_DEFBUTTON2, MB_ICONERROR, MB_ICONQUESTION, MB_OK,
        MB_SETFOREGROUND, MB_TOPMOST, MB_YESNO,
    };

    const WAIT_MILLIS: u32 = 60_000;
    /// Windows Installer's exit code for an installation a reboot finishes.
    const REBOOT_REQUIRED: i32 = 3010;
    const PROFILE_QUESTION: &str = "Also delete your njMule data?";
    const PROFILE_TEXT: &str = "Your data is your settings, server and contact lists, identity and credits, \
        unfinished downloads and logs. Leave the box unticked to keep it for a later installation; tick it to move \
        the data to the Recycle Bin.\n\nYour completed downloads are always kept.";

    fn wide(text: &str) -> Vec<u16> {
        text.encode_utf16().chain(std::iter::once(0)).collect()
    }

    pub fn fail(message: &str) {
        let (text, title) = (wide(message), wide("njMule"));
        // SAFETY: both strings end with a null and outlive the call.
        unsafe { MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), MB_OK | MB_ICONERROR) };
    }

    /// Waits up to `millis` for the process `pid` to end, if it still runs.
    pub(super) fn wait_for(pid: u32, millis: u32) {
        // SAFETY: the handle is checked, waited on and closed here.
        unsafe {
            let process = OpenProcess(PROCESS_SYNCHRONIZE, 0, pid);
            if !process.is_null() {
                WaitForSingleObject(process, millis);
                CloseHandle(process);
            }
        }
    }

    /// Installs the package; 0 when Windows Installer succeeded (3010 included: a reboot would finish replacing files in
    /// use), else its exit code.
    pub fn apply_update(update: &Update) -> i32 {
        // The old njMule and the window that waits for it hold the files Windows Installer replaces.
        for &pid in &update.waits {
            wait_for(pid, WAIT_MILLIS);
        }
        let Some(system) = std::env::var_os("SystemRoot") else {
            fail("njMule cannot find Windows Installer.");
            return 2;
        };
        let mut command = Command::new(std::path::Path::new(&system).join("System32").join("msiexec.exe"));
        command.args(msiexec_args(update));
        // Installed for every user: the same installation, which Windows Installer asks the rights for.
        if update.all_users {
            command.raw_arg("MSIINSTALLPERUSER=\"\"");
        }
        let code = match command.status() {
            Ok(status) => status.code().unwrap_or(1603),
            Err(e) => e.raw_os_error().unwrap_or(1603),
        };
        if let Some(result) = &update.result {
            let _ = std::fs::write(result, format!("{code}\n"));
        }
        // The new version, or the old one when the installation failed, tells what happened.
        if let Some((program, args)) = update.relaunch.split_first() {
            if let Ok(child) = Command::new(program).args(args).spawn() {
                // SAFETY: no pointers.
                unsafe { AllowSetForegroundWindow(child.id()) };
            }
        }
        if code == 0 || code == REBOOT_REQUIRED {
            0
        } else {
            code
        }
    }

    /// Keeps the computer from sleeping until the njMule running as `pid` ends, or ends this program.
    pub fn keep_awake(pid: u32) -> i32 {
        // SAFETY: the handle is checked, waited on and closed here.
        unsafe {
            let process = OpenProcess(PROCESS_SYNCHRONIZE, 0, pid);
            if process.is_null() {
                return 1;
            }
            SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
            WaitForSingleObject(process, INFINITE);
            CloseHandle(process);
        }
        0
    }

    type TaskDialogIndirect = unsafe extern "system" fn(*const TASKDIALOGCONFIG, *mut i32, *mut i32, *mut i32) -> i32;

    /// Whether the user ticks the box that deletes the profile; unticked at first. The task dialog comes from the common
    /// controls 6, found at run time; a yes or no question stands in for it without them.
    fn profile_ticked() -> bool {
        let (title, question, text, tick) =
            (wide("Uninstall njMule"), wide(PROFILE_QUESTION), wide(PROFILE_TEXT), wide("Delete my njMule data"));
        // SAFETY: the strings end with a null and outlive the calls; the function found has TaskDialogIndirect's type.
        unsafe {
            let controls = LoadLibraryW(wide("comctl32.dll").as_ptr());
            let found =
                if controls.is_null() { None } else { GetProcAddress(controls, c"TaskDialogIndirect".as_ptr().cast()) };
            let Some(found) = found else {
                let asked = wide(&format!("{PROFILE_QUESTION}\n\n{PROFILE_TEXT}"));
                let style = MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2 | MB_SETFOREGROUND | MB_TOPMOST;
                return MessageBoxW(std::ptr::null_mut(), asked.as_ptr(), wide("njMule").as_ptr(), style) == IDYES;
            };
            let dialog: TaskDialogIndirect = std::mem::transmute(found);
            let mut config: TASKDIALOGCONFIG = std::mem::zeroed();
            config.cbSize = std::mem::size_of::<TASKDIALOGCONFIG>() as u32;
            config.dwFlags = TDF_ALLOW_DIALOG_CANCELLATION;
            config.pszWindowTitle = title.as_ptr();
            config.Anonymous1.pszMainIcon = TD_INFORMATION_ICON;
            config.pszMainInstruction = question.as_ptr();
            config.pszContent = text.as_ptr();
            config.pszVerificationText = tick.as_ptr();
            config.dwCommonButtons = TDCBF_OK_BUTTON;
            let (mut button, mut ticked) = (0, 0);
            dialog(&config, &mut button, std::ptr::null_mut(), &mut ticked) >= 0 && button == IDOK && ticked != 0
        }
    }

    /// At uninstall: lets the user choose to delete their njMule data too, moved to the Recycle Bin; the completed
    /// downloads, in the Downloads folder, stay.
    pub fn uninstall_profile() -> i32 {
        let folders: Vec<_> = ["APPDATA", "LOCALAPPDATA"]
            .iter()
            .filter_map(std::env::var_os)
            .map(|dir| std::path::Path::new(&dir).join("njMule"))
            .filter(|dir| dir.is_dir())
            .collect();
        if folders.is_empty() || !profile_ticked() {
            return 0;
        }
        // Each path ends with a null, the list with one more.
        let mut list: Vec<u16> = Vec::new();
        for folder in &folders {
            list.extend(folder.as_os_str().encode_wide());
            list.push(0);
        }
        list.push(0);
        // SAFETY: the list is double-null-terminated and outlives the call.
        unsafe {
            let mut operation: SHFILEOPSTRUCTW = std::mem::zeroed();
            operation.wFunc = FO_DELETE;
            operation.pFrom = list.as_ptr();
            operation.fFlags = (FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI) as u16;
            SHFileOperationW(&mut operation)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn words(line: &[&str]) -> Vec<String> {
        line.iter().map(|w| (*w).to_owned()).collect()
    }

    #[test]
    fn should_open_the_window_without_a_command() {
        let found = verb(&words(&["njmule-gui", "ed2k://|file|a|1|0123456789ABCDEF0123456789ABCDEF|/"]));

        assert!(found.is_none());
    }

    #[test]
    fn should_read_an_update_as_njmule_asks_for_it() {
        let found = verb(&words(&[
            "njmule-gui",
            "--apply-update",
            r"C:\cache\njmule-0.2.0.msi",
            "--wait",
            "41",
            "--wait",
            "42",
            "--ui",
            "quiet",
            "--all-users",
            "--log",
            "install.log",
            "--result",
            "msiexec.txt",
            "--relaunch",
            r"C:\njMule\njmule-gui.exe",
            "--config-dir",
            r"D:\profile",
        ]));

        assert_eq!(
            found,
            Some(Ok(Verb::ApplyUpdate(Update {
                msi: r"C:\cache\njmule-0.2.0.msi".into(),
                waits: vec![41, 42],
                quiet: true,
                all_users: true,
                log: Some("install.log".into()),
                result: Some("msiexec.txt".into()),
                relaunch: words(&[r"C:\njMule\njmule-gui.exe", "--config-dir", r"D:\profile"]),
            })))
        );
    }

    #[test]
    fn should_refuse_an_update_started_wrongly() {
        let found = verb(&words(&["njmule-gui", "--apply-update", "a.msi", "--later"]));

        assert!(matches!(found, Some(Err(_))));
    }

    #[test]
    fn should_leave_an_update_plan_to_the_engine() {
        let found = verb(&words(&["njmule-gui", "--apply-update", "plan.properties"]));

        assert!(found.is_none());
    }

    #[test]
    fn should_run_msiexec_passive_with_its_log_by_default() {
        let update = Update { msi: "a.msi".into(), log: Some("i.log".into()), ..Update::default() };

        let args = msiexec_args(&update);

        assert_eq!(args, words(&["/i", "a.msi", "/passive", "/norestart", "/l*v", "i.log"]));
    }

    #[test]
    fn should_wait_for_the_processes_named() {
        let found = waits(&words(&["njmule-gui", "--config-dir", "/data", "--wait", "41", "--wait", "x"]));

        assert_eq!(found, vec![41]);
    }

    #[test]
    fn should_keep_the_computer_awake_for_a_process() {
        let found = verb(&words(&["njmule-gui", "--keep-awake", "4242"]));

        assert_eq!(found, Some(Ok(Verb::KeepAwake(4242))));
    }
}
