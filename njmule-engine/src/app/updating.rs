// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! What the front ends share around the installation of a new version: waiting, restarting, handing it over.

use std::collections::BTreeMap;
use std::thread;
use std::time::{Duration, Instant};

use crate::api::nj_mule_info;
use crate::update::hand_over::HandOver;
use crate::update::launcher;

/// The exit code that asks a service's supervisor to start njMule again once a new version is in place, as
/// `Restart=on-failure` of systemd does.
pub const EXIT_RESTART: u8 = 75;

/// How long a start waits for the njMule it replaces.
pub const EXIT_WAIT: Duration = Duration::from_secs(60);

const WAIT_OPTION: &str = "--wait";
const WAIT_POLL: Duration = Duration::from_millis(100);

/// Waits until the processes `pids` have ended, [`EXIT_WAIT`] at most in all: a start after an update finds the files
/// and the configuration of the njMule it replaces free only then.
pub fn await_end(pids: &[u64]) {
    if pids.is_empty() {
        return;
    }
    let end = Instant::now().checked_add(EXIT_WAIT).unwrap_or_else(Instant::now);
    for &pid in pids {
        let Ok(pid32) = u32::try_from(pid) else {
            continue;
        };
        while launcher::is_running(pid32) {
            if Instant::now() >= end {
                tracing::warn!("Process {pid} still runs after {} s: starting anyway", EXIT_WAIT.as_secs());
                break;
            }
            thread::sleep(WAIT_POLL);
        }
    }
}

/// The arguments that start njMule again as `args` did, without what waited for an earlier process.
pub fn again(args: &[String]) -> Vec<String> {
    let mut kept = Vec::new();
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        if arg == WAIT_OPTION {
            let _ = iter.next();
        } else if !arg.starts_with("--wait=") {
            kept.push(arg.clone());
        }
    }
    kept
}

/// Whether this process is a service of systemd, which starts it again after [`EXIT_RESTART`].
pub fn under_systemd<S: AsRef<str>>(environment: &BTreeMap<String, S>) -> bool {
    environment.contains_key("INVOCATION_ID") && environment.contains_key("JOURNAL_STREAM")
}

/// Whether the current process runs under systemd.
pub fn under_systemd_current() -> bool {
    std::env::var_os("INVOCATION_ID").is_some() && std::env::var_os("JOURNAL_STREAM").is_some()
}

/// Once the engine of a long run has stopped: hands over the installation of a new version, if one is due; returns the
/// exit code of the process.
pub fn hand_over(install: &HandOver, args: &[String], systemd: bool) -> u8 {
    if systemd {
        match install.install_here() {
            Ok(_) => {
                tracing::info!("{} {} is in place", nj_mule_info::NAME, install.version());
                return if install.restart() { EXIT_RESTART } else { 0 };
            }
            Err(e) => {
                tracing::error!("Cannot install {} {}: {e}", nj_mule_info::NAME, install.version());
                return 0;
            }
        }
    }
    match install.start(&again(args)) {
        Ok(()) => tracing::info!("Installing {} {} once this process has ended", nj_mule_info::NAME, install.version()),
        Err(e) => tracing::error!("Cannot install {} {}: {e}", nj_mule_info::NAME, install.version()),
    }
    0
}
