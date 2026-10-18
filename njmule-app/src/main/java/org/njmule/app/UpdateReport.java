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
package org.njmule.app;

import java.io.PrintStream;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.webui.UpdateHelp;

/**
 * What the command line says of new versions, from the engine's status or from a running njMule's API.
 *
 * @param current the version running
 * @param installation how it was installed
 * @param checked whether a check ever succeeded
 * @param failure why the last check failed
 * @param available the newer version, if any
 * @param page the page of its release
 * @param download its package for this installation
 */
record UpdateReport(
        String current,
        InstallKind installation,
        boolean checked,
        Optional<String> failure,
        Optional<String> available,
        Optional<URI> page,
        Optional<URI> download) {

    UpdateReport {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(installation, "installation");
    }

    static UpdateReport of(UpdateStatus status) {
        Optional<NewVersion> v = status.available();
        return new UpdateReport(
                status.current(),
                status.installation(),
                status.lastCheck().isPresent(),
                status.failure(),
                v.map(NewVersion::version),
                v.map(NewVersion::page),
                v.flatMap(NewVersion::download).map(NewVersion.Download::uri));
    }

    /** Whether a newer version is out. */
    boolean isAvailable() {
        return this.available.isPresent();
    }

    /** Prints the outcome: the new version and how to install it, that this one is the newest, or the failure. */
    void print(PrintStream out, PrintStream err) {
        if (this.available.isPresent()) {
            out.println(NjMuleInfo.NAME + " " + this.available.get() + " is available (this is " + this.current + ").");
            this.page.ifPresent(p -> out.println("What changed: " + p));
            out.println(UpdateHelp.howToInstall(this.installation, this.download, this.page));
        } else if (this.failure.isPresent()) {
            err.println("Cannot check for a new version: " + this.failure.get());
        } else if (!this.checked) {
            out.println(NjMuleInfo.NAME + " " + this.current + " has not looked for a new version yet.");
        } else {
            out.println(NjMuleInfo.NAME + " " + this.current + " is the newest version.");
        }
    }
}
