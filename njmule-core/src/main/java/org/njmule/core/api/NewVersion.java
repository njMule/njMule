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

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A version of njMule newer than the one running, as its signed release information describes it.
 *
 * @param version its Semantic Versioning version
 * @param published when it was released
 * @param critical whether it fixes a security problem
 * @param notes what changed, in Markdown
 * @param page the page of the release
 * @param download the package for this installation; empty when none fits
 */
public record NewVersion(
        String version, Instant published, boolean critical, String notes, URI page, Optional<Download> download) {

    public NewVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(download, "download");
    }

    /**
     * A package of the release.
     *
     * @param uri where it is downloaded from
     * @param size its length in bytes
     * @param sha256 its SHA-256 hash in lower-case hexadecimal
     */
    public record Download(URI uri, long size, String sha256) {
        public Download {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(sha256, "sha256");
        }
    }
}
