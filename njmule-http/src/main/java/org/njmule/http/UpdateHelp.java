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
package org.njmule.http;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.njmule.core.api.InstallKind;
import org.njmule.core.api.NjMuleInfo;

/** How a new version takes the place of this one, by the way njMule was installed; for the web and the console. */
public final class UpdateHelp {

    private UpdateHelp() {}

    /**
     * One sentence telling how to install a new version.
     *
     * @param download its package for this installation, if one fits
     * @param page the page of its release
     */
    public static String howToInstall(InstallKind installation, Optional<URI> download, Optional<URI> page) {
        String file = download.map(UpdateHelp::fileName).orElse("");
        return switch (installation) {
            case DEB ->
                "Download " + download.map(URI::toString).orElse("the .deb package") + " and install it: sudo apt"
                        + " install ./" + file;
            case RPM ->
                "Download " + download.map(URI::toString).orElse("the .rpm package") + " and install it: sudo dnf"
                        + " install ./" + file;
            case SYNOLOGY ->
                "Install it in the Package Center with Manual Install: "
                        + download.map(URI::toString).orElse("the .spk package of the release");
            case DOCKER -> "Pull the new image and start it again: docker compose pull && docker compose up -d";
            default ->
                "Download and install "
                        + download.or(() -> page).map(URI::toString).orElse(NjMuleInfo.HOMEPAGE);
        };
    }

    /** One sentence telling how to install the package of a new version downloaded to {@code file}. */
    public static String howToInstallFile(InstallKind installation, Path file) {
        String quoted = "\"" + file + "\"";
        return switch (installation) {
            case DEB -> "Install it: sudo apt install " + quoted;
            case RPM -> "Install it: sudo dnf install " + quoted;
            case SYNOLOGY -> "Install it in the Package Center with Manual Install: " + file;
            case DMG -> "Open it and drag " + NjMuleInfo.NAME + " to Applications, replacing this one: " + file;
            default -> "Install it as this copy was installed: " + file;
        };
    }

    private static String fileName(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
