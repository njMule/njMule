// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Starts the desktop program when the user logs in: a value of the user's Run key in the Windows registry, an XDG
//! autostart entry on Linux, a launch agent on macOS. Whether it does is always read from the system, so that it stays
//! right when the user turned it off elsewhere.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use crate::app::link_association::{
    self, Shell, SystemShell, WorkFile, check, desktop_program, is_desktop_program, read_utf16, strings,
    unescape_reg_string, write_utf16le,
};
use crate::app::windows_command_line;

/// The id of njMule's autostart entry and launch agent.
pub const ID: &str = "org.njmule.njMule";

const UTF16LE_BOM: char = '\u{FEFF}';

/// Starts the desktop program when the user logs in.
pub trait Autostart: Send + Sync {
    /// Whether the system starts this program at login now.
    fn is_on(&self) -> io::Result<bool>;

    /// Makes the system start this program at login.
    fn turn_on(&self) -> io::Result<()>;

    /// Stops the system from starting this program at login.
    fn turn_off(&self) -> io::Result<()>;
}

/// The start at login of each system.
pub enum SystemAutostart {
    Windows(WindowsAutostart),
    Linux(LinuxAutostart),
    MacOs(MacOsAutostart),
}

impl SystemAutostart {
    /// The start at login for this system, for the installed desktop program with `arguments`; `None` when running
    /// from a development build.
    pub fn for_this_system(config_dir: PathBuf, arguments: &[String]) -> Option<Self> {
        let exe = std::env::current_exe().ok()?.to_string_lossy().into_owned();
        let os = std::env::consts::OS;
        let program = desktop_program(&exe, os, |path| path.is_file());
        let xdg = std::env::var("XDG_CONFIG_HOME").ok();
        let home = dirs::home_dir().map(|dir| dir.to_string_lossy().into_owned()).unwrap_or_default();
        Self::for_program(config_dir, Arc::new(SystemShell), &program, arguments, os, xdg.as_deref(), &home)
            .ok()
            .flatten()
    }

    /// The system's parts as arguments, so that tests choose them and never touch the real ones.
    pub fn for_program(
        config_dir: PathBuf,
        shell: Arc<dyn Shell>,
        program: &str,
        arguments: &[String],
        os_name: &str,
        xdg_config_home: Option<&str>,
        home: &str,
    ) -> Result<Option<Self>, String> {
        let os = os_name.to_ascii_lowercase();
        // The program's own name, cut at the separators of the system it is for, whichever system this runs on.
        let cut = if os.starts_with("windows") { program.rfind(['/', '\\']) } else { program.rfind('/') };
        let split = cut.and_then(|index| index.checked_add(1)).unwrap_or(0);
        let name = program.get(split..).unwrap_or(program);
        if !is_desktop_program(Path::new(name)) {
            return Ok(None);
        }
        let command = build_command(program, arguments)?;
        if os.starts_with("windows") {
            return Ok(Some(Self::Windows(WindowsAutostart {
                config_dir,
                shell,
                program: program.to_owned(),
                command,
            })));
        }
        if os.starts_with("mac") {
            let agent = PathBuf::from(home).join("Library").join("LaunchAgents").join(format!("{ID}.plist"));
            return Ok(Some(Self::MacOs(MacOsAutostart { agent, command })));
        }
        if os.starts_with("linux") {
            let base = xdg_config_home
                .filter(|value| !value.trim().is_empty())
                .map_or_else(|| PathBuf::from(home).join(".config"), PathBuf::from);
            let entry = base.join("autostart").join(format!("{ID}.desktop"));
            return Ok(Some(Self::Linux(LinuxAutostart { entry, command })));
        }
        Ok(None)
    }
}

impl Autostart for SystemAutostart {
    fn is_on(&self) -> io::Result<bool> {
        match self {
            Self::Windows(windows) => windows.is_on(),
            Self::Linux(linux) => linux.is_on(),
            Self::MacOs(macos) => Ok(macos.is_on()),
        }
    }

    fn turn_on(&self) -> io::Result<()> {
        match self {
            Self::Windows(windows) => windows.turn_on(),
            Self::Linux(linux) => linux.turn_on(),
            Self::MacOs(macos) => macos.turn_on(),
        }
    }

    fn turn_off(&self) -> io::Result<()> {
        match self {
            Self::Windows(windows) => windows.turn_off(),
            Self::Linux(linux) => linux.turn_off(),
            Self::MacOs(macos) => macos.turn_off(),
        }
    }
}

fn build_command(program: &str, arguments: &[String]) -> Result<Vec<String>, String> {
    let mut all = Vec::with_capacity(arguments.len().saturating_add(1));
    all.push(program.to_owned());
    all.extend(arguments.iter().cloned());
    for part in &all {
        if part.chars().any(char::is_control) {
            return Err(format!("a control character in {part}"));
        }
    }
    Ok(all)
}

// Written whole and moved in place, so that the system never reads half a file.
fn write_atomically(file: &Path, text: &str) -> io::Result<()> {
    let absolute = std::path::absolute(file)?;
    if let Some(dir) = absolute.parent() {
        fs::create_dir_all(dir)?;
    }
    let name = file.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    let tmp = file.with_file_name(format!("{name}.tmp"));
    fs::write(&tmp, text)?;
    fs::rename(&tmp, file)
}

/// The value `njMule` of the user's Run key, written as a `.reg` file to avoid the quoting rules of `reg add`.
pub struct WindowsAutostart {
    config_dir: PathBuf,
    shell: Arc<dyn Shell>,
    program: String,
    command: Vec<String>,
}

impl WindowsAutostart {
    pub const KEY: &str = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    pub const VALUE: &str = "njMule";
    // Where the Task Manager keeps which values of the Run key are disabled.
    pub const APPROVED_KEY: &str = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    const KEY_PATH: &str = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    // Only a value naming this program counts: one left by another copy of njMule would start that one. One the user
    // disabled in the Task Manager stays, with a flag beside it.
    pub fn is_on(&self) -> io::Result<bool> {
        let ours = self.program.to_lowercase();
        let listed = self
            .export(Self::KEY)?
            .and_then(|reg| Self::string_value(&reg))
            .is_some_and(|value| value.to_lowercase().contains(&ours));
        if !listed {
            return Ok(false);
        }
        Ok(!self.export(Self::APPROVED_KEY)?.is_some_and(|reg| Self::disabled(&reg)))
    }

    pub fn turn_on(&self) -> io::Result<()> {
        {
            let file = WorkFile::create(&self.config_dir, "autostart-", ".reg")?;
            // reg import takes version 5 files in UTF-16 with a byte order mark.
            write_utf16le(file.path(), &self.reg())?;
            check(&*self.shell, &strings(["reg", "import", &file.path_string()]))?;
        }
        // Without its flag the value counts as enabled again.
        if self.export(Self::APPROVED_KEY)?.is_some_and(|reg| Self::disabled(&reg)) {
            check(&*self.shell, &strings(["reg", "delete", Self::APPROVED_KEY, "/v", Self::VALUE, "/f"]))?;
        }
        tracing::info!("njMule now starts when the user logs in");
        Ok(())
    }

    pub fn turn_off(&self) -> io::Result<()> {
        if self.export(Self::KEY)?.and_then(|reg| Self::string_value(&reg)).is_some() {
            check(&*self.shell, &strings(["reg", "delete", Self::KEY, "/v", Self::VALUE, "/f"]))?;
        }
        tracing::info!("njMule no longer starts when the user logs in");
        Ok(())
    }

    /// The command line of the value.
    pub fn command_line(&self) -> String {
        windows_command_line::of(&self.command)
    }

    pub fn reg(&self) -> String {
        format!(
            "{UTF16LE_BOM}Windows Registry Editor Version 5.00\r\n\r\n[{}]\r\n\"{}\"={}\r\n",
            Self::KEY_PATH,
            Self::VALUE,
            link_association::WindowsLinks::reg_string(&self.command_line()),
        )
    }

    // reg query prints in the console's code page, which may lack letters of the program's path; an export is in
    // UTF-16. None when the key is missing.
    fn export(&self, key: &str) -> io::Result<Option<String>> {
        let file = WorkFile::create(&self.config_dir, "autostart-", ".reg")?;
        let export = self.shell.run(&strings(["reg", "export", key, &file.path_string(), "/y"]))?;
        if export.exit != 0 {
            return Ok(None);
        }
        Ok(Some(read_utf16(file.path())?))
    }

    // The Task Manager's flag of our value: binary, with an odd first byte while the value is disabled.
    pub fn disabled(reg: &str) -> bool {
        let prefix = format!("\"{}\"=hex:", Self::VALUE);
        for line in reg.split('\n').map(|line| line.strip_suffix('\r').unwrap_or(line)) {
            if let Some(head) = line.get(..prefix.len())
                && head.eq_ignore_ascii_case(&prefix)
                && let Some(hex) = line.get(prefix.len()..prefix.len().saturating_add(2))
            {
                return u8::from_str_radix(hex, 16).is_ok_and(|byte| (byte & 1) != 0);
            }
        }
        false
    }

    // Our string value in an exported key, without the escapes of the format.
    pub fn string_value(reg: &str) -> Option<String> {
        let prefix = format!("\"{}\"=\"", Self::VALUE);
        for line in reg.split('\n').map(|line| line.strip_suffix('\r').unwrap_or(line)) {
            if let Some(head) = line.get(..prefix.len())
                && head.eq_ignore_ascii_case(&prefix)
                && let Some(rest) = line.get(prefix.len()..)
                && let Some(quoted) = rest.strip_suffix('"')
            {
                return Some(unescape_reg_string(quoted));
            }
        }
        None
    }
}

/// An entry of the XDG autostart directory, which desktops start at login unless it is hidden.
pub struct LinuxAutostart {
    entry: PathBuf,
    command: Vec<String>,
}

impl LinuxAutostart {
    pub fn is_on(&self) -> io::Result<bool> {
        if !self.entry.is_file() {
            return Ok(false);
        }
        let text = fs::read_to_string(&self.entry)?;
        for line in text.lines() {
            let setting = line.trim().replace(' ', "").to_ascii_lowercase();
            if setting == "hidden=true" || setting == "x-gnome-autostart-enabled=false" {
                return Ok(false);
            }
        }
        Ok(true)
    }

    pub fn turn_on(&self) -> io::Result<()> {
        write_atomically(&self.entry, &self.desktop_entry())?;
        tracing::info!("njMule now starts when the user logs in: {}", self.entry.display());
        Ok(())
    }

    pub fn turn_off(&self) -> io::Result<()> {
        if self.entry.exists() {
            fs::remove_file(&self.entry)?;
        }
        tracing::info!("njMule no longer starts when the user logs in");
        Ok(())
    }

    pub fn desktop_entry(&self) -> String {
        let exec: Vec<String> = self.command.iter().map(|part| Self::exec_argument(part)).collect();
        let try_exec = self.command.first().map(|first| first.replace('\\', "\\\\")).unwrap_or_default();
        format!(
            "[Desktop Entry]\n\
             Type=Application\n\
             Name=njMule\n\
             Comment=eD2K and Kad file sharing\n\
             Exec={}\n\
             TryExec={try_exec}\n\
             Icon={ID}\n\
             Terminal=false\n\
             X-GNOME-Autostart-enabled=true\n",
            exec.join(" "),
        )
    }

    // The Desktop Entry quoting: reserved characters need double quotes, inside which ", `, $ and \ take a backslash;
    // % doubles, as it starts a field code; then the string value escapes each backslash again.
    pub fn exec_argument(part: &str) -> String {
        let text = part.replace('%', "%%");
        if !text.is_empty() && !text.chars().any(|c| " \t\"'\\><~|&;$*?#()`".contains(c)) {
            return text;
        }
        let quoted =
            format!("\"{}\"", text.replace('\\', "\\\\").replace('"', "\\\"").replace('`', "\\`").replace('$', "\\$"),);
        quoted.replace('\\', "\\\\")
    }
}

/// A launch agent of the user, which macOS runs at login.
pub struct MacOsAutostart {
    agent: PathBuf,
    command: Vec<String>,
}

impl MacOsAutostart {
    pub fn is_on(&self) -> bool {
        self.agent.is_file()
    }

    pub fn turn_on(&self) -> io::Result<()> {
        write_atomically(&self.agent, &self.plist())?;
        tracing::info!("njMule now starts when the user logs in: {}", self.agent.display());
        Ok(())
    }

    pub fn turn_off(&self) -> io::Result<()> {
        if self.agent.exists() {
            fs::remove_file(&self.agent)?;
        }
        tracing::info!("njMule no longer starts when the user logs in");
        Ok(())
    }

    pub fn plist(&self) -> String {
        let mut arguments = String::new();
        for part in &self.command {
            arguments.push_str("        <string>");
            arguments.push_str(&xml_content(part));
            arguments.push_str("</string>\n");
        }
        format!(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
             <!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \
             \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n\
             <plist version=\"1.0\">\n\
             <dict>\n\
             \x20   <key>Label</key>\n\
             \x20   <string>{ID}</string>\n\
             \x20   <key>ProgramArguments</key>\n\
             \x20   <array>\n\
             {arguments}\
             \x20   </array>\n\
             \x20   <key>RunAtLoad</key>\n\
             \x20   <true/>\n\
             \x20   <key>ProcessType</key>\n\
             \x20   <string>Interactive</string>\n\
             </dict>\n\
             </plist>\n",
        )
    }
}

fn xml_content(text: &str) -> String {
    text.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}
