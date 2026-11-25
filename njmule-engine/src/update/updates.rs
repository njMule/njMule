// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Looks for new versions of njMule and installs them: fetches the manifest of the newest release of a channel with its
//! signature, checks the signature against the keys built into njMule and tells whether that release is newer than the
//! one running; downloads its package for this installation, checked against the manifest, and unpacks it; and, once the
//! engine has stopped, hands the installation over to an installer of its own process. One check and one download run at
//! a time, each on a task of its own.

use std::fs::{self, OpenOptions};
use std::future::Future;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use futures_util::future::BoxFuture;
use jiff::Timestamp;
use rand::RngExt;
use semver::Version;
use time::Time;
use tokio::sync::watch;
use tokio::task::AbortHandle;
use url::Url;

use super::apply_plan::{ApplyPlan, State as PlanState};
use super::archives;
use super::commands::{Commands, SystemCommands};
use super::disk_images;
use super::git_hub_releases::{Fetcher, GitHubReleases, Signed};
use super::hand_over::{self, HandOver};
use super::installation::Installation;
use super::launcher::{Launcher, SystemLauncher};
use super::minisign::{self, PublicKey};
use super::package_download::{self, Progress as DownloadProgress, Transfer};
use super::update_files::{self, UpdateFiles};
use super::update_helper::{self, Clock};
use super::update_manifest::{self, UpdateManifest};
use super::update_state::{self, UpdateState};
use crate::api::install_kind::InstallKind;
use crate::api::new_version::{Download, NewVersion};
use crate::api::nj_mule_info;
use crate::api::update_status::{Install, Installer, Progress, UpdateStatus};
use crate::config::night_hours::NightHours;
use crate::config::preferences::Preferences;
use crate::config::update_channel::UpdateChannel;
use crate::config::update_install_time::UpdateInstallTime;
use crate::config::update_mode::UpdateMode;
use crate::net::routes::Routes;
use crate::net::web_lists::WebLists;
use crate::persist::preferences_codec;

/// Set to `true`, njMule never looks for new versions: tests must not reach GitHub.
pub const DISABLED_PROPERTY: &str = "njmule.update.disabled";

pub const REPOSITORY: &str = nj_mule_info::HOMEPAGE;
pub const API: &str = "https://api.github.com/repos/njMule/njMule";

/// How long after a check the next one is due, and after a failed one.
pub const EVERY: Duration = Duration::from_secs(24 * 3600);
pub const RETRY: Duration = Duration::from_secs(6 * 3600);

// The signing key first, then the standby key that replaces it if it is ever lost or leaked.
const KEYS: &str = include_str!("../../assets/keys.pub");
// Clients started together would otherwise all ask at the same minute.
const JITTER_MILLIS: u64 = 3600 * 1000;
// Progress is told at most this often.
const PROGRESS_EVERY: Duration = Duration::from_millis(500);
const CLEANUP_ATTEMPTS: usize = 60;
const CLEANUP_WAIT: Duration = Duration::from_secs(2);

/// Hears what the checks, downloads and installations do.
pub trait Listener: Send + Sync + 'static {
    /// The status changed: a check or download started, progressed or ended, or the user chose something.
    fn changed(&self, status: UpdateStatus);

    /// A check njMule ran on its own found a new version worth telling of; once a run for each.
    fn found(&self, version: NewVersion);

    /// The package of `version` is downloaded and checked: it can be installed.
    fn ready(&self, version: NewVersion);

    /// njMule is to stop, install `version` and start again: the front end that owns the process stops the engine and
    /// hands the installation over.
    fn restart(&self, version: String);
}

impl Fetcher for WebLists {
    fn fetch(&self, url: &str) -> impl Future<Output = io::Result<Vec<u8>>> + Send {
        WebLists::fetch(self, url)
    }
}

impl Transfer for WebLists {
    fn download(
        &self,
        url: &str,
        part: &Path,
        size: u64,
        progress: DownloadProgress<'_>,
    ) -> impl Future<Output = io::Result<()>> + Send {
        WebLists::download(self, url, part, size, progress)
    }
}

/// The public keys built into njMule that sign the update information of releases.
pub fn shipped_keys() -> Vec<PublicKey> {
    // Read at build time from the repository: a test holds them valid.
    minisign::public_keys(KEYS).unwrap_or_default()
}

/// Records that this version starts on the configuration in `config_dir`; returns whether another version used it
/// before, or one that kept no record: a backup of the configuration is due before this one changes it.
pub fn starting(config_dir: &Path) -> bool {
    let file = config_dir.join(update_state::FILE_NAME);
    let state = UpdateState::load(&file);
    let version = nj_mule_info::version();
    if state.ran.as_deref() == Some(version) {
        return false;
    }
    let used = state.ran.is_some() || config_dir.join(preferences_codec::FILE_NAME).exists();
    if let Err(e) = state.running(version).save(&file) {
        tracing::warn!("Cannot save {}: {e}", file.display());
    }
    if let (Some(now), Some(before)) =
        (Version::parse(version).ok(), state.ran.as_deref().and_then(|v| Version::parse(v).ok()))
        && before > now
    {
        tracing::warn!(
            "The configuration was last used by {} {}, newer than this one: it may not read all of it",
            nj_mule_info::NAME,
            before
        );
    }
    used
}

/// Looks for new versions of njMule, downloads and stages their packages, and prepares the hand-over to the installer.
pub struct Updates<F = Arc<WebLists>, T = Arc<WebLists>> {
    inner: Arc<Inner<F, T>>,
}

impl<F, T> Clone for Updates<F, T> {
    fn clone(&self) -> Self {
        Self { inner: Arc::clone(&self.inner) }
    }
}

struct Inner<F, T> {
    releases: GitHubReleases<F>,
    transfer: T,
    keys: Vec<PublicKey>,
    installation: Installation,
    current: Version,
    state_file: PathBuf,
    files: UpdateFiles,
    commands: Arc<dyn Commands>,
    launcher: Arc<dyn Launcher>,
    clock: Clock,
    jitter: Duration,
    enabled: bool,
    desktop: bool,
    listener: Arc<dyn Listener>,
    guarded: Mutex<Guarded>,
}

struct Guarded {
    state: UpdateState,
    status: UpdateStatus,
    running: Option<watch::Sender<Option<UpdateStatus>>>,
    checker: Option<AbortHandle>,
    downloading: Option<watch::Sender<Option<UpdateStatus>>>,
    downloader: Option<AbortHandle>,
    last_progress: Timestamp,
    last_attempt: Option<Timestamp>,
    last_failed: bool,
    told: Option<String>,
    staged: Option<PathBuf>,
    install_asked: bool,
    unattended: bool,
    mode: UpdateMode,
    install_time: UpdateInstallTime,
    night_hours: NightHours,
    // What the last installation left for this start to tell: its plan, and what happened.
    outcome: Option<ApplyPlan>,
}

impl Updates<Arc<WebLists>, Arc<WebLists>> {
    /// The updates of this copy of njMule, fetched through `routes` (or, when the preferences name another release
    /// server, from there with any address), remembered in `config_dir` and downloaded to `cache_dir`.
    pub fn create(
        config_dir: &Path,
        cache_dir: &Path,
        routes: Routes,
        preferences: &Preferences,
        enabled: bool,
        listener: Arc<dyn Listener>,
    ) -> Self {
        let elsewhere = preferences.update_url.as_deref();
        let reach =
            Arc::new(if elsewhere.is_some() { WebLists::any_address(routes) } else { WebLists::create(routes) });
        let repository = elsewhere.unwrap_or(REPOSITORY);
        let api = elsewhere.map(|url| format!("{}/api", url.trim_end_matches('/'))).unwrap_or_else(|| API.to_owned());
        let jitter = Duration::from_millis(rand::rng().random_range(0..JITTER_MILLIS));
        let current = Version::parse(nj_mule_info::version()).unwrap_or_else(|_| Version::new(0, 0, 0));
        let updates = Self::with_version(
            GitHubReleases::new(Arc::clone(&reach), repository, &api),
            reach,
            shipped_keys(),
            Installation::current(),
            current,
            config_dir.join(update_state::FILE_NAME),
            UpdateFiles::in_cache(cache_dir),
            Arc::new(SystemCommands),
            Arc::new(SystemLauncher),
            Arc::new(Timestamp::now),
            jitter,
            enabled,
            HandOver::desktop(),
            listener,
        );
        {
            let mut guarded = updates.inner.lock();
            guarded.follow(preferences, &updates.inner);
        }
        updates
    }
}

impl<F: Fetcher + 'static, T: Transfer + 'static> Updates<F, T> {
    /// Constructs `Updates` with each part supplied so that tests can replace them.
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        releases: GitHubReleases<F>,
        transfer: T,
        keys: Vec<PublicKey>,
        installation: Installation,
        current: &str,
        state_file: PathBuf,
        files: UpdateFiles,
        commands: Arc<dyn Commands>,
        launcher: Arc<dyn Launcher>,
        clock: Clock,
        jitter: Duration,
        enabled: bool,
        desktop: bool,
        listener: Arc<dyn Listener>,
    ) -> io::Result<Self> {
        let current = Version::parse(current)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("not a version: {current}")))?;
        Ok(Self::with_version(
            releases,
            transfer,
            keys,
            installation,
            current,
            state_file,
            files,
            commands,
            launcher,
            clock,
            jitter,
            enabled,
            desktop,
            listener,
        ))
    }

    #[allow(clippy::too_many_arguments)]
    fn with_version(
        releases: GitHubReleases<F>,
        transfer: T,
        keys: Vec<PublicKey>,
        installation: Installation,
        current: Version,
        state_file: PathBuf,
        files: UpdateFiles,
        commands: Arc<dyn Commands>,
        launcher: Arc<dyn Launcher>,
        clock: Clock,
        jitter: Duration,
        enabled: bool,
        desktop: bool,
        listener: Arc<dyn Listener>,
    ) -> Self {
        let state = UpdateState::load(&state_file);
        let status = UpdateStatus {
            current: current.to_string(),
            installation: installation.kind,
            installer: installation.installer,
            checking: false,
            last_check: state.last_check,
            failure: None,
            available: None,
            skipped: state.skipped.clone(),
            download: None,
            ready: None,
            install: Install::Asked,
        };
        let mut guarded = Guarded {
            state,
            status,
            running: None,
            checker: None,
            downloading: None,
            downloader: None,
            last_progress: Timestamp::UNIX_EPOCH,
            last_attempt: None,
            last_failed: false,
            told: None,
            staged: None,
            install_asked: false,
            unattended: false,
            mode: UpdateMode::Notify,
            install_time: UpdateInstallTime::Exit,
            night_hours: NightHours::default(),
            outcome: None,
        };
        read_outcome(&mut guarded, &current, &state_file, &files, &commands);
        Self {
            inner: Arc::new(Inner {
                releases,
                transfer,
                keys,
                installation,
                current,
                state_file,
                files,
                commands,
                launcher,
                clock,
                jitter,
                enabled,
                desktop,
                listener,
                guarded: Mutex::new(guarded),
            }),
        }
    }

    pub fn status(&self) -> UpdateStatus {
        self.inner.lock().status.clone()
    }

    /// Follows the preferences: what to do with a new version, and when to install it.
    pub fn preferences(&self, preferences: &Preferences) {
        let after = {
            let mut guarded = self.inner.lock();
            guarded.follow(preferences, &self.inner);
            guarded.status.clone()
        };
        self.inner.listener.changed(after);
    }

    /// Whether a check of its own is due: none ran in this run yet, the last was a day ago, or failed six hours ago.
    pub fn is_due(&self) -> bool {
        let guarded = self.inner.lock();
        if !self.inner.enabled || guarded.running.is_some() {
            return false;
        }
        let Some(last) = guarded.last_attempt else {
            return true;
        };
        let wait = if guarded.last_failed { RETRY } else { EVERY.saturating_add(self.inner.jitter) };
        let due_at = last.checked_add(wait).unwrap_or(last);
        (self.inner.clock)() >= due_at
    }

    /// Looks for a new version of `channel`, or joins the check running; completes with the status after it, with the
    /// reason when it failed. `automatic`, the check is one njMule runs on its own and tells of what it finds.
    pub fn check(&self, channel: UpdateChannel, automatic: bool) -> BoxFuture<'static, UpdateStatus> {
        let (starting, rx) = {
            let mut guarded = self.inner.lock();
            if let Some(running) = &guarded.running {
                let rx = running.subscribe();
                let inner = Arc::clone(&self.inner);
                return Box::pin(await_watch(rx, inner));
            }
            if !self.inner.enabled {
                let last_check = guarded.status.last_check;
                let available = guarded.status.available.clone();
                let failed = guarded.with_check(
                    &self.inner,
                    false,
                    last_check,
                    Some("update checks are turned off for this run".to_owned()),
                    available,
                );
                return Box::pin(async move { failed });
            }
            let (tx, rx) = watch::channel(None);
            guarded.running = Some(tx.clone());
            guarded.last_attempt = Some((self.inner.clock)());
            let last_check = guarded.status.last_check;
            let failure = guarded.status.failure.clone();
            let available = guarded.status.available.clone();
            guarded.status = guarded.with_check(&self.inner, true, last_check, failure, available);
            let starting = guarded.status.clone();
            let handle = tokio::spawn(run_check(Arc::clone(&self.inner), tx, channel, automatic));
            guarded.checker = Some(handle.abort_handle());
            (starting, rx)
        };
        self.inner.listener.changed(starting);
        Box::pin(await_watch(rx, Arc::clone(&self.inner)))
    }

    /// Stops telling of `version`, unless it fixes a security problem; returns the status.
    pub fn skip(&self, version: &str) -> Result<UpdateStatus, String> {
        let skipped = Version::parse(version).map_err(|_| format!("not a version: {version}"))?;
        let (next, after) = {
            let mut guarded = self.inner.lock();
            let next = guarded.state.skipping(&skipped.to_string());
            guarded.state = next.clone();
            guarded.status = guarded.rebuild(&self.inner);
            (next, guarded.status.clone())
        };
        save_state(&self.inner.state_file, &next);
        self.inner.listener.changed(after.clone());
        Ok(after)
    }

    /// Downloads the package of the available version for this installation, checks it and unpacks it, or joins the
    /// download running; completes with the status after it, with the reason when it failed, or fails when there is
    /// nothing to download.
    pub fn download(&self) -> BoxFuture<'static, Result<UpdateStatus, String>> {
        let (starting, rx) = {
            let mut guarded = self.inner.lock();
            if let Some(downloading) = &guarded.downloading {
                let rx = downloading.subscribe();
                let inner = Arc::clone(&self.inner);
                return Box::pin(async move { Ok(await_watch(rx, inner).await) });
            }
            let Some(version) = guarded.status.available.clone() else {
                return Box::pin(async { Err("no new version is known: look for one first".to_owned()) });
            };
            let Some(download) = version.download.clone() else {
                let page = version.page;
                return Box::pin(
                    async move { Err(format!("the release has no package for this installation: {page}")) },
                );
            };
            if guarded.status.ready.is_some() {
                let status = guarded.status.clone();
                return Box::pin(async move { Ok(status) });
            }
            let (tx, rx) = watch::channel(None);
            guarded.downloading = Some(tx.clone());
            let checking = guarded.status.checking;
            let last_check = guarded.status.last_check;
            let available = guarded.status.available.clone();
            guarded.status = guarded.with_download(
                &self.inner,
                checking,
                last_check,
                None,
                available,
                Some(Progress::new(0, download.size)),
            );
            let starting = guarded.status.clone();
            let handle = tokio::spawn(run_download(Arc::clone(&self.inner), tx, version));
            guarded.downloader = Some(handle.abort_handle());
            (starting, rx)
        };
        self.inner.listener.changed(starting);
        let inner = Arc::clone(&self.inner);
        Box::pin(async move { Ok(await_watch(rx, inner).await) })
    }

    /// Asks to install the version ready: at once, stopping and starting njMule again (`restart`), or when njMule
    /// exits. Fails when no version is ready or njMule cannot install it here.
    pub fn install(&self, restart: bool) -> Result<UpdateStatus, String> {
        self.install_inner(restart, false)
    }

    fn install_inner(&self, restart: bool, by_itself: bool) -> Result<UpdateStatus, String> {
        let (after, version) = {
            let mut guarded = self.inner.lock();
            if guarded.status.ready.is_none() {
                return Err("no new version is downloaded yet".to_owned());
            }
            if guarded.status.installer == Installer::Nobody || (guarded.staged.is_none() && self.inner.needs_staging())
            {
                return Err("njMule cannot replace itself here: install the downloaded package as usual".to_owned());
            }
            let Some(version) = guarded.status.available.as_ref().map(|version| version.version.clone()) else {
                return Err("no new version is downloaded yet".to_owned());
            };
            if restart {
                guarded.unattended = by_itself;
            } else {
                guarded.install_asked = true;
            }
            let checking = guarded.status.checking;
            let last_check = guarded.status.last_check;
            let failure = guarded.status.failure.clone();
            let available = guarded.status.available.clone();
            let download = guarded.status.download;
            let ready = guarded.status.ready.clone();
            let install = if restart { Install::Restart } else { Install::AtExit };
            guarded.status =
                guarded.with_all(&self.inner, checking, last_check, failure, available, download, ready, install);
            (guarded.status.clone(), version)
        };
        self.inner.listener.changed(after.clone());
        if restart {
            tracing::info!("Restarting to install {} {version}", nj_mule_info::NAME);
            self.inner.listener.restart(version);
        }
        Ok(after)
    }

    /// In the night hours of the preferences, asks to restart for the version ready, when njMule installs new versions
    /// on its own; returns whether it did.
    pub fn install_if_night(&self, now: Time) -> bool {
        {
            let guarded = self.inner.lock();
            if guarded.mode != UpdateMode::Install
                || guarded.install_time != UpdateInstallTime::ExitOrNight
                || guarded.status.install != Install::AtExit
                || !guarded.night_hours.holds(now)
            {
                return false;
            }
        }
        self.install_inner(true, true).is_ok()
    }

    /// Once the engine has stopped: the installation to hand over, when the user asked for it or the preferences install
    /// at exit; none otherwise.
    pub fn hand_over(&self) -> Option<HandOver> {
        let guarded = self.inner.lock();
        if guarded.status.install == Install::Asked {
            return None;
        }
        let package_file = guarded.status.ready.clone()?;
        let version = guarded.status.available.as_ref()?;
        let restart = guarded.status.install == Install::Restart;
        let root = self.inner.installation.root();
        let probe = self
            .inner
            .installation
            .executable
            .as_deref()
            .and_then(|executable| root.as_deref().and_then(|root| executable.strip_prefix(root).ok()))
            .map(|relative| relative.to_string_lossy().into_owned());
        let staged = guarded.staged.clone();
        let plan = ApplyPlan {
            version: version.version.clone(),
            from: self.inner.current.to_string(),
            kind: self.inner.installation.kind,
            package_file,
            target: if staged.is_some() { root } else { None },
            probe: if staged.is_some() { probe } else { None },
            staged,
            restart,
            unattended: if restart { guarded.unattended } else { !guarded.install_asked },
            program: None,
            arguments: Vec::new(),
            state: PlanState::HandedOver,
            reason: None,
            helper: None,
            time: (self.inner.clock)(),
        };
        Some(HandOver::new(
            plan,
            self.inner.installation.clone(),
            self.inner.files.clone(),
            Arc::clone(&self.inner.commands),
            Arc::clone(&self.inner.launcher),
            Arc::clone(&self.inner.clock),
            self.inner.desktop,
        ))
    }

    /// The engine of this version has run long enough: the version is kept. Leaves the mark the installer waits for,
    /// removes the old files an installation kept and tells what the last installation did; returns that, for the log.
    pub fn ran_long_enough(&self) -> Option<String> {
        let current = self.inner.current.to_string();
        if let Err(e) = mark_started(&self.inner.files, &current) {
            tracing::debug!("Cannot mark the start of this version: {e}");
        }
        let plan = {
            let mut guarded = self.inner.lock();
            guarded.outcome.take().filter(|plan| plan.version == current)
        }?;
        let message = format!("{} was updated from {} to {}", nj_mule_info::NAME, plan.from, plan.version);
        let inner = Arc::clone(&self.inner);
        tokio::spawn(finish(inner, plan));
        Some(message)
    }

    /// Stops the check and the download running, if any.
    pub fn close(&self) {
        let mut guarded = self.inner.lock();
        if let Some(checker) = guarded.checker.take() {
            checker.abort();
        }
        if let Some(downloader) = guarded.downloader.take() {
            downloader.abort();
        }
    }
}

impl<F, T> Inner<F, T> {
    fn lock(&self) -> std::sync::MutexGuard<'_, Guarded> {
        self.guarded.lock().unwrap_or_else(PoisonError::into_inner)
    }

    // An installer package installs itself; archives and bundles are swapped by njMule.
    fn needs_staging(&self) -> bool {
        self.installation.kind != InstallKind::Msi
    }

    // The folders a new download keeps: its own, and the one of an installation not yet made final.
    fn kept(&self, version: &str) -> Vec<String> {
        let guarded = self.lock();
        match &guarded.outcome {
            Some(plan) => vec![version.to_owned(), plan.version.clone()],
            None => vec![version.to_owned()],
        }
    }
}

impl Guarded {
    fn follow<F, T>(&mut self, preferences: &Preferences, inner: &Inner<F, T>) {
        self.mode = preferences.update_mode;
        self.install_time = preferences.update_install_time;
        self.night_hours = preferences.night_hours;
        self.status = self.rebuild(inner);
    }

    // The status once the preferences or the choices changed: when the version ready installs.
    fn rebuild<F, T>(&mut self, inner: &Inner<F, T>) -> UpdateStatus {
        let install = self.install_when();
        let checking = self.status.checking;
        let last_check = self.status.last_check;
        let failure = self.status.failure.clone();
        let available = self.status.available.clone();
        let download = self.status.download;
        let ready = self.status.ready.clone();
        self.with_all(inner, checking, last_check, failure, available, download, ready, install)
    }

    fn install_when(&self) -> Install {
        if self.status.install == Install::Restart {
            return Install::Restart;
        }
        if self.status.ready.is_none() || self.status.installer == Installer::Nobody {
            return Install::Asked;
        }
        if self.install_asked {
            return Install::AtExit;
        }
        // On its own only what needs nobody, and never again a version whose installation failed.
        let version = self.status.available.as_ref().map(|version| version.version.as_str()).unwrap_or_default();
        let by_itself = self.mode == UpdateMode::Install
            && self.status.installer == Installer::Itself
            && self.state.failed_install.as_deref() != Some(version);
        if by_itself { Install::AtExit } else { Install::Asked }
    }

    // A new version found keeps its download only when it is the same.
    fn with_check<F, T>(
        &mut self,
        inner: &Inner<F, T>,
        checking: bool,
        last_check: Option<Timestamp>,
        failure: Option<String>,
        available: Option<NewVersion>,
    ) -> UpdateStatus {
        let same = available.as_ref().map(|v| v.version.as_str())
            == self.status.available.as_ref().map(|v| v.version.as_str());
        let download = if same { self.status.download } else { None };
        let ready = if same { self.status.ready.clone() } else { None };
        let install = if same { self.status.install } else { Install::Asked };
        self.with_all(inner, checking, last_check, failure, available, download, ready, install)
    }

    fn with_download<F, T>(
        &mut self,
        inner: &Inner<F, T>,
        checking: bool,
        last_check: Option<Timestamp>,
        failure: Option<String>,
        available: Option<NewVersion>,
        download: Option<Progress>,
    ) -> UpdateStatus {
        let ready = self.status.ready.clone();
        let install = self.status.install;
        self.with_all(inner, checking, last_check, failure, available, download, ready, install)
    }

    #[allow(clippy::too_many_arguments)]
    fn with_all<F, T>(
        &mut self,
        inner: &Inner<F, T>,
        checking: bool,
        last_check: Option<Timestamp>,
        failure: Option<String>,
        available: Option<NewVersion>,
        download: Option<Progress>,
        ready: Option<PathBuf>,
        install: Install,
    ) -> UpdateStatus {
        if ready.is_none() {
            self.staged = None;
        }
        UpdateStatus {
            current: inner.current.to_string(),
            installation: inner.installation.kind,
            installer: inner.installation.installer,
            checking,
            last_check,
            failure,
            available,
            skipped: self.state.skipped.clone(),
            download,
            ready,
            install,
        }
    }
}

async fn await_watch<F, T>(mut rx: watch::Receiver<Option<UpdateStatus>>, inner: Arc<Inner<F, T>>) -> UpdateStatus {
    match rx.wait_for(Option::is_some).await {
        Ok(value) => value.clone().unwrap_or_else(|| inner.lock().status.clone()),
        Err(_) => inner.lock().status.clone(),
    }
}

async fn run_check<F: Fetcher + 'static, T: Transfer + 'static>(
    inner: Arc<Inner<F, T>>,
    tx: watch::Sender<Option<UpdateStatus>>,
    channel: UpdateChannel,
    automatic: bool,
) {
    let fetched = match inner.releases.latest(channel).await {
        Ok(signed) => verified(&signed, &inner.keys),
        Err(e) => Err(e.to_string()),
    };
    let (manifest, mut failure) = match fetched {
        Ok(manifest) => (Some(manifest), None),
        Err(reason) => (None, Some(reason)),
    };
    let mut tell = None;
    let mut next = None;
    let mut fetch = false;
    let after = {
        let mut guarded = inner.lock();
        guarded.running = None;
        guarded.checker = None;
        let offered = manifest.as_ref().map(|manifest| &manifest.version);
        let highest = guarded.state.highest.get(&channel);
        if let (Some(offered), Some(highest)) = (offered, highest)
            && offered < highest
        {
            failure = Some(format!("the update server offered {offered}, older than the {highest} it offered before"));
        }
        guarded.last_failed = failure.is_some();
        if let Some(reason) = failure {
            tracing::debug!("Update check failed: {reason}");
            let last_check = guarded.status.last_check;
            let available = guarded.status.available.clone();
            guarded.status = guarded.with_check(&inner, false, last_check, Some(reason), available);
        } else if let Some(manifest) = manifest {
            let now = (inner.clock)();
            let updated = guarded.state.checked(now, channel, &manifest.version);
            guarded.state = updated.clone();
            next = Some(updated);
            let available = is_offered(&manifest.version, &inner.current, channel)
                .then(|| new_version(&manifest, &inner.installation, &inner.releases));
            guarded.status = guarded.with_check(&inner, false, Some(now), None, available);
            if guarded.status.is_worth_telling()
                && let Some(offered) = guarded.status.available.clone()
            {
                if guarded.told.as_deref() != Some(offered.version.as_str()) {
                    guarded.told = Some(offered.version.clone());
                    if automatic {
                        tell = Some(offered.clone());
                    }
                }
                // The Package Center installs from the browser's computer: a package downloaded on the NAS serves
                // nobody.
                fetch = matches!(guarded.mode, UpdateMode::Download | UpdateMode::Install)
                    && inner.installation.kind != InstallKind::Synology
                    && offered.download.is_some()
                    && guarded.status.ready.is_none();
            }
        }
        guarded.status.clone()
    };
    if let Some(next) = next {
        save_state(&inner.state_file, &next);
    }
    inner.listener.changed(after.clone());
    if let Some(version) = tell {
        inner.listener.found(version);
    }
    if fetch {
        // Started before the check answers, its outcome goes to the listener.
        drop(Updates { inner: Arc::clone(&inner) }.download());
    }
    let _ = tx.send_replace(Some(after));
}

fn verified(signed: &Signed, keys: &[PublicKey]) -> Result<UpdateManifest, String> {
    if signed.manifest.len() > update_manifest::MAX_SIZE {
        return Err("the update information is too large".to_owned());
    }
    let signature = minisign::signature(&signed.signature)
        .map_err(|e| format!("the update information is not signed by njMule: {e}"))?;
    minisign::verify(&signed.manifest, &signature, keys)
        .map_err(|e| format!("the update information is not signed by njMule: {e}"))?;
    UpdateManifest::parse(&signed.manifest).map_err(|e| e.to_string())
}

// Newer than this version, and a test version only to those who want them.
fn is_offered(offered: &Version, current: &Version, channel: UpdateChannel) -> bool {
    offered > current && (channel == UpdateChannel::Prerelease || offered.pre.is_empty())
}

fn new_version<F: Fetcher>(
    manifest: &UpdateManifest,
    installation: &Installation,
    releases: &GitHubReleases<F>,
) -> NewVersion {
    let download = manifest.asset(&installation.platform, installation.kind.id()).map(|asset| Download {
        url: releases.download(&manifest.version, &asset.name),
        size: asset.size,
        sha256: asset.sha256.clone(),
    });
    NewVersion {
        version: manifest.version.to_string(),
        published: manifest.published,
        critical: manifest.critical,
        notes: manifest.notes.clone(),
        page: releases.page(&manifest.version),
        download,
    }
}

// The download task: the package, then what it unpacks to.
async fn run_download<F: Fetcher + 'static, T: Transfer + 'static>(
    inner: Arc<Inner<F, T>>,
    tx: watch::Sender<Option<UpdateStatus>>,
    version: NewVersion,
) {
    let outcome = download_and_prepare(&inner, &version).await;
    let (file, unpacked, failure) = match outcome {
        Ok((file, unpacked)) => (Some(file), unpacked, None),
        Err(reason) => {
            let failure = format!("the download of {} {} failed: {reason}", nj_mule_info::NAME, version.version);
            tracing::warn!("{failure}");
            (None, None, Some(failure))
        }
    };
    let mut ready = false;
    let after = {
        let mut guarded = inner.lock();
        guarded.downloading = None;
        guarded.downloader = None;
        let same = guarded.status.available.as_ref().map(|v| v.version.as_str()) == Some(version.version.as_str());
        if failure.is_none() && same {
            guarded.staged = unpacked;
            ready = true;
            let checking = guarded.status.checking;
            let last_check = guarded.status.last_check;
            let available = guarded.status.available.clone();
            guarded.status =
                guarded.with_all(&inner, checking, last_check, None, available, None, file, Install::Asked);
            guarded.status = guarded.rebuild(&inner);
        } else {
            let checking = guarded.status.checking;
            let last_check = guarded.status.last_check;
            let available = guarded.status.available.clone();
            guarded.status = guarded.with_download(&inner, checking, last_check, failure, available, None);
        }
        guarded.status.clone()
    };
    inner.listener.changed(after.clone());
    if ready {
        tracing::info!("{} {} is downloaded and ready to install", nj_mule_info::NAME, version.version);
        inner.listener.ready(version);
    }
    let _ = tx.send_replace(Some(after));
}

async fn download_and_prepare<F: Fetcher + 'static, T: Transfer + 'static>(
    inner: &Arc<Inner<F, T>>,
    version: &NewVersion,
) -> io::Result<(PathBuf, Option<PathBuf>)> {
    let download = version.download.as_ref().ok_or_else(|| io::Error::other("no package for this installation"))?;
    let name = file_name(&download.url);
    let kept = inner.kept(&version.version);
    let files = inner.files.clone();
    let current = inner.current.to_string();
    let _ = tokio::task::spawn_blocking(move || {
        let refs: Vec<&str> = kept.iter().map(String::as_str).collect();
        files.keep_only(&refs, &current);
    })
    .await;
    let target = inner.files.package_file(&version.version, &name)?;
    let progress_inner = Arc::clone(inner);
    let progress_version = version.version.clone();
    let total = download.size;
    let file = package_download::fetch(&inner.transfer, download, &target, &move |received| {
        report_progress(&progress_inner, &progress_version, received, total);
    })
    .await?;
    let unpacked = prepare(inner, &version.version, &file).await?;
    Ok((file, unpacked))
}

// What njMule installs itself is unpacked now, while the old version still runs; a disk image is opened.
async fn prepare<F: Fetcher + 'static, T: Transfer + 'static>(
    inner: &Arc<Inner<F, T>>,
    version: &str,
    file: &Path,
) -> io::Result<Option<PathBuf>> {
    if inner.installation.installer == Installer::Nobody || !inner.needs_staging() {
        return Ok(None);
    }
    let unpacked = inner.files.unpacked(version)?;
    let kind = inner.installation.kind;
    let file = file.to_path_buf();
    let commands = Arc::clone(&inner.commands);
    tokio::task::spawn_blocking(move || {
        update_files::delete_tree(&unpacked)?;
        if kind == InstallKind::Dmg {
            return disk_images::extract(&file, &unpacked, commands.as_ref()).map(Some);
        }
        archives::unpack(&file, &unpacked)?;
        Ok(Some(unpacked))
    })
    .await
    .map_err(|e| io::Error::other(format!("unpack stopped: {e}")))?
}

fn report_progress<F, T>(inner: &Inner<F, T>, version: &str, received: u64, size: u64) {
    let after = {
        let mut guarded = inner.lock();
        let now = (inner.clock)();
        let next_at = guarded.last_progress.checked_add(PROGRESS_EVERY).unwrap_or(guarded.last_progress);
        let same = guarded.status.available.as_ref().map(|v| v.version.as_str()) == Some(version);
        if guarded.downloading.is_none() || (received < size && now < next_at) || !same {
            return;
        }
        guarded.last_progress = now;
        let checking = guarded.status.checking;
        let last_check = guarded.status.last_check;
        let failure = guarded.status.failure.clone();
        let available = guarded.status.available.clone();
        guarded.status =
            guarded.with_download(inner, checking, last_check, failure, available, Some(Progress::new(received, size)));
        guarded.status.clone()
    };
    inner.listener.changed(after);
}

// What the last installation left: told at this start, its leftovers removed once this version has run long enough.
fn read_outcome(
    guarded: &mut Guarded,
    current: &Version,
    state_file: &Path,
    files: &UpdateFiles,
    commands: &Arc<dyn Commands>,
) {
    let last = match ApplyPlan::load(&files.plan()) {
        Ok(Some(plan)) => plan,
        Ok(None) => return,
        Err(e) => {
            tracing::warn!("{e}");
            delete_plan(files);
            return;
        }
    };
    let running = current.to_string();
    if last.version == running {
        tracing::info!("{} {running} replaced {}", nj_mule_info::NAME, last.from);
        guarded.outcome = Some(last);
        return;
    }
    if last.from != running {
        delete_plan(files);
        return;
    }
    // The old version runs: the installation failed, or stopped half-way and is undone now.
    let reason = last.reason.clone().unwrap_or_else(|| reason(&last, files));
    if last.staged.is_some() && last.state != PlanState::RolledBack {
        match update_helper::swap(&last, files, Arc::clone(commands)).and_then(|swap| swap.roll_back()) {
            Ok(()) => {}
            Err(e) => tracing::warn!("Cannot undo the installation of {}: {e}", last.version),
        }
    }
    let failure = format!("the installation of {} {} failed: {reason}", nj_mule_info::NAME, last.version);
    tracing::warn!("{failure}");
    guarded.state = guarded.state.failing(Some(&last.version));
    save_state(state_file, &guarded.state);
    guarded.status = UpdateStatus {
        current: guarded.status.current.clone(),
        installation: guarded.status.installation,
        installer: guarded.status.installer,
        checking: false,
        last_check: guarded.status.last_check,
        failure: Some(failure),
        available: None,
        skipped: guarded.status.skipped.clone(),
        download: None,
        ready: None,
        install: Install::Asked,
    };
    delete_plan(files);
}

// Why an installation the installer did not describe failed: for the MSI, what msiexec returned.
fn reason(plan: &ApplyPlan, files: &UpdateFiles) -> String {
    if plan.kind != InstallKind::Msi {
        return "it did not finish".to_owned();
    }
    let Ok(folder) = files.version(&plan.version) else {
        return "Windows Installer did not run".to_owned();
    };
    match fs::read_to_string(folder.join(hand_over::INSTALLER_RESULT)) {
        Ok(code) => {
            let code = code.trim();
            let why = match code {
                "1602" => "it was cancelled".to_owned(),
                "1618" => "another installation was running".to_owned(),
                other => format!("Windows Installer ended with code {other}"),
            };
            format!("{why} (log: {})", folder.join(hand_over::INSTALLER_LOG).display())
        }
        Err(_) => "Windows Installer did not run".to_owned(),
    }
}

// The old files go once the installer that may still hold them has ended; then the plan and the packages.
async fn finish<F: Fetcher + 'static, T: Transfer + 'static>(inner: Arc<Inner<F, T>>, plan: ApplyPlan) {
    if plan.staged.is_some() && plan.target.is_some() {
        for attempt in 0..=CLEANUP_ATTEMPTS {
            let plan_copy = plan.clone();
            let files = inner.files.clone();
            let commands = Arc::clone(&inner.commands);
            let cleaned =
                tokio::task::spawn_blocking(move || update_helper::swap(&plan_copy, &files, commands)?.clean_up())
                    .await;
            match cleaned {
                Ok(Ok(())) => break,
                Ok(Err(e)) if attempt >= CLEANUP_ATTEMPTS => {
                    tracing::warn!("Cannot remove the files of {} {}: {e}", nj_mule_info::NAME, plan.from);
                    return;
                }
                Err(e) if attempt >= CLEANUP_ATTEMPTS => {
                    tracing::warn!("Cannot remove the files of {} {}: {e}", nj_mule_info::NAME, plan.from);
                    return;
                }
                _ => tokio::time::sleep(CLEANUP_WAIT).await,
            }
        }
    }
    let files = inner.files.clone();
    let _ = tokio::task::spawn_blocking(move || delete_plan(&files)).await;
    let kept = {
        let guarded = inner.lock();
        if guarded.downloading.is_some() {
            return;
        }
        if guarded.status.ready.is_some() {
            guarded.status.available.as_ref().map(|version| version.version.clone())
        } else {
            None
        }
    };
    let files = inner.files.clone();
    let current = inner.current.to_string();
    let _ = tokio::task::spawn_blocking(move || {
        let versions: Vec<&str> = kept.as_deref().into_iter().collect();
        files.keep_only(&versions, &current);
    })
    .await;
}

fn mark_started(files: &UpdateFiles, version: &str) -> io::Result<()> {
    fs::create_dir_all(files.root())?;
    let mark = files.started(version)?;
    if !mark.exists() {
        let _ = OpenOptions::new().write(true).create_new(true).open(mark);
    }
    Ok(())
}

fn delete_plan(files: &UpdateFiles) {
    match fs::remove_file(files.plan()) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => {
            tracing::debug!("Cannot delete {}: {e}", files.plan().display());
        }
        _ => {}
    }
}

fn file_name(url: &str) -> String {
    let path = Url::parse(url).map(|parsed| parsed.path().to_owned()).unwrap_or_else(|_| url.to_owned());
    path.rsplit('/').next().unwrap_or_default().to_owned()
}

fn save_state(state_file: &Path, next: &UpdateState) {
    if let Err(e) = next.save(state_file) {
        tracing::warn!("Cannot save {}: {e}", state_file.display());
    }
}
