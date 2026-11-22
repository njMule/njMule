// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The hand-over of a new version from a stopped engine: it writes the plan and starts the installer, which waits for
//! this process to end. An MSI goes to the desktop window's program, copied out of the folder Windows Installer
//! replaces, which runs `msiexec` and starts njMule again; an unpacked archive or bundle goes to the update helper, run
//! by this version's program.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use super::apply_plan::{ApplyPlan, State};
use super::commands::Commands;
use super::installation::Installation;
use super::launcher::{self, DESKTOP_VARIABLE, Launcher};
use super::update_files::UpdateFiles;
use super::update_helper::{self, Clock};
use crate::api::install_kind::InstallKind;
use crate::api::nj_mule_info;
use crate::api::update_status::Installer;

/// The desktop window's program, which starts this engine for the desktop.
pub const LAUNCHER_FILE: &str = if cfg!(windows) { "njmule-gui.exe" } else { "njmule-gui" };

pub const INSTALLER_LOG: &str = "install.log";
pub const INSTALLER_RESULT: &str = "msiexec.txt";
pub const HELPER_LOG: &str = "helper.log";

// How long the installer has to say that it runs.
const SIGNAL: Duration = Duration::from_secs(15);
const POLL: Duration = Duration::from_millis(100);

pub struct HandOver {
    plan: ApplyPlan,
    installation: Installation,
    files: UpdateFiles,
    commands: Arc<dyn Commands>,
    launcher: Arc<dyn Launcher>,
    clock: Clock,
    desktop: bool,
}

impl HandOver {
    /// The hand-over of `plan`, its program and arguments still to come; `desktop` tells the desktop app's engine, which
    /// starts again through its window.
    pub fn new(
        plan: ApplyPlan,
        installation: Installation,
        files: UpdateFiles,
        commands: Arc<dyn Commands>,
        launcher: Arc<dyn Launcher>,
        clock: Clock,
        desktop: bool,
    ) -> Self {
        Self { plan, installation, files, commands, launcher, clock, desktop }
    }

    /// Whether this process is the desktop app's engine, started by its window.
    pub fn desktop() -> bool {
        std::env::var(DESKTOP_VARIABLE).is_ok_and(|value| value == "1")
    }

    /// The version installed.
    pub fn version(&self) -> &str {
        &self.plan.version
    }

    /// Whether njMule starts again once the new version is in place.
    pub fn restart(&self) -> bool {
        self.plan.restart
    }

    /// Starts the installer in a process of its own, which waits for this one to end; njMule starts again with
    /// `arguments`, as this run started.
    pub fn start(&self, arguments: &[String]) -> io::Result<()> {
        let full = self.planned(self.plan.restart, arguments);
        remove_if_there(&self.files.started(&full.version)?)?;
        full.save(&self.files.plan())?;
        tracing::info!("Handing over the installation of {} {}", nj_mule_info::NAME, full.version);
        if full.kind == InstallKind::Msi { self.start_installer(&full) } else { self.start_helper(&full) }
    }

    /// Installs from this process, for the command line or a service that its supervisor restarts: the files of an
    /// archive or a bundle are swapped now, an installer package goes to its installer, which runs once this process
    /// has ended. Tells whether the new version is in place now.
    pub fn install_here(&self) -> io::Result<bool> {
        if self.plan.kind == InstallKind::Msi {
            let alone = self.planned(false, &[]);
            alone.save(&self.files.plan())?;
            self.start_installer(&alone)?;
            return Ok(false);
        }
        let here = self.planned(false, &[]).with_helper(std::process::id(), (self.clock)());
        here.save(&self.files.plan())?;
        if let Some(failure) = update_helper::place(&here, &self.files, self.commands.clone()) {
            here.with(State::Failed, Some(&failure), (self.clock)()).save(&self.files.plan())?;
            return Err(io::Error::other(failure));
        }
        here.with(State::Installed, None, (self.clock)()).save(&self.files.plan())?;
        Ok(true)
    }

    fn planned(&self, restart: bool, arguments: &[String]) -> ApplyPlan {
        let program = if restart { self.program() } else { None };
        ApplyPlan {
            restart: restart && program.is_some(),
            arguments: if program.is_some() { arguments.to_vec() } else { Vec::new() },
            program,
            state: State::HandedOver,
            reason: None,
            helper: None,
            time: (self.clock)(),
            ..self.plan.clone()
        }
    }

    // The installed program that starts njMule as the user does: the desktop app's window, for its engine.
    fn program(&self) -> Option<PathBuf> {
        let executable = self.installation.executable.clone()?;
        if self.desktop {
            let window = executable.with_file_name(LAUNCHER_FILE);
            if window.is_file() {
                return Some(window);
            }
        }
        Some(executable)
    }

    // The window's program runs msiexec once this process and the window that started it have ended, then starts
    // njMule again.
    fn start_installer(&self, full: &ApplyPlan) -> io::Result<()> {
        let executable = self.executable()?;
        let installed = executable.with_file_name(LAUNCHER_FILE);
        let folder = self.files.version(&full.version)?;
        let copy = folder.join(LAUNCHER_FILE);
        fs::create_dir_all(&folder)?;
        fs::copy(&installed, &copy)?;
        remove_if_there(&folder.join(INSTALLER_RESULT))?;
        let mut command = vec![text(&copy), "--apply-update".to_owned(), text(&full.package_file)];
        for pid in self.waits() {
            command.extend(["--wait".to_owned(), pid.to_string()]);
        }
        command.extend(["--ui".to_owned(), if full.unattended { "quiet" } else { "passive" }.to_owned()]);
        if self.installation.installer == Installer::Elevated {
            command.push("--all-users".to_owned());
        }
        command.extend([
            "--log".to_owned(),
            text(&folder.join(INSTALLER_LOG)),
            "--result".to_owned(),
            text(&folder.join(INSTALLER_RESULT)),
        ]);
        if full.restart {
            command.push("--relaunch".to_owned());
            command.extend(update_helper::command(full));
        }
        self.launcher.start(&command, None).map(|_| ())
    }

    // This program, run again as the installer; it says that it runs by taking the plan over.
    fn start_helper(&self, full: &ApplyPlan) -> io::Result<()> {
        let mut command = vec![text(&self.executable()?), "--apply-update".to_owned(), text(&self.files.plan())];
        for pid in self.waits() {
            command.extend(["--wait".to_owned(), pid.to_string()]);
        }
        let log = self.files.version(&full.version)?.join(HELPER_LOG);
        let mut helper = self.launcher.start(&command, Some(&log))?;
        let end = (self.clock)().checked_add(SIGNAL).unwrap_or_else(|_| (self.clock)());
        while (self.clock)() < end {
            if ApplyPlan::load(&self.files.plan()).ok().flatten().is_some_and(|now| now.state != State::HandedOver) {
                return Ok(());
            }
            if !helper.is_alive() {
                break;
            }
            thread::sleep(POLL);
        }
        let why = match helper.exit_code() {
            None => "the installer did not start in time".to_owned(),
            Some(code) => format!("the installer stopped at once with code {code}"),
        };
        tracing::error!("Cannot install {} {}: {why}", nj_mule_info::NAME, full.version);
        helper.destroy();
        full.with(State::Failed, Some(&why), (self.clock)()).save(&self.files.plan())?;
        if full.restart {
            // This version again, once this process has ended.
            let mut again = update_helper::command(full);
            again.extend(["--wait".to_owned(), std::process::id().to_string()]);
            self.launcher.start(&again, None)?;
        }
        Err(io::Error::other(why))
    }

    fn executable(&self) -> io::Result<PathBuf> {
        self.installation
            .executable
            .clone()
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no installed program to hand over to"))
    }

    // This process, and the desktop window that started it, whose file the installer replaces too.
    fn waits(&self) -> Vec<u32> {
        let mut pids = vec![std::process::id()];
        let folder = self.installation.executable.as_deref().and_then(std::path::Path::parent);
        if let Some((parent, Some(program))) = launcher::parent()
            && program.file_name().is_some_and(|name| name.to_string_lossy().to_lowercase() == LAUNCHER_FILE)
            && folder.is_some_and(|folder| program.parent() == Some(folder))
        {
            pids.push(parent);
        }
        pids
    }
}

fn text(path: &std::path::Path) -> String {
    path.to_string_lossy().into_owned()
}

fn remove_if_there(file: &std::path::Path) -> io::Result<()> {
    match fs::remove_file(file) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}
