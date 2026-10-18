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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.net.WebLists;
import org.semver4j.Semver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks for new versions of njMule: fetches the manifest of the newest release of a channel with its signature, checks
 * the signature against the keys built into njMule, and tells whether that release is newer than the one running. One
 * check runs at a time, on a thread of its own; nothing is downloaded or installed.
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

    private static final Logger logger = LoggerFactory.getLogger(Updates.class);

    private final GitHubReleases releases;
    private final Map<String, Minisign.PublicKey> keys;
    private final Installation installation;
    private final Semver current;
    private final Path stateFile;
    private final InstantSource clock;
    private final Duration jitter;
    private final boolean enabled;
    private final Listener listener;
    private final Object lock = new Object();
    // Guarded by lock.
    private UpdateState state;
    private UpdateStatus status;
    private CompletableFuture<UpdateStatus> running;
    private Thread checker;
    private Instant lastAttempt;
    private boolean lastFailed;
    private String told;

    /** Hears what the checks find. */
    public interface Listener {
        /** A check started or ended, or the user skipped a version. */
        void changed(UpdateStatus status);

        /** A check njMule ran on its own found a new version worth telling of; once a run for each. */
        void found(NewVersion version);
    }

    Updates(
            GitHubReleases releases,
            Map<String, Minisign.PublicKey> keys,
            Installation installation,
            String current,
            Path stateFile,
            InstantSource clock,
            Duration jitter,
            boolean enabled,
            Listener listener) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.keys = Map.copyOf(keys);
        this.installation = Objects.requireNonNull(installation, "installation");
        this.current = Objects.requireNonNull(Semver.parse(current), () -> "not a version: " + current);
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.enabled = enabled;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.state = UpdateState.load(stateFile);
        this.status = new UpdateStatus(
                this.current.getVersion(),
                installation.kind(),
                false,
                this.state.lastCheck(),
                Optional.empty(),
                Optional.empty(),
                this.state.skipped());
    }

    /** The updates of this copy of njMule, fetched through {@code web} and remembered in {@code configDir}. */
    public static Updates create(Path configDir, WebLists web, Listener listener) {
        return new Updates(
                new GitHubReleases(web::fetch, REPOSITORY, API),
                shippedKeys(),
                Installation.current(),
                NjMuleInfo.version(),
                configDir.resolve(UpdateState.FILE_NAME),
                InstantSource.system(),
                Duration.ofMillis(JITTER_RANDOM.nextLong(JITTER_MILLIS)),
                !Boolean.getBoolean(DISABLED_PROPERTY),
                listener);
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

    public UpdateStatus status() {
        synchronized (this.lock) {
            return this.status;
        }
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
            this.status = new UpdateStatus(
                    this.status.current(),
                    this.status.installation(),
                    this.status.checking(),
                    this.status.lastCheck(),
                    this.status.failure(),
                    this.status.available(),
                    next.skipped());
            after = this.status;
        }
        save(next);
        this.listener.changed(after);
        return after;
    }

    /** Stops the check running, if any. */
    @Override
    public void close() {
        synchronized (this.lock) {
            if (this.checker != null) {
                this.checker.interrupt();
            }
        }
    }

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

    private UpdateStatus failed(String failure) {
        synchronized (this.lock) {
            return with(false, this.status.lastCheck(), Optional.of(failure), this.status.available());
        }
    }

    // Guarded by lock.
    private UpdateStatus with(
            boolean checking, Optional<Instant> lastCheck, Optional<String> failure, Optional<NewVersion> available) {
        return new UpdateStatus(
                this.current.getVersion(),
                this.installation.kind(),
                checking,
                lastCheck,
                failure,
                available,
                this.state.skipped());
    }

    private void save(UpdateState next) {
        try {
            next.save(this.stateFile);
        } catch (IOException e) {
            logger.warn("Cannot save {}: {}", this.stateFile, e.toString());
        }
    }
}
