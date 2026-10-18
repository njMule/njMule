/*
 * njMule - Next generation Java Mule
 * SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <https://www.gnu.org/licenses/>.
 */
package org.njmule.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateHandOver;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.api.UpdateStatus.Install;
import org.njmule.core.api.UpdateStatus.Installer;
import org.njmule.core.config.NightHours;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;
import org.njmule.core.net.WebLists;
import org.njmule.core.persist.PreferencesCodec;
import org.semver4j.Semver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks for new versions of njMule and installs them: fetches the manifest of the newest release of a channel with its
 * signature, checks the signature against the keys built into njMule and tells whether that release is newer than the
 * one running; downloads its package for this installation, checked against the manifest, and unpacks it; and, once the
 * engine has stopped, hands the installation over to an installer of its own process. One check and one download run at
 * a time, each on a thread of its own.
 */
public final class Updates implements AutoCloseable {

    /** Set to {@code true}, njMule never looks for new versions: tests must not reach GitHub. */
    public static final String DISABLED_PROPERTY = "njmule.update.disabled";

    static final URI REPOSITORY = URI.create(NjMuleInfo.HOMEPAGE);
    static final URI API = URI.create("https://api.github.com/repos/njMule/njMule");
    /** How long after a check the next one is due, and after a failed one. */
    static final Duration EVERY = Duration.ofDays(1);

    static final Duration RETRY = Duration.ofHours(6);
    // Clients started together would otherwise all ask at the same minute.
    private static final long JITTER_MILLIS = Duration.ofHours(1).toMillis();
    private static final SecureRandom JITTER_RANDOM = new SecureRandom();
    private static final String KEYS_RESOURCE = "keys.pub";
    // Progress is told at most this often.
    private static final Duration PROGRESS_EVERY = Duration.ofMillis(500);

    private static final Logger logger = LoggerFactory.getLogger(Updates.class);

    private final GitHubReleases releases;
    private final PackageDownload.Transfer transfer;
    private final Map<String, Minisign.PublicKey> keys;
    private final Installation installation;
    private final Semver current;
    private final Path stateFile;
    private final UpdateFiles files;
    private final Commands commands;
    private final Launcher launcher;
    private final InstantSource clock;
    private final Duration jitter;
    private final boolean enabled;
    private final boolean desktop;
    private final Listener listener;
    private final Object lock = new Object();
    // Guarded by lock.
    private UpdateState state;
    private UpdateStatus status;
    private CompletableFuture<UpdateStatus> running;
    private Thread checker;
    private CompletableFuture<UpdateStatus> downloading;
    private Thread downloader;
    private Instant lastProgress = Instant.EPOCH;
    private Instant lastAttempt;
    private boolean lastFailed;
    private String told;
    private Optional<Path> staged = Optional.empty();
    private boolean installAsked;
    private boolean unattended;
    private UpdateMode mode = UpdateMode.NOTIFY;
    private UpdateInstallTime installTime = UpdateInstallTime.EXIT;
    private NightHours nightHours = NightHours.DEFAULT;
    // What the last installation left for this start to tell: its plan, and what happened.
    private Optional<ApplyPlan> outcome = Optional.empty();

    /** Hears what the checks, downloads and installations do. */
    public interface Listener {
        /** The status changed: a check or download started, progressed or ended, or the user chose something. */
        void changed(UpdateStatus status);

        /** A check njMule ran on its own found a new version worth telling of; once a run for each. */
        void found(NewVersion version);

        /** The package of {@code version} is downloaded and checked: it can be installed. */
        void ready(NewVersion version);

        /**
         * njMule is to stop, install {@code version} and start again: the front end that owns the process stops the
         * engine and hands the installation over.
         */
        void restart(String version);
    }

    @SuppressWarnings("java:S107") // the parts are given apart so that tests can replace each one
    Updates(
            GitHubReleases releases,
            PackageDownload.Transfer transfer,
            Map<String, Minisign.PublicKey> keys,
            Installation installation,
            String current,
            Path stateFile,
            UpdateFiles files,
            Commands commands,
            Launcher launcher,
            InstantSource clock,
            Duration jitter,
            boolean enabled,
            boolean desktop,
            Listener listener) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.keys = Map.copyOf(keys);
        this.installation = Objects.requireNonNull(installation, "installation");
        this.current = Objects.requireNonNull(Semver.parse(current), () -> "not a version: " + current);
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.files = Objects.requireNonNull(files, "files");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.enabled = enabled;
        this.desktop = desktop;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.state = UpdateState.load(stateFile);
        this.status = new UpdateStatus(
                this.current.getVersion(),
                installation.kind(),
                installation.installer(),
                false,
                this.state.lastCheck(),
                Optional.empty(),
                Optional.empty(),
                this.state.skipped(),
                Optional.empty(),
                Optional.empty(),
                Install.ASKED);
        readOutcome();
    }

    /**
     * The updates of this copy of njMule, fetched through {@code web} (or, when the preferences name another release
     * server, from there with any address), remembered in {@code configDir} and downloaded to {@code cacheDir}.
     */
    public static Updates create(
            Path configDir, Path cacheDir, WebLists web, Preferences preferences, Listener listener) {
        Optional<URI> elsewhere = preferences.updateUrl();
        WebLists reach = elsewhere.isPresent() ? WebLists.anyAddress() : web;
        URI repository = elsewhere.orElse(REPOSITORY);
        URI api = elsewhere
                .map(u -> URI.create(u.toString().replaceAll("/+$", "") + "/api"))
                .orElse(API);
        Updates updates = new Updates(
                new GitHubReleases(reach::fetch, repository, api),
                reach::download,
                shippedKeys(),
                Installation.current(),
                NjMuleInfo.version(),
                configDir.resolve(UpdateState.FILE_NAME),
                UpdateFiles.in(cacheDir),
                Commands.SYSTEM,
                Launcher.SYSTEM,
                InstantSource.system(),
                Duration.ofMillis(JITTER_RANDOM.nextLong(JITTER_MILLIS)),
                !Boolean.getBoolean(DISABLED_PROPERTY),
                HandOver.desktop(),
                listener);
        synchronized (updates.lock) {
            updates.follow(preferences);
        }
        return updates;
    }

    /** The public keys release manifests are signed with, built into njMule. */
    static Map<String, Minisign.PublicKey> shippedKeys() {
        try (InputStream in = Updates.class.getResourceAsStream(KEYS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("no update keys built in");
            }
            return Minisign.publicKeys(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Records that this version starts on the configuration in {@code configDir}; returns whether another version used
     * it before, or one that kept no record: a backup of the configuration is due before this one changes it.
     */
    public static boolean starting(Path configDir) {
        Path file = configDir.resolve(UpdateState.FILE_NAME);
        UpdateState state = UpdateState.load(file);
        String version = NjMuleInfo.version();
        if (state.ran().equals(Optional.of(version))) {
            return false;
        }
        boolean used = state.ran().isPresent() || Files.exists(configDir.resolve(PreferencesCodec.FILE_NAME));
        try {
            state.running(version).save(file);
        } catch (IOException e) {
            logger.warn("Cannot save {}: {}", file, e.toString());
        }
        Semver now = Semver.parse(version);
        state.ran()
                .map(Semver::parse)
                .filter(before -> now != null && before != null && before.isGreaterThan(now))
                .ifPresent(before -> logger.warn(
                        "The configuration was last used by {} {}, newer than this one: it may not read all of it",
                        NjMuleInfo.NAME,
                        before.getVersion()));
        return used;
    }

    public UpdateStatus status() {
        synchronized (this.lock) {
            return this.status;
        }
    }

    /** Follows the preferences: what to do with a new version, and when to install it. */
    public void preferences(Preferences preferences) {
        UpdateStatus after;
        synchronized (this.lock) {
            follow(preferences);
            after = this.status;
        }
        this.listener.changed(after);
    }

    // Guarded by lock.
    private void follow(Preferences preferences) {
        this.mode = preferences.updateMode();
        this.installTime = preferences.updateInstallTime();
        this.nightHours = preferences.nightHours();
        this.status = rebuild();
    }

    /** Whether a check of its own is due: none ran in this run yet, the last was a day ago, or failed six hours ago. */
    public boolean isDue() {
        synchronized (this.lock) {
            if (!this.enabled || this.running != null) {
                return false;
            }
            if (this.lastAttempt == null) {
                return true;
            }
            Duration wait = this.lastFailed ? RETRY : EVERY.plus(this.jitter);
            return !this.clock.instant().isBefore(this.lastAttempt.plus(wait));
        }
    }

    /**
     * Looks for a new version of {@code channel}, or joins the check running; completes with the status after it, with
     * the reason when it failed. {@code automatic}, the check is one njMule runs on its own and tells of what it finds.
     */
    public CompletableFuture<UpdateStatus> check(UpdateChannel channel, boolean automatic) {
        Objects.requireNonNull(channel, "channel");
        CompletableFuture<UpdateStatus> result;
        UpdateStatus starting;
        synchronized (this.lock) {
            if (this.running != null) {
                return this.running.copy();
            }
            if (!this.enabled) {
                return CompletableFuture.completedFuture(failed("update checks are turned off for this run"));
            }
            result = new CompletableFuture<>();
            this.running = result;
            this.lastAttempt = this.clock.instant();
            this.status = with(true, this.status.lastCheck(), this.status.failure(), this.status.available());
            starting = this.status;
            this.checker =
                    Thread.ofVirtual().name("njmule-update-check").unstarted(() -> run(result, channel, automatic));
            this.checker.start();
        }
        this.listener.changed(starting);
        return result.copy();
    }

    /** Stops telling of {@code version}, unless it fixes a security problem; returns the status. */
    public UpdateStatus skip(String version) {
        Semver skipped = Semver.parse(version);
        if (skipped == null) {
            throw new IllegalArgumentException("not a version: " + version);
        }
        UpdateState next;
        UpdateStatus after;
        synchronized (this.lock) {
            next = this.state.skipping(skipped.getVersion());
            this.state = next;
            this.status = rebuild();
            after = this.status;
        }
        save(next);
        this.listener.changed(after);
        return after;
    }

    /**
     * Downloads the package of the available version for this installation, checks it and unpacks it, or joins the
     * download running; completes with the status after it, with the reason when it failed, or fails with an
     * {@link IllegalStateException} when there is nothing to download.
     */
    public CompletableFuture<UpdateStatus> download() {
        CompletableFuture<UpdateStatus> result;
        UpdateStatus starting;
        synchronized (this.lock) {
            if (this.downloading != null) {
                return this.downloading.copy();
            }
            Optional<NewVersion> available = this.status.available();
            if (available.isEmpty()) {
                return refused("no new version is known: look for one first");
            }
            NewVersion version = available.get();
            if (version.download().isEmpty()) {
                return refused("the release has no package for this installation: " + version.page());
            }
            if (this.status.ready().isPresent()) {
                return CompletableFuture.completedFuture(this.status);
            }
            result = new CompletableFuture<>();
            this.downloading = result;
            this.status = with(
                    this.status.checking(),
                    this.status.lastCheck(),
                    Optional.empty(),
                    this.status.available(),
                    Optional.of(new UpdateStatus.Progress(
                            0, version.download().get().size())));
            starting = this.status;
            this.downloader = Thread.ofVirtual().name("njmule-update-download").unstarted(() -> fetch(result, version));
            this.downloader.start();
        }
        this.listener.changed(starting);
        return result.copy();
    }

    /**
     * Asks to install the version ready: at once, stopping and starting njMule again ({@code restart}), or when njMule
     * exits. Fails with an {@link IllegalStateException} when no version is ready or njMule cannot install it here.
     */
    public CompletableFuture<UpdateStatus> install(boolean restart) {
        return install(restart, false);
    }

    private CompletableFuture<UpdateStatus> install(boolean restart, boolean byItself) {
        UpdateStatus after;
        String version;
        synchronized (this.lock) {
            if (this.status.ready().isEmpty()) {
                return refused("no new version is downloaded yet");
            }
            if (this.status.installer() == Installer.NONE || (this.staged.isEmpty() && needsStaging())) {
                return refused("njMule cannot replace itself here: install the downloaded package as usual");
            }
            version = this.status.available().orElseThrow().version();
            if (restart) {
                this.unattended = byItself;
            } else {
                this.installAsked = true;
            }
            this.status = with(
                    this.status.checking(),
                    this.status.lastCheck(),
                    this.status.failure(),
                    this.status.available(),
                    this.status.download(),
                    restart ? Install.RESTART : Install.AT_EXIT);
            after = this.status;
        }
        this.listener.changed(after);
        if (restart) {
            logger.info("Restarting to install {} {}", NjMuleInfo.NAME, version);
            this.listener.restart(version);
        }
        return CompletableFuture.completedFuture(after);
    }

    /**
     * In the night hours of the preferences, asks to restart for the version ready, when njMule installs new versions
     * on its own; returns whether it did.
     */
    public boolean installIfNight(LocalTime now) {
        synchronized (this.lock) {
            if (this.mode != UpdateMode.INSTALL
                    || this.installTime != UpdateInstallTime.EXIT_OR_NIGHT
                    || this.status.install() != Install.AT_EXIT
                    || !this.nightHours.holds(now)) {
                return false;
            }
        }
        return !install(true, true).isCompletedExceptionally();
    }

    /**
     * Once the engine has stopped: the installation to hand over, when the user asked for it or the preferences install
     * at exit; empty otherwise.
     */
    public Optional<UpdateHandOver> handOver() {
        synchronized (this.lock) {
            if (this.status.install() == Install.ASKED || this.status.ready().isEmpty()) {
                return Optional.empty();
            }
            NewVersion version = this.status.available().orElseThrow();
            boolean restart = this.status.install() == Install.RESTART;
            Optional<Path> root = this.installation.root();
            Optional<String> probe = this.installation
                    .executable()
                    .flatMap(e -> root.map(r -> r.relativize(e).toString()));
            ApplyPlan plan = new ApplyPlan(
                    version.version(),
                    this.current.getVersion(),
                    this.installation.kind(),
                    this.status.ready().get(),
                    this.staged,
                    this.staged.isPresent() ? root : Optional.empty(),
                    this.staged.isPresent() ? probe : Optional.empty(),
                    restart,
                    restart ? this.unattended : !this.installAsked,
                    Optional.empty(),
                    List.of(),
                    ApplyPlan.State.HANDED_OVER,
                    Optional.empty(),
                    OptionalLong.empty(),
                    this.clock.instant());
            return Optional.of(new HandOver(
                    plan, this.installation, this.files, this.commands, this.launcher, this.clock, this.desktop));
        }
    }

    /**
     * The engine of this version has run long enough: the version is kept. Leaves the mark the installer waits for,
     * removes the old files an installation kept and tells what the last installation did; returns that, for the log.
     */
    public Optional<String> ranLongEnough() {
        try {
            Files.createDirectories(this.files.root());
            Path mark = this.files.started(this.current.getVersion());
            if (!Files.exists(mark)) {
                Files.createFile(mark);
            }
        } catch (IOException e) {
            logger.debug("Cannot mark the start of this version: {}", e.toString());
        }
        Optional<ApplyPlan> plan;
        synchronized (this.lock) {
            plan = this.outcome.filter(p -> p.version().equals(this.current.getVersion()));
            this.outcome = Optional.empty();
        }
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        Thread.ofVirtual().name("njmule-update-cleanup").start(() -> finish(plan.get()));
        return Optional.of(NjMuleInfo.NAME + " was updated from " + plan.get().from() + " to "
                + plan.get().version());
    }

    /** Stops the check and the download running, if any. */
    @Override
    public void close() {
        synchronized (this.lock) {
            if (this.checker != null) {
                this.checker.interrupt();
            }
            if (this.downloader != null) {
                this.downloader.interrupt();
            }
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored") // a download it starts tells its outcome to the listener
    private void run(CompletableFuture<UpdateStatus> result, UpdateChannel channel, boolean automatic) {
        UpdateManifest manifest = null;
        String failure = null;
        try {
            manifest = verified(this.releases.latest(channel));
        } catch (IOException e) {
            failure = e.getMessage();
        } catch (SignatureException e) {
            failure = "the update information is not signed by njMule: " + e.getMessage();
        } catch (RuntimeException e) {
            logger.warn("The update check failed", e);
            failure = e.toString();
        }
        NewVersion tell = null;
        UpdateState next = null;
        UpdateStatus after;
        boolean fetch = false;
        synchronized (this.lock) {
            this.running = null;
            this.checker = null;
            Semver offered = manifest == null ? null : manifest.version();
            Semver highest = this.state.highest().get(channel);
            if (offered != null && highest != null && offered.isLowerThan(highest)) {
                failure = "the update server offered " + offered.getVersion() + ", older than the "
                        + highest.getVersion() + " it offered before";
            }
            this.lastFailed = failure != null;
            if (failure != null) {
                logger.debug("Update check failed: {}", failure);
                this.status = with(false, this.status.lastCheck(), Optional.of(failure), this.status.available());
            } else {
                Instant now = this.clock.instant();
                next = this.state.checked(now, channel, offered);
                this.state = next;
                Optional<NewVersion> available = isOffered(offered, channel)
                        ? Optional.of(newVersion(Objects.requireNonNull(manifest)))
                        : Optional.empty();
                this.status = with(false, Optional.of(now), Optional.empty(), available);
                if (this.status.isWorthTelling()
                        && !available.orElseThrow().version().equals(this.told)) {
                    this.told = available.orElseThrow().version();
                    tell = automatic ? available.orElseThrow() : null;
                }
                fetch = (this.mode == UpdateMode.DOWNLOAD || this.mode == UpdateMode.INSTALL)
                        && this.status.isWorthTelling()
                        && this.status.available().orElseThrow().download().isPresent()
                        && this.status.ready().isEmpty();
            }
            after = this.status;
        }
        if (next != null) {
            save(next);
        }
        this.listener.changed(after);
        if (tell != null) {
            this.listener.found(tell);
        }
        result.complete(after);
        if (fetch) {
            // Its outcome goes to the listener.
            download();
        }
    }

    private UpdateManifest verified(GitHubReleases.Signed signed) throws IOException, SignatureException {
        if (signed.manifest().length > UpdateManifest.MAX_SIZE) {
            throw new IOException("the update information is too large");
        }
        Minisign.verify(signed.manifest(), Minisign.signature(signed.signature()), this.keys);
        return UpdateManifest.parse(signed.manifest());
    }

    // Newer than this version, and a test version only to those who want them.
    private boolean isOffered(Semver offered, UpdateChannel channel) {
        return offered.isGreaterThan(this.current)
                && (channel == UpdateChannel.PRERELEASE
                        || offered.getPreRelease().isEmpty());
    }

    private NewVersion newVersion(UpdateManifest manifest) {
        Optional<NewVersion.Download> download = manifest.asset(
                        this.installation.platform(), this.installation.kind().id())
                .map(a -> new NewVersion.Download(
                        this.releases.download(manifest.version(), a.name()), a.size(), a.sha256()));
        return new NewVersion(
                manifest.version().getVersion(),
                manifest.published(),
                manifest.critical(),
                manifest.notes(),
                this.releases.page(manifest.version()),
                download);
    }

    // The download thread: the package, then what it unpacks to.
    private void fetch(CompletableFuture<UpdateStatus> result, NewVersion version) {
        NewVersion.Download download = version.download().orElseThrow();
        String name = fileName(download.uri());
        Path file = null;
        Optional<Path> unpacked = Optional.empty();
        String failure = null;
        try {
            this.files.keepOnly(kept(version.version()), this.current.getVersion());
            file = PackageDownload.fetch(
                    this.transfer,
                    download,
                    this.files.packageFile(version.version(), name),
                    received -> progress(version, received, download.size()));
            unpacked = prepare(version.version(), file);
        } catch (IOException | RuntimeException e) {
            failure = "the download of " + NjMuleInfo.NAME + " " + version.version() + " failed: " + e.getMessage();
            logger.warn("{}", failure);
        }
        UpdateStatus after;
        boolean ready = false;
        synchronized (this.lock) {
            this.downloading = null;
            this.downloader = null;
            boolean same = this.status.available().map(NewVersion::version).equals(Optional.of(version.version()));
            if (failure == null && same) {
                this.staged = unpacked;
                ready = true;
                this.status = with(
                        this.status.checking(),
                        this.status.lastCheck(),
                        Optional.empty(),
                        this.status.available(),
                        Optional.empty(),
                        Optional.ofNullable(file),
                        Install.ASKED);
                this.status = rebuild();
            } else {
                this.status = with(
                        this.status.checking(),
                        this.status.lastCheck(),
                        Optional.ofNullable(failure),
                        this.status.available(),
                        Optional.empty());
            }
            after = this.status;
        }
        this.listener.changed(after);
        if (ready) {
            logger.info("{} {} is downloaded and ready to install", NjMuleInfo.NAME, version.version());
            this.listener.ready(version);
        }
        result.complete(after);
    }

    // What njMule installs itself is unpacked now, while the old version still runs; a disk image is opened.
    private Optional<Path> prepare(String version, Path file) throws IOException {
        if (this.installation.installer() == Installer.NONE || !needsStaging()) {
            return Optional.empty();
        }
        Path unpacked = this.files.unpacked(version);
        UpdateFiles.deleteTree(unpacked);
        if (this.installation.kind() == InstallKind.DMG) {
            return Optional.of(DiskImages.extract(file, unpacked, this.commands));
        }
        Archives.unpack(file, unpacked);
        return Optional.of(unpacked);
    }

    // An installer package installs itself; archives and bundles are swapped by njMule.
    private boolean needsStaging() {
        return this.installation.kind() != InstallKind.MSI;
    }

    private void progress(NewVersion version, long received, long size) {
        UpdateStatus after;
        synchronized (this.lock) {
            Instant now = this.clock.instant();
            if (this.downloading == null
                    || (received < size && now.isBefore(this.lastProgress.plus(PROGRESS_EVERY)))
                    || !this.status.available().map(NewVersion::version).equals(Optional.of(version.version()))) {
                return;
            }
            this.lastProgress = now;
            this.status = with(
                    this.status.checking(),
                    this.status.lastCheck(),
                    this.status.failure(),
                    this.status.available(),
                    Optional.of(new UpdateStatus.Progress(Math.min(received, size), size)));
            after = this.status;
        }
        this.listener.changed(after);
    }

    // The status once the preferences or the choices changed: when the version ready installs.
    private UpdateStatus rebuild() {
        return with(
                this.status.checking(),
                this.status.lastCheck(),
                this.status.failure(),
                this.status.available(),
                this.status.download(),
                this.status.ready(),
                installWhen());
    }

    // Guarded by lock.
    private Install installWhen() {
        if (this.status.install() == Install.RESTART) {
            return Install.RESTART;
        }
        if (this.status.ready().isEmpty() || this.status.installer() == Installer.NONE) {
            return Install.ASKED;
        }
        if (this.installAsked) {
            return Install.AT_EXIT;
        }
        // On its own only what needs nobody, and never again a version whose installation failed.
        String version = this.status.available().map(NewVersion::version).orElse("");
        boolean byItself = this.mode == UpdateMode.INSTALL
                && this.status.installer() == Installer.SELF
                && !this.state.failedInstall().equals(Optional.of(version));
        return byItself ? Install.AT_EXIT : Install.ASKED;
    }

    private static CompletableFuture<UpdateStatus> refused(String reason) {
        return CompletableFuture.failedFuture(new IllegalStateException(reason));
    }

    private UpdateStatus failed(String failure) {
        synchronized (this.lock) {
            return with(false, this.status.lastCheck(), Optional.of(failure), this.status.available());
        }
    }

    // Guarded by lock. A new version found keeps its download only when it is the same.
    private UpdateStatus with(
            boolean checking, Optional<Instant> lastCheck, Optional<String> failure, Optional<NewVersion> available) {
        boolean same = available
                .map(NewVersion::version)
                .equals(this.status.available().map(NewVersion::version));
        return with(
                checking,
                lastCheck,
                failure,
                available,
                same ? this.status.download() : Optional.empty(),
                same ? this.status.ready() : Optional.empty(),
                same ? this.status.install() : Install.ASKED);
    }

    // Guarded by lock.
    private UpdateStatus with(
            boolean checking,
            Optional<Instant> lastCheck,
            Optional<String> failure,
            Optional<NewVersion> available,
            Optional<UpdateStatus.Progress> download) {
        return with(checking, lastCheck, failure, available, download, this.status.ready(), this.status.install());
    }

    // Guarded by lock.
    private UpdateStatus with(
            boolean checking,
            Optional<Instant> lastCheck,
            Optional<String> failure,
            Optional<NewVersion> available,
            Optional<UpdateStatus.Progress> download,
            Install install) {
        return with(checking, lastCheck, failure, available, download, this.status.ready(), install);
    }

    // Guarded by lock.
    private UpdateStatus with(
            boolean checking,
            Optional<Instant> lastCheck,
            Optional<String> failure,
            Optional<NewVersion> available,
            Optional<UpdateStatus.Progress> download,
            Optional<Path> ready,
            Install install) {
        if (ready.isEmpty()) {
            this.staged = Optional.empty();
        }
        return new UpdateStatus(
                this.current.getVersion(),
                this.installation.kind(),
                this.installation.installer(),
                checking,
                lastCheck,
                failure,
                available,
                this.state.skipped(),
                download,
                ready,
                install);
    }

    // What the last installation left: told at this start, its leftovers removed once this version has run long enough.
    private void readOutcome() {
        Optional<ApplyPlan> plan;
        try {
            plan = ApplyPlan.load(this.files.plan());
        } catch (IOException e) {
            logger.warn("{}", e.getMessage());
            deletePlan();
            return;
        }
        if (plan.isEmpty()) {
            return;
        }
        ApplyPlan last = plan.get();
        String running = this.current.getVersion();
        if (last.version().equals(running)) {
            this.outcome = plan;
            logger.info("{} {} replaced {}", NjMuleInfo.NAME, running, last.from());
            return;
        }
        if (!last.from().equals(running)) {
            deletePlan();
            return;
        }
        // The old version runs: the installation failed, or stopped half-way and is undone now.
        String reason = last.reason().orElseGet(() -> reason(last));
        if (last.staged().isPresent() && last.state() != ApplyPlan.State.ROLLED_BACK) {
            try {
                UpdateHelper.swap(last, this.files, this.commands).rollBack();
            } catch (IOException | RuntimeException e) {
                logger.warn("Cannot undo the installation of {}: {}", last.version(), e.toString());
            }
        }
        String failure = "the installation of " + NjMuleInfo.NAME + " " + last.version() + " failed: " + reason;
        logger.warn("{}", failure);
        this.state = this.state.failing(Optional.of(last.version()));
        save(this.state);
        this.status = new UpdateStatus(
                this.status.current(),
                this.status.installation(),
                this.status.installer(),
                false,
                this.status.lastCheck(),
                Optional.of(failure),
                Optional.empty(),
                this.status.skipped(),
                Optional.empty(),
                Optional.empty(),
                Install.ASKED);
        deletePlan();
    }

    // Why an installation the installer did not describe failed: for the MSI, what msiexec returned.
    private String reason(ApplyPlan plan) {
        if (plan.kind() != InstallKind.MSI) {
            return "it did not finish";
        }
        Path folder = this.files.version(plan.version());
        try {
            String code = Files.readString(folder.resolve(HandOver.INSTALLER_RESULT), StandardCharsets.UTF_8)
                    .strip();
            String why = switch (code) {
                case "1602" -> "it was cancelled";
                case "1618" -> "another installation was running";
                default -> "Windows Installer ended with code " + code;
            };
            return why + " (log: " + folder.resolve(HandOver.INSTALLER_LOG) + ")";
        } catch (IOException e) {
            return "Windows Installer did not run";
        }
    }

    // The old files go once the installer that may still hold them has ended; then the plan and the packages.
    private void finish(ApplyPlan plan) {
        if (plan.staged().isPresent() && plan.target().isPresent()) {
            Swap swap = UpdateHelper.swap(plan, this.files, this.commands);
            for (int attempt = 0; ; attempt++) {
                try {
                    swap.cleanUp();
                    break;
                } catch (IOException | RuntimeException e) {
                    if (attempt >= 60) {
                        logger.warn("Cannot remove the files of {} {}: {}", NjMuleInfo.NAME, plan.from(), e.toString());
                        return;
                    }
                }
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        deletePlan();
        synchronized (this.lock) {
            if (this.downloading != null) {
                return;
            }
            this.files.keepOnly(
                    this.status.ready().isPresent()
                            ? this.status.available().map(NewVersion::version).stream()
                                    .toList()
                            : List.of(),
                    this.current.getVersion());
        }
    }

    // The folders a new download keeps: its own, and the one of an installation not yet made final.
    private List<String> kept(String version) {
        synchronized (this.lock) {
            return this.outcome.isPresent()
                    ? List.of(version, this.outcome.get().version())
                    : List.of(version);
        }
    }

    private void deletePlan() {
        try {
            Files.deleteIfExists(this.files.plan());
        } catch (IOException e) {
            logger.debug("Cannot delete {}: {}", this.files.plan(), e.toString());
        }
    }

    private static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void save(UpdateState next) {
        try {
            next.save(this.stateFile);
        } catch (IOException e) {
            logger.warn("Cannot save {}: {}", this.stateFile, e.toString());
        }
    }
}
