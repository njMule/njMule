// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! `--service`: njMule as the Windows service "njMule", which `njmule-service.exe` beside `njmule.exe` runs headless
//! on the profile in `%ProgramData%\njMule` as the virtual account `NT SERVICE\njMule`.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use data_encoding::BASE64;
use rand::RngExt;

use crate::app::launch_options::{LaunchOptions, ServiceAction, WebUiSetup};
use crate::app::web_ui_password::{self, PasswordError, PasswordSource};
use crate::config::app_directories::AppDirectories;
use crate::remote::web_ui_settings::WebUiSettings;

pub const NAME: &str = "njMule";
pub const ACCOUNT: &str = r"NT SERVICE\njMule";
pub const FIREWALL_RULE: &str = "njMule (service)";
pub const HOST: &str = "njmule-service.exe";

pub const EXIT_OK: i32 = 0;
pub const EXIT_FAILURE: i32 = 1;
pub const EXIT_USAGE: i32 = 2;

// By SID: the names of the built-in accounts are translated.
const LOCAL_SYSTEM: &str = "*S-1-5-18";
const ADMINISTRATORS: &str = "*S-1-5-32-544";

// What njmule-service.exe returns: a state of the service for "status", else a Windows error.
const STOPPED: i32 = 1;
const STARTING: i32 = 2;
const STOPPING: i32 = 3;
const RUNNING: i32 = 4;
const NOT_INSTALLED: i32 = 1060;

/// What the commands need of Windows; the tests stand in for it.
pub trait Windows {
    fn applies(&self) -> bool;

    /// Whether this process has the administrator's rights.
    fn elevated(&mut self) -> io::Result<bool>;

    /// The running `njmule.exe`, when this process is one.
    fn program(&self) -> Option<PathBuf>;

    fn program_data(&self) -> PathBuf;

    /// A folder of this user's own, for the messages the elevated command hands back.
    fn own_folder(&self) -> io::Result<PathBuf>;

    /// Runs a program, drops its output and returns its exit code.
    fn run(&mut self, command: &[String]) -> io::Result<i32>;

    /// Runs `program` with the administrator's rights, which Windows asks the user for; its exit code.
    fn run_elevated(&mut self, program: &Path, args: &[String]) -> io::Result<i32>;
}

/// Runs `--service` against Windows itself.
pub fn run_for_this_system<W1: Write, W2: Write>(
    cli: &LaunchOptions,
    out: &mut W1,
    err: &mut W2,
    passwords: &mut dyn PasswordSource,
) -> i32 {
    let mut system = SystemWindows;
    run(&mut system, cli, out, err, passwords)
}

/// Runs `--service` against `system`.
pub fn run<W1: Write, W2: Write>(
    system: &mut dyn Windows,
    cli: &LaunchOptions,
    out: &mut W1,
    err: &mut W2,
    passwords: &mut dyn PasswordSource,
) -> i32 {
    let Some(task) = cli.service.as_ref() else {
        let _ = writeln!(err, "Missing --service action");
        return EXIT_USAGE;
    };
    let mut runner = Runner { system, out, err, passwords, said: String::new() };
    let code = match runner.execute(cli, task.action) {
        Ok(code) => code,
        Err(PasswordError::Usage(e)) => {
            runner.complain(&e.to_string());
            EXIT_USAGE
        }
        Err(PasswordError::Io(e)) => {
            runner.complain(&e.to_string());
            EXIT_FAILURE
        }
    };
    // The command without the administrator's rights shows what this one said in its own window.
    if let Some(output) = &task.output
        && let Err(e) = fs::write(output, &runner.said)
    {
        let _ = writeln!(runner.err, "Cannot write {}: {e}", output.display());
    }
    code
}

/// A password file is passed on as an absolute path: the new process starts in another folder.
pub fn web_ui_arguments(setup: &WebUiSetup) -> Vec<String> {
    let mut args = Vec::new();
    if let Some(port) = setup.port {
        args.push("--webui-port".to_owned());
        args.push(port.to_string());
    }
    if setup.lan {
        args.push("--webui-lan".to_owned());
    }
    if setup.api {
        args.push("--webui-api".to_owned());
    }
    for host in &setup.hosts {
        args.push("--webui-host".to_owned());
        args.push(host.clone());
    }
    for proxy in &setup.proxies {
        args.push("--webui-proxy".to_owned());
        args.push(proxy.clone());
    }
    if let Some(file) = &setup.password_file {
        let absolute = std::path::absolute(file).unwrap_or_else(|_| file.clone());
        args.push("--webui-password-file".to_owned());
        args.push(absolute.to_string_lossy().into_owned());
    } else if !setup.keep_password {
        args.push("--webui-password".to_owned());
    }
    args
}

/// The arguments as one command line, quoted as the C runtime of Windows reads them back.
pub fn command_line(args: &[String]) -> String {
    let mut line = String::new();
    for arg in args {
        if !line.is_empty() {
            line.push(' ');
        }
        if !arg.is_empty() && !arg.chars().any(|c| c == ' ' || c == '\t' || c == '"') {
            line.push_str(arg);
            continue;
        }
        line.push('"');
        let mut backslashes: usize = 0;
        for ch in arg.chars() {
            if ch == '\\' {
                backslashes = backslashes.saturating_add(1);
                continue;
            }
            // Backslashes before a quote are doubled, and the quote escaped.
            let repeat = if ch == '"' { backslashes.saturating_mul(2).saturating_add(1) } else { backslashes };
            for _ in 0..repeat {
                line.push('\\');
            }
            line.push(ch);
            backslashes = 0;
        }
        for _ in 0..backslashes.saturating_mul(2) {
            line.push('\\');
        }
        line.push('"');
    }
    line
}

struct Runner<'a, W1: Write, W2: Write> {
    system: &'a mut dyn Windows,
    out: &'a mut W1,
    err: &'a mut W2,
    passwords: &'a mut dyn PasswordSource,
    said: String,
}

impl<W1: Write, W2: Write> Runner<'_, W1, W2> {
    fn execute(&mut self, cli: &LaunchOptions, action: ServiceAction) -> Result<i32, PasswordError> {
        if !self.system.applies() {
            self.complain(
                "The service is for Windows; the deb and rpm packages install a systemd unit, njmule.service.",
            );
            return Ok(EXIT_USAGE);
        }
        let program = self.system.program();
        let folder =
            program.as_ref().and_then(|p| std::path::absolute(p).ok()).and_then(|p| p.parent().map(Path::to_path_buf));
        let (Some(program), Some(folder)) = (program, folder) else {
            self.complain(&format!("The service needs njMule's Windows package: {HOST} beside njmule.exe."));
            return Ok(EXIT_FAILURE);
        };
        let host = folder.join(HOST);
        if !host.is_file() {
            self.complain(&format!("The service needs njMule's Windows package: {HOST} beside njmule.exe."));
            return Ok(EXIT_FAILURE);
        }
        if action == ServiceAction::Status {
            return Ok(self.status(&host)?);
        }
        if !self.system.elevated()? {
            return Ok(self.elevated(cli, &program)?);
        }
        match action {
            ServiceAction::Install => self.install(cli, &program, &folder),
            ServiceAction::Uninstall => Ok(self.uninstall(&folder)?),
            ServiceAction::Start => Ok(self.step(&host, "start", "The service njMule runs.")?),
            ServiceAction::Stop => Ok(self.step(&host, "stop", "The service njMule has stopped.")?),
            ServiceAction::Status => Ok(self.status(&host)?),
        }
    }

    fn status(&mut self, host: &Path) -> io::Result<i32> {
        let state = self.system.run(&[host.to_string_lossy().into_owned(), "status".to_owned()])?;
        let text = match state {
            NOT_INSTALLED => "is not installed (njmule --service=install)".to_owned(),
            STOPPED => "is stopped".to_owned(),
            STARTING => "is starting".to_owned(),
            STOPPING => "is stopping".to_owned(),
            RUNNING => "runs".to_owned(),
            other => format!("is in state {other}"),
        };
        self.say(&format!("The service njMule {text}."));
        Ok(if state == RUNNING { EXIT_OK } else { EXIT_FAILURE })
    }

    // The same command once more, with the administrator's rights; its messages come back through a file.
    fn elevated(&mut self, cli: &LaunchOptions, program: &Path) -> io::Result<i32> {
        let Some(task) = cli.service.as_ref() else {
            return Ok(EXIT_USAGE);
        };
        let mut args = vec![format!("--service={}", action_name(task.action))];
        if let Some(level) = cli.log_level.as_deref() {
            args.push("--log-level".to_owned());
            args.push(level.to_owned());
        }
        if task.action == ServiceAction::Install
            && let Some(setup) = cli.web_ui.as_ref()
        {
            args.extend(web_ui_arguments(setup));
        }
        // Not the shared temporary folder: the user's own profile, which other users cannot write into.
        let folder = self.system.own_folder()?;
        fs::create_dir_all(&folder)?;
        let output = create_temp_file(&folder, "service-", ".txt")?;
        let _cleanup = RemoveOnDrop(output.clone());
        args.push("--service-output".to_owned());
        args.push(output.to_string_lossy().into_owned());
        let suffix = if task.action == ServiceAction::Install {
            "; the web interface password is asked in the window that opens."
        } else {
            "."
        };
        self.say(&format!("Windows asks for the administrator's rights{suffix}"));
        let code = self.system.run_elevated(program, &args)?;
        let told = fs::read_to_string(&output)?;
        if told.trim().is_empty() {
            self.complain("Nothing was changed: njMule did not get the administrator's rights.");
            return Ok(EXIT_FAILURE);
        }
        let _ = write!(self.out, "{told}");
        Ok(code)
    }

    fn install(&mut self, cli: &LaunchOptions, program: &Path, folder: &Path) -> Result<i32, PasswordError> {
        let host = folder.join(HOST);
        // A service that runs reads its settings again only when it starts.
        let running = self.system.run(&[host.to_string_lossy().into_owned(), "status".to_owned()])? == RUNNING;
        let root = self.profile();
        let config = AppDirectories::under_root(&root)?.config;
        fs::create_dir_all(&config)?;
        // Before any secret is written: what ProgramData holds, every user of this computer may read.
        check(
            self.system.run(&[
                "icacls".to_owned(),
                root.to_string_lossy().into_owned(),
                "/inheritance:r".to_owned(),
                "/grant:r".to_owned(),
                format!("{LOCAL_SYSTEM}:(OI)(CI)F"),
                format!("{ADMINISTRATORS}:(OI)(CI)F"),
                "/Q".to_owned(),
            ])?,
            "close the profile to the other users",
        )?;
        self.set_password(cli, &config)?;
        check(self.system.run(&[host.to_string_lossy().into_owned(), "install".to_owned()])?, "register the service")?;
        // The virtual account reads njMule's programs and keeps its profile, and nothing else of this computer.
        check(
            self.system.run(&[
                "icacls".to_owned(),
                root.to_string_lossy().into_owned(),
                "/grant".to_owned(),
                format!("{ACCOUNT}:(OI)(CI)M"),
                "/T".to_owned(),
                "/Q".to_owned(),
            ])?,
            "give the service its profile",
        )?;
        check(
            self.system.run(&[
                "icacls".to_owned(),
                folder.to_string_lossy().into_owned(),
                "/grant".to_owned(),
                format!("{ACCOUNT}:(OI)(CI)RX"),
                "/T".to_owned(),
                "/Q".to_owned(),
            ])?,
            "let the service run njMule's programs",
        )?;
        // A rule of an earlier installation may name another folder.
        let _ = self.system.run(&[
            "netsh".to_owned(),
            "advfirewall".to_owned(),
            "firewall".to_owned(),
            "delete".to_owned(),
            "rule".to_owned(),
            format!("name={FIREWALL_RULE}"),
        ])?;
        check(
            self.system.run(&[
                "netsh".to_owned(),
                "advfirewall".to_owned(),
                "firewall".to_owned(),
                "add".to_owned(),
                "rule".to_owned(),
                format!("name={FIREWALL_RULE}"),
                "dir=in".to_owned(),
                "action=allow".to_owned(),
                format!("program={}", program.display()),
                "enable=yes".to_owned(),
                "profile=private,domain".to_owned(),
            ])?,
            "let other clients reach it through the firewall",
        )?;
        if running {
            check(self.system.run(&[host.to_string_lossy().into_owned(), "stop".to_owned()])?, "stop the service")?;
        }
        check(self.system.run(&[host.to_string_lossy().into_owned(), "start".to_owned()])?, "start the service")?;
        let settings = WebUiSettings::load(&config);
        self.say(&format!(
            "njMule runs as the Windows service \"njMule\", started with Windows, on the profile in {}.",
            root.display()
        ));
        let lan_note = if settings.lan { ", open to the other computers of the network." } else { "" };
        self.say(&format!("Its web interface: http://localhost:{}/{lan_note}", settings.port));
        self.say(&format!(
            "The firewall rule \"{FIREWALL_RULE}\" lets other clients in on private and domain networks."
        ));
        Ok(EXIT_OK)
    }

    // The options alone change the service's settings and keep its password, which is asked only when there is none.
    fn set_password(&mut self, cli: &LaunchOptions, config: &Path) -> Result<(), PasswordError> {
        if web_ui_password::set_from_file(cli, config)? {
            return Ok(());
        }
        let setup = cli.web_ui.as_ref();
        let keep = setup.is_none_or(|s| s.keep_password);
        if keep && !WebUiSettings::load(config).password_hash.is_empty() {
            if let Some(setup) = setup {
                web_ui_password::update(config, setup, None)?;
            }
            return Ok(());
        }
        self.say("The web interface of the service needs a password.");
        let fallback = WebUiSetup::default();
        web_ui_password::set_typed(setup.unwrap_or(&fallback), config, self.passwords)?;
        Ok(())
    }

    fn uninstall(&mut self, folder: &Path) -> io::Result<i32> {
        let host = folder.join(HOST);
        if self.system.run(&[host.to_string_lossy().into_owned(), "status".to_owned()])? == NOT_INSTALLED {
            self.say("The service njMule is not installed.");
            return Ok(EXIT_OK);
        }
        let root = self.profile();
        // Stopped while the account still reaches its profile, where njMule saves and its exit is asked; the rights go
        // next, before the service: once it is gone, the account's name no longer resolves.
        check(self.system.run(&[host.to_string_lossy().into_owned(), "stop".to_owned()])?, "stop the service")?;
        let _ = self.system.run(&[
            "icacls".to_owned(),
            folder.to_string_lossy().into_owned(),
            "/remove".to_owned(),
            ACCOUNT.to_owned(),
            "/T".to_owned(),
            "/Q".to_owned(),
        ])?;
        let _ = self.system.run(&[
            "icacls".to_owned(),
            root.to_string_lossy().into_owned(),
            "/remove".to_owned(),
            ACCOUNT.to_owned(),
            "/T".to_owned(),
            "/Q".to_owned(),
        ])?;
        check(self.system.run(&[host.to_string_lossy().into_owned(), "uninstall".to_owned()])?, "remove the service")?;
        let _ = self.system.run(&[
            "netsh".to_owned(),
            "advfirewall".to_owned(),
            "firewall".to_owned(),
            "delete".to_owned(),
            "rule".to_owned(),
            format!("name={FIREWALL_RULE}"),
        ])?;
        self.say(&format!("The service njMule is removed; its profile stays in {}.", root.display()));
        Ok(EXIT_OK)
    }

    fn step(&mut self, host: &Path, verb: &str, done: &str) -> io::Result<i32> {
        let code = self.system.run(&[host.to_string_lossy().into_owned(), verb.to_owned()])?;
        if code == NOT_INSTALLED {
            self.complain("The service njMule is not installed: njmule --service=install");
            return Ok(EXIT_FAILURE);
        }
        check(code, &format!("{verb} the service"))?;
        self.say(done);
        Ok(EXIT_OK)
    }

    fn profile(&self) -> PathBuf {
        self.system.program_data().join(NAME)
    }

    fn say(&mut self, text: &str) {
        let _ = writeln!(self.out, "{text}");
        self.said.push_str(text);
        self.said.push('\n');
    }

    fn complain(&mut self, text: &str) {
        let _ = writeln!(self.err, "{text}");
        self.said.push_str(text);
        self.said.push('\n');
    }
}

/// Windows itself.
pub struct SystemWindows;

impl Windows for SystemWindows {
    fn applies(&self) -> bool {
        cfg!(windows)
    }

    // fltmc answers only an administrator.
    fn elevated(&mut self) -> io::Result<bool> {
        Ok(self.run(&["fltmc".to_owned()])? == 0)
    }

    fn program(&self) -> Option<PathBuf> {
        std::env::current_exe().ok().filter(|path| {
            path.file_name().and_then(|name| name.to_str()).is_some_and(|name| name.eq_ignore_ascii_case("njmule.exe"))
        })
    }

    fn program_data(&self) -> PathBuf {
        if let Some(value) = std::env::var_os("ProgramData")
            && !value.is_empty()
        {
            return PathBuf::from(value);
        }
        let drive = std::env::var("SystemDrive").unwrap_or_else(|_| "C:".to_owned());
        PathBuf::from(drive).join("ProgramData")
    }

    fn own_folder(&self) -> io::Result<PathBuf> {
        Ok(AppDirectories::resolve_current(None)?.cache)
    }

    fn run(&mut self, command: &[String]) -> io::Result<i32> {
        let Some((program, args)) = command.split_first() else {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "empty command"));
        };
        let status = Command::new(program).args(args).stdout(Stdio::null()).stderr(Stdio::null()).status()?;
        Ok(status.code().unwrap_or(1))
    }

    // Start-Process -Verb RunAs is what asks Windows for the rights; the script goes encoded, so that no quote of its
    // arguments meets the command line's own.
    fn run_elevated(&mut self, program: &Path, args: &[String]) -> io::Result<i32> {
        let script = format!(
            "$p = Start-Process -FilePath {} -ArgumentList {} -Verb RunAs -Wait -PassThru; exit $p.ExitCode",
            quoted(&program.to_string_lossy()),
            quoted(&command_line(args))
        );
        let utf16: Vec<u8> = script.encode_utf16().flat_map(u16::to_le_bytes).collect();
        let encoded = BASE64.encode(&utf16);
        self.run(&[
            "powershell.exe".to_owned(),
            "-NoProfile".to_owned(),
            "-NonInteractive".to_owned(),
            "-EncodedCommand".to_owned(),
            encoded,
        ])
    }
}

fn check(code: i32, what: &str) -> io::Result<()> {
    if code == 0 { Ok(()) } else { Err(io::Error::other(format!("Cannot {what} (Windows error {code})."))) }
}

fn quoted(text: &str) -> String {
    format!("'{}'", text.replace('\'', "''"))
}

fn action_name(action: ServiceAction) -> &'static str {
    match action {
        ServiceAction::Install => "install",
        ServiceAction::Uninstall => "uninstall",
        ServiceAction::Start => "start",
        ServiceAction::Stop => "stop",
        ServiceAction::Status => "status",
    }
}

fn create_temp_file(dir: &Path, prefix: &str, suffix: &str) -> io::Result<PathBuf> {
    let mut rng = rand::rng();
    for _ in 0..64 {
        let id: u64 = rng.random();
        let path = dir.join(format!("{prefix}{id:016x}{suffix}"));
        match fs::OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(_) => return Ok(path),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
            Err(e) => return Err(e),
        }
    }
    Err(io::Error::new(io::ErrorKind::AlreadyExists, "cannot create temporary service output file"))
}

struct RemoveOnDrop(PathBuf);

impl Drop for RemoveOnDrop {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}
