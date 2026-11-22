// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Makes njMule the program that opens ed2k links for this user (clicked in a browser, for instance) and gives them
//! back: what handled them before, such as eMule, is saved first and restored afterwards.

use std::fs::{self, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex, PoisonError, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use encoding_rs::UTF_16BE;
use rand::RngExt;

/// The id of njMule's desktop entry on Linux.
pub const DESKTOP_ID: &str = "org.njmule.njMule.desktop";

// reg.exe, xdg-mime and their like answer in well under a second.
const COMMAND_TIMEOUT: Duration = Duration::from_secs(30);
// The last of the output of a command that has ended comes at once, unless something it started holds it open.
const OUTPUT_AFTER_END: Duration = Duration::from_secs(2);
const POLL: Duration = Duration::from_millis(20);
const UTF16LE_BOM: char = '\u{FEFF}';

/// The exit code and combined output of a system command.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandResult {
    pub exit: i32,
    pub output: String,
}

/// Runs a system command and returns its exit code and output.
pub trait Shell: Send + Sync {
    fn run(&self, command: &[String]) -> io::Result<CommandResult>;
}

impl<F> Shell for F
where
    F: Fn(&[String]) -> io::Result<CommandResult> + Send + Sync,
{
    fn run(&self, command: &[String]) -> io::Result<CommandResult> {
        self(command)
    }
}

/// Runs a system command on this computer through [`run_command`].
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemShell;

impl Shell for SystemShell {
    fn run(&self, command: &[String]) -> io::Result<CommandResult> {
        run_command(command)
    }
}

/// Runs `command` with nothing on its input, timing out after 30 s.
pub fn run_command(command: &[String]) -> io::Result<CommandResult> {
    run_command_with_timeout(command, COMMAND_TIMEOUT)
}

/// Runs `command` with nothing on its input. Past `timeout` it is stopped. Once it has ended, what it started may keep
/// its output open: only the output come in the next two seconds counts.
pub fn run_command_with_timeout(command: &[String], timeout: Duration) -> io::Result<CommandResult> {
    let Some((name, arguments)) = command.split_first() else {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "no program to run"));
    };
    let mut child = Command::new(name)
        .args(arguments)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    let collected = Arc::new(Mutex::new(Vec::new()));
    let stdout = child.stdout.take().map(|stream| copy_output(stream, Arc::clone(&collected)));
    let stderr = child.stderr.take().map(|stream| copy_output(stream, Arc::clone(&collected)));
    let status = wait(&mut child, timeout).inspect_err(|_| {
        let _ = child.kill();
        let _ = child.wait();
    })?;
    let Some(status) = status else {
        let _ = child.kill();
        let _ = child.wait();
        return Err(io::Error::new(
            io::ErrorKind::TimedOut,
            format!("{name} did not finish in {} s", timeout.as_secs()),
        ));
    };
    let deadline = Instant::now().checked_add(OUTPUT_AFTER_END);
    for done in [stdout, stderr].into_iter().flatten() {
        let left = deadline.map_or(Duration::ZERO, |end| end.saturating_duration_since(Instant::now()));
        if matches!(done.recv_timeout(left), Err(mpsc::RecvTimeoutError::Timeout)) {
            tracing::debug!("{name} ended with its output still open");
        }
    }
    let output = collected.lock().unwrap_or_else(PoisonError::into_inner).clone();
    Ok(CommandResult { exit: status.code().unwrap_or(-1), output: String::from_utf8_lossy(&output).into_owned() })
}

fn wait(child: &mut Child, timeout: Duration) -> io::Result<Option<std::process::ExitStatus>> {
    let deadline = Instant::now().checked_add(timeout);
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(Some(status));
        }
        if deadline.is_some_and(|end| Instant::now() >= end) {
            return Ok(None);
        }
        thread::sleep(POLL);
    }
}

fn copy_output(mut stream: impl Read + Send + 'static, collected: Arc<Mutex<Vec<u8>>>) -> mpsc::Receiver<()> {
    let (sender, receiver) = mpsc::channel();
    let _ = thread::Builder::new().name("njmule-command-output".to_owned()).spawn(move || {
        let mut buf = [0u8; 4096];
        loop {
            match stream.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if let Some(slice) = buf.get(..n) {
                        collected.lock().unwrap_or_else(PoisonError::into_inner).extend_from_slice(slice);
                    }
                }
            }
        }
        let _ = sender.send(());
    });
    receiver
}

/// The association of the `ed2k` scheme for a system that lets a program take and give it back.
pub enum LinkAssociation {
    Windows(WindowsLinks),
    Linux(LinuxLinks),
}

impl LinkAssociation {
    /// The association for this system, for the installed desktop program; `None` where it cannot be made: on macOS,
    /// where links come as system events, and when running from a development build.
    pub fn for_this_system(config_dir: PathBuf) -> Option<Self> {
        let exe = std::env::current_exe().ok()?.to_string_lossy().into_owned();
        let os = std::env::consts::OS;
        let program = desktop_program(&exe, os, |path| path.is_file());
        let xdg = std::env::var("XDG_CONFIG_HOME").ok();
        let home = dirs::home_dir().map(|dir| dir.to_string_lossy().into_owned()).unwrap_or_default();
        Self::for_program(config_dir, Arc::new(SystemShell), PathBuf::from(program), os, xdg.as_deref(), &home)
    }

    /// The association for `program` on `os_name`, with the system's parts passed in so that tests never touch the
    /// real system.
    pub fn for_program(
        config_dir: PathBuf,
        shell: Arc<dyn Shell>,
        program: PathBuf,
        os_name: &str,
        xdg_config_home: Option<&str>,
        home: &str,
    ) -> Option<Self> {
        if !is_desktop_program(&program) {
            return None;
        }
        let os = os_name.to_ascii_lowercase();
        if os.starts_with("windows") {
            return Some(Self::Windows(WindowsLinks::new(config_dir, shell, program)));
        }
        if os.starts_with("linux") {
            let base = xdg_config_home
                .filter(|value| !value.trim().is_empty())
                .map_or_else(|| PathBuf::from(home).join(".config"), PathBuf::from);
            return Some(Self::Linux(LinuxLinks::new(config_dir, shell, base.join("mimeapps.list"))));
        }
        None
    }

    /// Whether ed2k links open in njMule now.
    pub fn is_ours(&self) -> io::Result<bool> {
        match self {
            Self::Windows(windows) => windows.is_ours(),
            Self::Linux(linux) => linux.is_ours(),
        }
    }

    /// Makes njMule open ed2k links, saving what opened them before.
    pub fn take(&self) -> io::Result<()> {
        match self {
            Self::Windows(windows) => windows.take(),
            Self::Linux(linux) => linux.take(),
        }
    }

    /// Gives ed2k links back to what opened them before njMule took them, or to nothing.
    pub fn give_back(&self) -> io::Result<()> {
        match self {
            Self::Windows(windows) => windows.give_back(),
            Self::Linux(linux) => linux.give_back(),
        }
    }
}

/// The program the system starts for the desktop app running as `command`: the engine, `njmule.exe` or `njmule`, runs
/// started by the desktop window beside it, `njmule-gui.exe` or `njmule-gui`.
pub fn desktop_program<F: Fn(&Path) -> bool>(command: &str, os_name: &str, is_file: F) -> String {
    let suffix = if os_name.to_ascii_lowercase().starts_with("windows") { ".exe" } else { "" };
    let split = command.rfind(['\\', '/']).and_then(|index| index.checked_add(1)).unwrap_or(0);
    let (dir, name) = command.split_at_checked(split).unwrap_or(("", command));
    if !name.eq_ignore_ascii_case(&format!("njmule{suffix}")) {
        return command.to_owned();
    }
    let window = format!("{dir}njmule-gui{suffix}");
    if is_file(Path::new(&window)) { window } else { command.to_owned() }
}

/// Whether `command` names the installed desktop window executable.
pub fn is_desktop_program(command: &Path) -> bool {
    let Some(name) = command.file_name().and_then(|name| name.to_str()) else {
        return false;
    };
    name.eq_ignore_ascii_case("njmule-gui.exe") || name.eq_ignore_ascii_case("njmule-gui")
}

/// The user's `ed2k` key in the Windows registry, exported before njMule writes its own and imported back.
pub struct WindowsLinks {
    config_dir: PathBuf,
    shell: Arc<dyn Shell>,
    program: PathBuf,
}

impl WindowsLinks {
    pub const KEY: &str = "HKCU\\Software\\Classes\\ed2k";
    pub const COMMAND_KEY: &str = "HKCU\\Software\\Classes\\ed2k\\shell\\open\\command";
    pub const BACKUP: &str = "ed2k-handler-backup.reg";
    const KEY_PATH: &str = "HKEY_CURRENT_USER\\Software\\Classes\\ed2k";

    pub fn new(config_dir: PathBuf, shell: Arc<dyn Shell>, program: PathBuf) -> Self {
        Self { config_dir, shell, program }
    }

    pub fn is_ours(&self) -> io::Result<bool> {
        // reg query prints in the console's code page, which may lack letters of the program's path; an export is in
        // UTF-16.
        let file = WorkFile::create(&self.config_dir, "ed2k-", ".reg")?;
        let export = self.shell.run(&strings(["reg", "export", Self::COMMAND_KEY, &file.path_string(), "/y"]))?;
        if export.exit != 0 {
            return Ok(false);
        }
        let ours = self.command().to_lowercase();
        let text = read_utf16(file.path())?;
        Ok(Self::default_value(&text).is_some_and(|value| value.to_lowercase().contains(&ours)))
    }

    pub fn take(&self) -> io::Result<()> {
        let backup = self.config_dir.join(Self::BACKUP);
        if !self.is_ours()? && self.shell.run(&strings(["reg", "query", Self::KEY]))?.exit == 0 {
            check(&*self.shell, &strings(["reg", "export", Self::KEY, &backup.to_string_lossy(), "/y"]))?;
        }
        self.import_reg(&self.ours())?;
        tracing::info!("ed2k links now open in njMule");
        Ok(())
    }

    pub fn give_back(&self) -> io::Result<()> {
        if !self.is_ours()? {
            return Ok(());
        }
        self.import_reg(&format!("[-{}]\r\n", Self::KEY_PATH))?;
        let backup = self.config_dir.join(Self::BACKUP);
        if backup.exists() {
            check(&*self.shell, &strings(["reg", "import", &backup.to_string_lossy()]))?;
            fs::remove_file(&backup)?;
        }
        tracing::info!("ed2k links given back");
        Ok(())
    }

    pub fn command(&self) -> String {
        format!("\"{}\" \"%1\"", self.program.to_string_lossy())
    }

    // A .reg file avoids the quoting rules of reg add for values with quotes in them.
    pub fn ours(&self) -> String {
        format!(
            "[{}]\r\n@=\"URL:ed2k Protocol\"\r\n\"URL Protocol\"=\"\"\r\n\r\n\
             [{}\\DefaultIcon]\r\n@={}\r\n\r\n\
             [{}\\shell\\open\\command]\r\n@={}\r\n",
            Self::KEY_PATH,
            Self::KEY_PATH,
            Self::reg_string(&format!("\"{}\",0", self.program.to_string_lossy())),
            Self::KEY_PATH,
            Self::reg_string(&self.command()),
        )
    }

    fn import_reg(&self, body: &str) -> io::Result<()> {
        let file = WorkFile::create(&self.config_dir, "ed2k-", ".reg")?;
        // reg import takes version 5 files in UTF-16 with a byte order mark.
        write_utf16le(file.path(), &format!("{UTF16LE_BOM}Windows Registry Editor Version 5.00\r\n\r\n{body}"))?;
        check(&*self.shell, &strings(["reg", "import", &file.path_string()]))?;
        Ok(())
    }

    /// The default value of an exported key when it is a string, without the escapes of the format.
    pub fn default_value(reg: &str) -> Option<String> {
        for line in reg.split('\n').map(|line| line.strip_suffix('\r').unwrap_or(line)) {
            if let Some(quoted) = line.strip_prefix("@=\"").and_then(|rest| rest.strip_suffix('"')) {
                return Some(unescape_reg_string(quoted));
            }
        }
        None
    }

    pub fn reg_string(value: &str) -> String {
        format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
    }
}

/// The user's default handler of the `ed2k` scheme in the desktop's MIME associations on Linux.
pub struct LinuxLinks {
    config_dir: PathBuf,
    shell: Arc<dyn Shell>,
    mime_apps: PathBuf,
}

impl LinuxLinks {
    pub const SCHEME: &str = "x-scheme-handler/ed2k";
    pub const PREVIOUS: &str = "ed2k-handler-previous";

    pub fn new(config_dir: PathBuf, shell: Arc<dyn Shell>, mime_apps: PathBuf) -> Self {
        Self { config_dir, shell, mime_apps }
    }

    pub fn is_ours(&self) -> io::Result<bool> {
        Ok(self.current()? == DESKTOP_ID)
    }

    pub fn take(&self) -> io::Result<()> {
        let before = self.current()?;
        if before != DESKTOP_ID {
            fs::write(self.config_dir.join(Self::PREVIOUS), format!("{before}\n"))?;
        }
        check(&*self.shell, &strings(["xdg-mime", "default", DESKTOP_ID, Self::SCHEME]))?;
        tracing::info!("ed2k links now open in njMule");
        Ok(())
    }

    pub fn give_back(&self) -> io::Result<()> {
        if !self.is_ours()? {
            return Ok(());
        }
        let previous = self.config_dir.join(Self::PREVIOUS);
        let before = if previous.exists() { fs::read_to_string(&previous)?.trim().to_owned() } else { String::new() };
        if before.ends_with(".desktop") {
            check(&*self.shell, &strings(["xdg-mime", "default", &before, Self::SCHEME]))?;
        } else {
            self.remove_our_default()?;
        }
        if previous.exists() {
            fs::remove_file(&previous)?;
        }
        tracing::info!("ed2k links given back");
        Ok(())
    }

    // xdg-mime cannot unset a default: our id goes from the user's lists, and a list left empty goes too.
    fn remove_our_default(&self) -> io::Result<()> {
        if !self.mime_apps.exists() {
            return Ok(());
        }
        let raw = fs::read_to_string(&self.mime_apps)?;
        let lines: Vec<String> = raw.lines().map(str::to_owned).collect();
        let mut kept = Vec::with_capacity(lines.len());
        let prefix = format!("{}=", Self::SCHEME);
        for line in &lines {
            let entry = line.trim();
            let Some(rest) = entry.strip_prefix(&prefix) else {
                kept.push(line.clone());
                continue;
            };
            let ids: Vec<&str> = rest.split(';').map(str::trim).filter(|id| !id.is_empty()).collect();
            let others: Vec<&str> = ids.iter().copied().filter(|id| *id != DESKTOP_ID).collect();
            if others.len() == ids.len() {
                kept.push(line.clone());
            } else if !others.is_empty() {
                kept.push(format!("{}={};", Self::SCHEME, others.join(";")));
            }
        }
        if kept != lines {
            // A list linked from elsewhere, such as a dotfiles repository, stays a link: its target changes.
            let target = fs::canonicalize(&self.mime_apps)?;
            let name = target.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
            let tmp = target.with_file_name(format!("{name}.tmp"));
            fs::write(&tmp, format!("{}\n", kept.join("\n")))?;
            fs::rename(&tmp, &target)?;
        }
        Ok(())
    }

    fn current(&self) -> io::Result<String> {
        let query = self.shell.run(&strings(["xdg-mime", "query", "default", Self::SCHEME]))?;
        Ok(if query.exit == 0 { query.output.trim().to_owned() } else { String::new() })
    }
}

pub(crate) fn check(shell: &dyn Shell, command: &[String]) -> io::Result<CommandResult> {
    let result = shell.run(command)?;
    if result.exit != 0 {
        return Err(io::Error::other(format!("{} failed: {}", command.join(" "), result.output.trim())));
    }
    Ok(result)
}

pub(crate) fn strings<const N: usize>(parts: [&str; N]) -> Vec<String> {
    parts.into_iter().map(str::to_owned).collect()
}

pub(crate) fn unescape_reg_string(quoted: &str) -> String {
    let mut value = String::with_capacity(quoted.len());
    let mut chars = quoted.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            if let Some(escaped) = chars.next() {
                value.push(escaped);
            } else {
                value.push('\\');
            }
        } else {
            value.push(c);
        }
    }
    value
}

pub(crate) fn write_utf16le(path: &Path, text: &str) -> io::Result<()> {
    let mut bytes = Vec::with_capacity(text.len().saturating_mul(2));
    for unit in text.encode_utf16() {
        bytes.extend_from_slice(&unit.to_le_bytes());
    }
    fs::write(path, bytes)
}

pub(crate) fn read_utf16(path: &Path) -> io::Result<String> {
    let bytes = fs::read(path)?;
    let (encoding, bom_len) = encoding_rs::Encoding::for_bom(&bytes).unwrap_or((UTF_16BE, 0));
    let body = bytes.get(bom_len..).unwrap_or_default();
    let (decoded, _) = encoding.decode_without_bom_handling(body);
    Ok(decoded.into_owned())
}

/// A temporary work file in `dir` that removes itself when dropped.
pub(crate) struct WorkFile {
    path: PathBuf,
}

impl WorkFile {
    pub(crate) fn create(dir: &Path, prefix: &str, suffix: &str) -> io::Result<Self> {
        let mut rng = rand::rng();
        for _ in 0..64 {
            let id: u64 = rng.random();
            let path = dir.join(format!("{prefix}{id:016x}{suffix}"));
            match OpenOptions::new().write(true).create_new(true).open(&path) {
                Ok(mut file) => {
                    file.flush()?;
                    return Ok(Self { path });
                }
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
                Err(e) => return Err(e),
            }
        }
        Err(io::Error::new(io::ErrorKind::AlreadyExists, "cannot create a work file"))
    }

    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    pub(crate) fn path_string(&self) -> String {
        self.path.to_string_lossy().into_owned()
    }
}

impl Drop for WorkFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}
