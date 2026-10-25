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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes the app out of the macOS disk image of a new version, with the system's own tools: {@code hdiutil} mounts the
 * image read-only and out of the Finder's sight, {@code ditto} copies the bundle with its attributes, and
 * {@code codesign} checks that its signature still holds.
 */
final class DiskImages {

    private static final Logger logger = LoggerFactory.getLogger(DiskImages.class);
    private static final Duration MOUNT_TIME = Duration.ofMinutes(2);
    private static final Duration COPY_TIME = Duration.ofMinutes(5);
    private static final String APP = "njMule.app";

    private DiskImages() {}

    /**
     * Copies njMule's app out of the disk image {@code dmg} into the empty folder {@code target}; returns the bundle.
     */
    static Path extract(Path dmg, Path target, Commands commands) throws IOException {
        Path folder = Files.createDirectories(target).toAbsolutePath();
        Path mount = Files.createTempDirectory(Objects.requireNonNullElse(folder.getParent(), folder), "mount-");
        commands.require(
                List.of(
                        "hdiutil",
                        "attach",
                        "-nobrowse",
                        "-readonly",
                        "-noautoopen",
                        "-mountpoint",
                        mount.toString(),
                        dmg.toString()),
                MOUNT_TIME);
        Path bundle;
        try {
            Path app = app(mount);
            bundle = target.resolve(String.valueOf(app.getFileName()));
            commands.require(List.of("ditto", app.toString(), bundle.toString()), COPY_TIME);
        } finally {
            Commands.Result detached =
                    commands.run(List.of("hdiutil", "detach", mount.toString(), "-force"), MOUNT_TIME);
            if (!detached.succeeded()) {
                logger.warn("Cannot detach {}: {}", mount, detached.output().strip());
            }
            Files.deleteIfExists(mount);
        }
        verify(bundle, commands);
        return bundle;
    }

    /** Fails unless the signature of {@code bundle} covers all it holds. */
    static void verify(Path bundle, Commands commands) throws IOException {
        commands.require(List.of("codesign", "--verify", "--deep", "--strict", bundle.toString()), COPY_TIME);
    }

    // The image holds the app, its uninstaller and a link to /Applications.
    private static Path app(Path mount) throws IOException {
        Path app = mount.resolve(APP);
        if (!Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the disk image holds no " + APP);
        }
        return app;
    }
}
