// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sets the web interface password of the command line, typed or read from a file such as a Docker secret.

use std::fmt;
use std::fs::File;
use std::io::{self, BufRead, IsTerminal, Read, Write};
use std::path::Path;

use zeroize::Zeroizing;

use crate::api::nj_mule_info;
use crate::app::launch_options::{Action, LaunchOptions, UsageError, WebUiSetup};
use crate::config::app_directories::AppDirectories;
use crate::remote::passwords;
use crate::remote::web_ui_settings::WebUiSettings;

/// A password file holds one line: anything longer is not one.
pub const MAX_FILE_BYTES: usize = 4096;

pub const EXIT_OK: i32 = 0;
pub const EXIT_FAILURE: i32 = 1;
pub const EXIT_USAGE: i32 = 2;

/// Where a password or short answer comes from: the terminal, or a line of standard input.
pub trait PasswordSource {
    /// The text typed after `prompt`, or `None` at the end of the input.
    fn read(&mut self, prompt: &str) -> io::Result<Option<Zeroizing<String>>>;
}

/// Reads passwords without echo on a terminal, otherwise one per line of standard input.
pub struct TerminalPasswords;

impl PasswordSource for TerminalPasswords {
    fn read(&mut self, prompt: &str) -> io::Result<Option<Zeroizing<String>>> {
        if io::stdin().is_terminal() {
            match rpassword::prompt_password(prompt) {
                Ok(text) => Ok(Some(Zeroizing::new(text))),
                Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => Ok(None),
                Err(e) => Err(e),
            }
        } else {
            let mut stdout = io::stdout();
            stdout.write_all(prompt.as_bytes())?;
            stdout.flush()?;
            let mut line = Zeroizing::new(String::new());
            let count = io::stdin().lock().read_line(&mut line)?;
            if count == 0 {
                return Ok(None);
            }
            while line.ends_with(['\n', '\r']) {
                line.pop();
            }
            Ok(Some(line))
        }
    }
}

/// A short answer typed in reply; `None` at the end of the input.
pub fn answer(input: &mut dyn PasswordSource, prompt: &str) -> Option<String> {
    input.read(prompt).ok().flatten().map(|value| (*value).clone())
}

#[derive(Debug)]
pub enum PasswordError {
    Usage(UsageError),
    Io(io::Error),
}

impl fmt::Display for PasswordError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Usage(e) => write!(f, "{e}"),
            Self::Io(e) => write!(f, "{e}"),
        }
    }
}

impl std::error::Error for PasswordError {}

impl From<UsageError> for PasswordError {
    fn from(value: UsageError) -> Self {
        Self::Usage(value)
    }
}

impl From<io::Error> for PasswordError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

/// Sets the password of `--webui-password-file`, when the command line names one: with `--webui-password` as a
/// set-up of its own, otherwise as at each start, where the options the command line does not give stay as the web
/// interface saved them.
pub fn set_from_file(cli: &LaunchOptions, config: &Path) -> Result<bool, PasswordError> {
    let Some(setup) = cli.web_ui.as_ref() else {
        return Ok(false);
    };
    let Some(file) = setup.password_file.as_deref() else {
        return Ok(false);
    };
    let password = read(file)?;
    if cli.action == Action::WebUiPassword {
        save(config, setup, &password)?;
    } else {
        update(config, setup, Some(&password))?;
    }
    tracing::info!("Web interface password set from {}", file.display());
    Ok(true)
}

/// Turns the web interface on with `password` and the port, LAN, API, host name, proxy and guest choices of `setup`.
pub fn save(config: &Path, setup: &WebUiSetup, password: &str) -> Result<WebUiSettings, PasswordError> {
    let old = WebUiSettings::load(config);
    let admin = passwords::hash(password);
    let guest_password_hash = guest_hash(setup, &admin, &old)?;
    let settings = WebUiSettings {
        enabled: true,
        port: setup.port.unwrap_or(old.port),
        lan: setup.lan,
        password_hash: admin,
        api: setup.api || old.api,
        hosts: setup.hosts.clone(),
        proxies: setup.proxies.clone(),
        guest_password_hash,
    };
    settings.save(config)?;
    Ok(settings)
}

/// Turns the web interface on with `password`, the saved one when `None`, changing only the choices `setup` gives:
/// what was set in the web interface itself stays.
pub fn update(config: &Path, setup: &WebUiSetup, password: Option<&str>) -> Result<WebUiSettings, PasswordError> {
    let old = WebUiSettings::load(config);
    let admin = match password {
        Some(value) => passwords::hash(value),
        None => old.password_hash.clone(),
    };
    let guest_password_hash = guest_hash(setup, &admin, &old)?;
    let settings = WebUiSettings {
        enabled: true,
        port: setup.port.unwrap_or(old.port),
        lan: setup.lan || old.lan,
        password_hash: admin,
        api: setup.api || old.api,
        hosts: if setup.hosts.is_empty() { old.hosts } else { setup.hosts.clone() },
        proxies: if setup.proxies.is_empty() { old.proxies } else { setup.proxies.clone() },
        guest_password_hash,
    };
    settings.save(config)?;
    Ok(settings)
}

/// Prompts twice for a web interface password and saves it with `setup`.
pub fn set_typed(
    setup: &WebUiSetup,
    config: &Path,
    passwords: &mut dyn PasswordSource,
) -> Result<WebUiSettings, PasswordError> {
    let first = passwords.read("Web interface password: ")?;
    let second = passwords.read("The same password again: ")?;
    let (Some(first), Some(second)) = (first, second) else {
        return Err(UsageError(format!("The password needs at least {} characters.", passwords::MIN_LENGTH)).into());
    };
    if first.chars().count() < passwords::MIN_LENGTH {
        return Err(UsageError(format!("The password needs at least {} characters.", passwords::MIN_LENGTH)).into());
    }
    if first.as_str() != second.as_str() {
        return Err(UsageError("The two passwords differ.".to_owned()).into());
    }
    save(config, setup, &first)
}

/// Runs the `--webui-password` command.
pub fn run_command<W1: Write, W2: Write>(
    cli: &LaunchOptions,
    out: &mut W1,
    err: &mut W2,
    passwords: &mut dyn PasswordSource,
) -> i32 {
    let config = match AppDirectories::resolve_current(cli.config_dir.as_deref()) {
        Ok(dirs) => dirs.config,
        Err(e) => {
            let _ = writeln!(err, "Cannot set the web interface password: {e}");
            return EXIT_FAILURE;
        }
    };
    let result = match set_from_file(cli, &config) {
        Ok(true) => Ok(WebUiSettings::load(&config)),
        Ok(false) => match cli.web_ui.as_ref() {
            Some(setup) => set_typed(setup, &config, passwords),
            None => Err(UsageError("Missing web interface setup".to_owned()).into()),
        },
        Err(e) => Err(e),
    };
    match result {
        Ok(settings) => {
            let suffix = if settings.lan {
                ", open to other computers.".to_owned()
            } else {
                format!(": http://localhost:{}/", settings.port)
            };
            let _ =
                writeln!(out, "The web interface starts with {} on port {}{suffix}", nj_mule_info::NAME, settings.port);
            EXIT_OK
        }
        Err(PasswordError::Usage(e)) => {
            let _ = writeln!(err, "{e}");
            EXIT_USAGE
        }
        Err(PasswordError::Io(e)) => {
            let _ = writeln!(err, "Cannot set the web interface password: {e}");
            EXIT_FAILURE
        }
    }
}

/// Reads the first line of `file` as a web interface password.
pub fn read(file: &Path) -> Result<Zeroizing<String>, UsageError> {
    read_kind(file, "web interface password")
}

// The guest's password may not be the web interface's, whose holder would log in as a guest otherwise.
fn guest_hash(setup: &WebUiSetup, admin: &str, old: &WebUiSettings) -> Result<String, UsageError> {
    if setup.no_guest {
        return Ok(String::new());
    }
    let Some(file) = setup.guest_password_file.as_deref() else {
        return Ok(old.guest_password_hash.clone());
    };
    let guest = read_kind(file, "web interface guest password")?;
    if !admin.is_empty() && passwords::matches(&guest, admin) {
        return Err(UsageError(format!(
            "The guest password in {} is the web interface password: choose another one.",
            file.display()
        )));
    }
    let hash = passwords::hash(&guest);
    tracing::info!("Web interface guest password set from {}", file.display());
    Ok(hash)
}

// The first line of the file, without its line ending or a byte order mark, `kind` naming the password in messages.
fn read_kind(file: &Path, kind: &str) -> Result<Zeroizing<String>, UsageError> {
    let handle = File::open(file).map_err(|e| match e.kind() {
        io::ErrorKind::NotFound => UsageError(format!("The {kind} file {} does not exist.", file.display())),
        io::ErrorKind::PermissionDenied => {
            UsageError(format!("The {kind} file {} cannot be read: access denied.", file.display()))
        }
        _ => UsageError(format!("The {kind} file {} cannot be read: {e}", file.display())),
    })?;
    let mut bytes = Zeroizing::new(Vec::new());
    let limit = u64::try_from(MAX_FILE_BYTES.saturating_add(1)).unwrap_or(u64::MAX);
    handle.take(limit).read_to_end(&mut bytes).map_err(|e| match e.kind() {
        io::ErrorKind::PermissionDenied => {
            UsageError(format!("The {kind} file {} cannot be read: access denied.", file.display()))
        }
        _ => UsageError(format!("The {kind} file {} cannot be read: {e}", file.display())),
    })?;
    if bytes.len() > MAX_FILE_BYTES {
        return Err(UsageError(format!("The {kind} file {} is longer than {MAX_FILE_BYTES} bytes.", file.display())));
    }
    let text = Zeroizing::new(
        String::from_utf8(bytes.to_vec())
            .map_err(|_| UsageError(format!("The {kind} file {} is not UTF-8 text.", file.display())))?,
    );
    let without_bom = text.strip_prefix('\u{FEFF}').unwrap_or(text.as_str());
    let line = without_bom.split(['\n', '\r']).next().unwrap_or("");
    if line.chars().count() < passwords::MIN_LENGTH {
        return Err(UsageError(format!(
            "The password in {} needs at least {} characters.",
            file.display(),
            passwords::MIN_LENGTH
        )));
    }
    Ok(Zeroizing::new(line.to_owned()))
}
