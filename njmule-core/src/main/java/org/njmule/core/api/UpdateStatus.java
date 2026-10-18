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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What njMule knows of its new versions.
 *
 * @param current the version running
 * @param installation how this copy was installed, which tells how to update it
 * @param checking whether a check runs now
 * @param lastCheck when a check last succeeded
 * @param failure why the last check failed; empty when it succeeded or none ran
 * @param available the newest version, when it is newer than the one running
 * @param skipped the version the user asked not to be told of again
 */
public record UpdateStatus(
        String current,
        InstallKind installation,
        boolean checking,
        Optional<Instant> lastCheck,
        Optional<String> failure,
        Optional<NewVersion> available,
        Optional<String> skipped) {

    public UpdateStatus {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(lastCheck, "lastCheck");
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(available, "available");
        Objects.requireNonNull(skipped, "skipped");
    }

    /** Whether the user should hear of the available version: one not skipped, or one that fixes a security problem. */
    public boolean isWorthTelling() {
        return this.available.isPresent()
                && (this.available.get().critical()
                        || !this.skipped.equals(Optional.of(this.available.get().version())));
    }
}
