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

import java.util.Arrays;
import java.util.Optional;

/** How this copy of njMule was installed, which tells how it is updated. */
public enum InstallKind {
    /** The Windows installer, also as winget installs it. */
    MSI("msi"),
    /** The Windows zip archive. */
    WINDOWS_ZIP("zip"),
    /** The macOS disk image. */
    DMG("dmg"),
    /** The Debian package, which the system's package manager updates. */
    DEB("deb"),
    /** The RPM package, which the system's package manager updates. */
    RPM("rpm"),
    /** The Linux archive of the desktop app. */
    TAR("tar"),
    /** The Linux archive of the engine alone, built static. */
    STATIC_TAR("static-tar"),
    /** The Synology DSM package, which the Package Center updates. */
    SYNOLOGY("spk"),
    /** The Docker image, which its administrator pulls again. */
    DOCKER("docker"),
    /** Built from the sources, or installed some other way. */
    UNKNOWN("unknown");

    private final String id;

    InstallKind(String id) {
        this.id = id;
    }

    /** The name packages give themselves and release assets go by, as "msi". */
    public String id() {
        return this.id;
    }

    /** The kind named {@code id}, if any. */
    public static Optional<InstallKind> of(String id) {
        return Arrays.stream(values()).filter(k -> k.id.equals(id)).findFirst();
    }
}
