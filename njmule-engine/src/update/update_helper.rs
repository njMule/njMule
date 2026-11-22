// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The installer of a new version unpacked by njMule, run by the old version's program in a process of its own once the
//! old njMule has handed over: it waits for that njMule to end, swaps the files, asks the new program its version and,
//! when njMule is to start again, starts the new version and watches it until it has run its engine 30 s. A new version
//! that does not answer, or stops with an error before, gives its place back to the old one, which starts again. How far
//! it got goes into the plan, which the next start of njMule reads.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use jiff::Timestamp;

use super::apply_plan::{ApplyPlan, State};
use super::commands::{Commands, SystemCommands};
use super::launcher::{self, Launcher, Running, SystemLauncher};
use super::swap::{self, Swap};
use super::update_files::UpdateFiles;
use crate::api::install_kind::InstallKind;
use crate::api::nj_mule_info;

pub const JOURNAL: &str = "swap.journal";
pub const DEFAULT_WAIT: Duration = Duration::from_secs(60);
pub const DEFAULT_WATCH: Duration = Duration::from_secs(600);

const PROBE_TIME: Duration = Duration::from_secs(60);
const DEFAULT_POLL: Duration = Duration::from_millis(500);

/// What tells the time of the plan's states.
pub type Clock = Arc<dyn Fn() -> Timestamp + Send + Sync>;

pub struct UpdateHelper {
    plan_file: PathBuf,
    files: UpdateFiles,
    commands: Arc<dyn Commands>,
    launcher: Arc<dyn Launcher>,
    clock: Clock,
    wait: Duration,
    watch: Duration,
    poll: Duration,
}

impl UpdateHelper {
    /// The installer of the plan in `plan_file`, given how long it waits for njMule to end and watches the new version,
    /// and how often it looks.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        plan_file: &Path,
        commands: Arc<dyn Commands>,
        launcher: Arc<dyn Launcher>,
        clock: Clock,
        wait: Duration,
        watch: Duration,
        poll: Duration,
    ) -> Self {
        let root = plan_file.parent().map_or_else(PathBuf::new, Path::to_path_buf);
        Self {
            plan_file: plan_file.to_path_buf(),
            files: UpdateFiles::new(root),
            commands,
            launcher,
            clock,
            wait,
            watch,
            poll,
        }
    }

    /// The installer of the plan in `plan_file`, with the real programs and processes of this computer.
    pub fn of(plan_file: &Path) -> Self {
        Self::new(
            plan_file,
            Arc::new(SystemCommands),
            Arc::new(SystemLauncher),
            Arc::new(Timestamp::now),
            DEFAULT_WAIT,
            DEFAULT_WATCH,
            DEFAULT_POLL,
        )
    }

    /// Installs once the processes `wait_for` have ended; gives the exit code of this process.
    pub fn run(&self, wait_for: &[u32]) -> i32 {
        let plan = match ApplyPlan::load(&self.plan_file) {
            Ok(Some(plan)) if plan.staged.is_some() && plan.target.is_some() => plan,
            Ok(_) => {
                tracing::error!("No installation to do in {}", self.plan_file.display());
                return 1;
            }
            Err(e) => {
                tracing::error!("Cannot read the installation plan: {e}");
                return 1;
            }
        };
        let plan = plan.with_helper(std::process::id(), (self.clock)());
        self.save(&plan);
        for &pid in wait_for {
            if !self.await_end(pid) {
                let why = format!("{} did not stop within {} s", nj_mule_info::NAME, self.wait.as_secs());
                self.save(&plan.with(State::Failed, Some(&why), (self.clock)()));
                return 1;
            }
        }
        tracing::info!(
            "Installing {} {} in {}",
            nj_mule_info::NAME,
            plan.version,
            plan.target.as_deref().unwrap_or(Path::new("")).display()
        );
        if let Some(failure) = place(&plan, &self.files, self.commands.clone()) {
            tracing::error!("The installation failed: {failure}");
            self.save(&plan.with(State::Failed, Some(&failure), (self.clock)()));
            self.restart_old(&plan);
            return 1;
        }
        let plan = plan.with(State::Installed, None, (self.clock)());
        self.save(&plan);
        if !plan.restart || plan.program.is_none() {
            return 0;
        }
        match self.launcher.start(&command(&plan), None) {
            Ok(started) => self.watch(&plan, started),
            Err(e) => self.roll_back(&plan, &format!("the new version does not start: {e}")),
        }
    }

    // Until the new version leaves its mark; an error before puts the old version back.
    fn watch(&self, plan: &ApplyPlan, mut started: Box<dyn Running>) -> i32 {
        let Ok(mark) = self.files.started(&plan.version) else {
            return 0;
        };
        let end = (self.clock)().checked_add(self.watch).unwrap_or_else(|_| (self.clock)());
        while (self.clock)() < end {
            if mark.exists() {
                return 0;
            }
            if let Some(code) = started.exit_code() {
                // Closed early by the user: the old files stay until the new version has run long enough.
                if code == 0 || mark.exists() {
                    return 0;
                }
                return self
                    .roll_back(plan, &format!("the new version stopped with code {code} before it had run 30 s"));
            }
            thread::sleep(self.poll);
        }
        tracing::warn!(
            "{} {} did not report within {} min",
            nj_mule_info::NAME,
            plan.version,
            self.watch.as_secs() / 60
        );
        0
    }

    fn roll_back(&self, plan: &ApplyPlan, why: &str) -> i32 {
        tracing::error!("Going back to {} {}: {why}", nj_mule_info::NAME, plan.from);
        let reason = match swap_of(plan, &self.files, self.commands.clone()).and_then(|swap| swap.roll_back()) {
            Ok(()) => why.to_owned(),
            Err(e) => format!("{why}; the old files could not all be put back: {e}"),
        };
        self.save(&plan.with(State::RolledBack, Some(&reason), (self.clock)()));
        self.restart_old(plan);
        1
    }

    fn restart_old(&self, plan: &ApplyPlan) {
        if !plan.restart || plan.program.is_none() {
            return;
        }
        if let Err(e) = self.launcher.start(&command(plan), None) {
            tracing::error!("Cannot start {} again: {e}", nj_mule_info::NAME);
        }
    }

    fn await_end(&self, pid: u32) -> bool {
        let end = (self.clock)().checked_add(self.wait).unwrap_or_else(|_| (self.clock)());
        while launcher::is_running(pid) {
            if (self.clock)() >= end {
                return false;
            }
            thread::sleep(self.poll);
        }
        true
    }

    fn save(&self, plan: &ApplyPlan) {
        if let Err(e) = plan.save(&self.plan_file) {
            tracing::warn!("Cannot keep the installation plan: {e}");
        }
    }
}

/// Swaps the files of `plan` and asks the new program its version; puts the old files back when it does not answer.
/// Gives why it failed.
pub fn place(plan: &ApplyPlan, files: &UpdateFiles, commands: Arc<dyn Commands>) -> Option<String> {
    let swap = match swap_of(plan, files, commands.clone()) {
        Ok(swap) => swap,
        Err(e) => return Some(format!("cannot put the new files in place: {e}")),
    };
    if let Err(e) = swap.apply() {
        return Some(format!("cannot put the new files in place: {e}"));
    }
    let wrong = probe(plan, commands.as_ref())?;
    match swap.roll_back() {
        Ok(()) => Some(wrong),
        Err(e) => Some(format!("{wrong}; the old files could not all be put back: {e}")),
    }
}

fn swap_of(plan: &ApplyPlan, files: &UpdateFiles, commands: Arc<dyn Commands>) -> std::io::Result<Swap> {
    let journal = files.version(&plan.version)?.join(JOURNAL);
    let missing = || std::io::Error::new(std::io::ErrorKind::InvalidInput, "a plan without the files to swap");
    let staged = plan.staged.as_deref().ok_or_else(missing)?;
    let target = plan.target.as_deref().ok_or_else(missing)?;
    Ok(if plan.kind == InstallKind::Dmg {
        Swap::bundle(staged, target, &journal, swap::ditto(commands))
    } else {
        Swap::folder(staged, target, &journal, swap::files())
    })
}

// The new program, asked as the user would: it must name the version installed.
fn probe(plan: &ApplyPlan, commands: &dyn Commands) -> Option<String> {
    let probe = plan.probe.as_deref()?;
    let program = plan.target.as_deref()?.join(probe);
    let command = [program.to_string_lossy().into_owned(), "--version".to_owned()];
    match commands.run(&command, PROBE_TIME) {
        Ok(answer)
            if answer.succeeded() && answer.output.contains(&format!("{} {}", nj_mule_info::NAME, plan.version)) =>
        {
            None
        }
        Ok(answer) if answer.succeeded() => Some(format!("the new version does not start: {}", answer.output.trim())),
        Ok(answer) => Some(format!("the new version does not start: it ended with code {}", answer.exit_code)),
        Err(e) => Some(format!("the new version does not start: {e}")),
    }
}

/// What starts njMule again: the program of the plan and its arguments.
pub fn command(plan: &ApplyPlan) -> Vec<String> {
    let mut command: Vec<String> = plan.program.iter().map(|program| program.to_string_lossy().into_owned()).collect();
    command.extend(plan.arguments.iter().cloned());
    command
}
