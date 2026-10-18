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
package org.njmule.core.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What njMule knows of its new versions.
 *
 * @param current the version running
 * @param installation how this copy was installed, which tells how to update it
 * @param installer who puts a new version in place here
 * @param checking whether a check runs now
 * @param lastCheck when a check last succeeded
 * @param failure why the last check, download or installation failed; empty when it succeeded or none ran
 * @param available the newest version, when it is newer than the one running
 * @param skipped the version the user asked not to be told of again
 * @param download how far the download of the available version is, while it runs
 * @param ready the package of the available version, downloaded and checked against its signed information
 * @param install when the version ready is installed
 */
public record UpdateStatus(
        String current,
        InstallKind installation,
        Installer installer,
        boolean checking,
        Optional<Instant> lastCheck,
        Optional<String> failure,
        Optional<NewVersion> available,
        Optional<String> skipped,
        Optional<Progress> download,
        Optional<Path> ready,
        Install install) {

    public UpdateStatus {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(installer, "installer");
        Objects.requireNonNull(lastCheck, "lastCheck");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(skipped, "skipped");
        Objects.requireNonNull(download, "download");
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(install, "install");
    }

    /** Who puts a new version in place. */
    public enum Installer {
        /** The user, or the system's package manager: njMule tells how. */
        NONE,
        /** njMule itself, also unattended. */
        SELF,
        /** njMule, once the system has asked the user for administrator rights: never unattended. */
        ELEVATED
    }

    /** When the version ready is installed. */
    public enum Install {
        /** When the user asks. */
        ASKED,
        /** When njMule exits, as the preferences say. */
        AT_EXIT,
        /** Now: njMule stops, installs it and starts again. */
        RESTART
    }

    /** Where the update stands, in one word. */
    public enum Phase {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        DOWNLOADING,
        READY,
        INSTALLING,
        FAILED
    }

    /**
     * How far a download is.
     *
     * @param received the bytes on disk
     * @param size the bytes in all
     */
    public record Progress(long received, long size) {
        public Progress {
            if (received < 0 || size < 0 || received > size) {
                throw new IllegalArgumentException("received " + received + " of " + size);
            }
        }

        /** The share received, from 0 to 1. */
        public double fraction() {
            return this.size == 0 ? 1 : (double) this.received / this.size;
        }
    }

    /** Whether the user should hear of the available version: one not skipped, or one that fixes a security problem. */
    public boolean isWorthTelling() {
        return this.available.isPresent()
                && (this.available.get().critical()
                        || !this.skipped.equals(Optional.of(this.available.get().version())));
    }

    /** Whether njMule can install the version ready here, by itself or once the system has asked for rights. */
    public boolean canInstall() {
        return this.ready.isPresent() && this.installer != Installer.NONE;
    }

    public Phase phase() {
        if (this.install == Install.RESTART) {
            return Phase.INSTALLING;
        }
        if (this.checking) {
            return Phase.CHECKING;
        }
        if (this.download.isPresent()) {
            return Phase.DOWNLOADING;
        }
        if (this.failure.isPresent()) {
            return Phase.FAILED;
        }
        if (this.ready.isPresent()) {
            return Phase.READY;
        }
        if (this.available.isPresent()) {
            return Phase.AVAILABLE;
        }
        return this.lastCheck.isPresent() ? Phase.UP_TO_DATE : Phase.IDLE;
    }
}
