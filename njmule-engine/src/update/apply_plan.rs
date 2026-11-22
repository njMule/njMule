// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! The installation of a new version, from its hand-over to its end: what the stopping njMule asks the installer to do,
//! then how far the installer got. It lives in `cache/update/apply.properties` until the new version has proved itself,
//! so that the next start of either version, Java or Rust, can tell the user what happened.

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use jiff::Timestamp;

use crate::api::install_kind::InstallKind;
use crate::persist::{atomic_files, property_lines};

/// How far an installation got.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum State {
    /// The stopping njMule started the installer.
    HandedOver,
    /// The installer runs and waits for njMule to end.
    Waiting,
    /// The new version is in place, the old files kept until it has run long enough.
    Installed,
    /// Nothing changed: the installation stopped before, or put everything back at once.
    Failed,
    /// The new version failed to run and the old one is back.
    RolledBack,
}

impl State {
    const ALL: [Self; 5] = [Self::HandedOver, Self::Waiting, Self::Installed, Self::Failed, Self::RolledBack];

    /// The name the plan keeps it by.
    pub fn name(self) -> &'static str {
        match self {
            Self::HandedOver => "handed_over",
            Self::Waiting => "waiting",
            Self::Installed => "installed",
            Self::Failed => "failed",
            Self::RolledBack => "rolled_back",
        }
    }

    fn of(name: &str) -> Option<Self> {
        let name = name.to_lowercase();
        Self::ALL.into_iter().find(|state| state.name() == name)
    }
}

/// The version installed and the one it replaces, how njMule was installed, the package downloaded, the files unpacked
/// from it or the new bundle and the folder or bundle they replace (none for an installer package), the program below
/// the target that tells its version, whether njMule starts again and with what, whether nobody watches, how far the
/// installation got and why it failed, the process that installs and when the state was last set.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApplyPlan {
    pub version: String,
    pub from: String,
    pub kind: InstallKind,
    pub package_file: PathBuf,
    pub staged: Option<PathBuf>,
    pub target: Option<PathBuf>,
    pub probe: Option<String>,
    pub restart: bool,
    pub unattended: bool,
    pub program: Option<PathBuf>,
    pub arguments: Vec<String>,
    pub state: State,
    pub reason: Option<String>,
    pub helper: Option<u32>,
    pub time: Timestamp,
}

impl ApplyPlan {
    pub fn with(&self, next: State, why: Option<&str>, when: Timestamp) -> Self {
        Self { state: next, reason: why.map(str::to_owned), time: when, ..self.clone() }
    }

    pub fn with_helper(&self, pid: u32, when: Timestamp) -> Self {
        Self { state: State::Waiting, reason: None, helper: Some(pid), time: when, ..self.clone() }
    }

    /// The plan in `file`, if one is there; a plan that cannot be read fails.
    pub fn load(file: &Path) -> io::Result<Option<Self>> {
        let text = match fs::read_to_string(file) {
            Ok(text) => text,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(e),
        };
        let p = property_lines::parse(&text);
        let damaged = |why: &str| {
            io::Error::new(io::ErrorKind::InvalidData, format!("a damaged installation plan {}: {why}", file.display()))
        };
        let arguments = (0..).map_while(|i: u32| p.get(&format!("argument.{i}")).cloned()).collect();
        let helper = match p.get("helper").map(String::as_str) {
            None | Some("") => None,
            Some(pid) => Some(pid.parse().map_err(|_| damaged(&format!("helper {pid}")))?),
        };
        let kind = required(&p, "kind")?;
        let state = required(&p, "state")?;
        let time = required(&p, "time")?;
        Ok(Some(Self {
            version: required(&p, "version")?,
            from: required(&p, "from")?,
            kind: InstallKind::of(&kind).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "unknown kind"))?,
            package_file: PathBuf::from(required(&p, "package")?),
            staged: path(&p, "staged"),
            target: path(&p, "target"),
            probe: p.get("probe").filter(|probe| !probe.trim().is_empty()).cloned(),
            restart: flag(&p, "restart"),
            unattended: flag(&p, "unattended"),
            program: path(&p, "program"),
            arguments,
            state: State::of(&state).ok_or_else(|| damaged(&format!("state {state}")))?,
            reason: p.get("reason").filter(|reason| !reason.trim().is_empty()).cloned(),
            helper,
            time: time.parse().map_err(|_| damaged(&format!("time {time}")))?,
        }))
    }

    /// Keeps the plan in `file`, replacing it whole.
    pub fn save(&self, file: &Path) -> io::Result<()> {
        let mut text = String::new();
        let mut put = |key: &str, value: &str| property_lines::append(&mut text, key, value);
        put("version", &self.version);
        put("from", &self.from);
        put("kind", self.kind.id());
        put("package", &self.package_file.to_string_lossy());
        if let Some(staged) = &self.staged {
            put("staged", &staged.to_string_lossy());
        }
        if let Some(target) = &self.target {
            put("target", &target.to_string_lossy());
        }
        if let Some(probe) = &self.probe {
            put("probe", probe);
        }
        put("restart", &self.restart.to_string());
        put("unattended", &self.unattended.to_string());
        if let Some(program) = &self.program {
            put("program", &program.to_string_lossy());
        }
        for (i, argument) in self.arguments.iter().enumerate() {
            put(&format!("argument.{i}"), argument);
        }
        put("state", self.state.name());
        if let Some(reason) = &self.reason {
            put("reason", reason);
        }
        if let Some(helper) = self.helper {
            put("helper", &helper.to_string());
        }
        put("time", &self.time.to_string());
        // No backup: an older state of the plan would only mislead.
        atomic_files::write_with(file, false, false, |out| out.write_all(text.as_bytes()))
    }
}

fn required(p: &BTreeMap<String, String>, key: &str) -> io::Result<String> {
    p.get(key)
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("the installation plan lacks {key}")))
}

fn path(p: &BTreeMap<String, String>, key: &str) -> Option<PathBuf> {
    p.get(key).filter(|value| !value.trim().is_empty()).map(PathBuf::from)
}

// As the Java engine reads a boolean: true only when it says so.
fn flag(p: &BTreeMap<String, String>, key: &str) -> bool {
    p.get(key).is_some_and(|value| value.eq_ignore_ascii_case("true"))
}
