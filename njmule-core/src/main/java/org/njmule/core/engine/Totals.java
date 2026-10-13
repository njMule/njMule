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
package org.njmule.core.engine;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.njmule.core.persist.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The all-time totals: bytes received and sent, sessions and time running, kept in {@code
 * statistics.properties}. The current session is added when the totals are saved.
 */
final class Totals {

    static final String FILE_NAME = "statistics.properties";

    private static final Logger logger = LoggerFactory.getLogger(Totals.class);

    private final Path file;
    private final long downloaded;
    private final long uploaded;
    private final long sessions;
    private final long seconds;

    private Totals(Path file, long downloaded, long uploaded, long sessions, long seconds) {
        this.file = file;
        this.downloaded = downloaded;
        this.uploaded = uploaded;
        this.sessions = sessions;
        this.seconds = seconds;
    }

    /** The totals before this session; a broken file counts as none. */
    static Totals load(Path file) {
        if (Files.exists(file) || Files.exists(AtomicFiles.bakPath(file.toAbsolutePath()))) {
            try {
                Properties p =
                        AtomicFiles.read(
                                file,
                                in -> {
                                    Properties props = new Properties();
                                    try (Reader r =
                                            new InputStreamReader(in, StandardCharsets.UTF_8)) {
                                        props.load(r);
                                    }
                                    return props;
                                });
                return new Totals(
                        file,
                        number(p, "downloaded"),
                        number(p, "uploaded"),
                        number(p, "sessions"),
                        number(p, "seconds"));
            } catch (IOException e) {
                logger.warn("Cannot read {}, the totals start again", file, e);
            }
        }
        return new Totals(file, 0, 0, 0, 0);
    }

    long downloaded() {
        return this.downloaded;
    }

    long uploaded() {
        return this.uploaded;
    }

    /** Sessions before this one. */
    long sessions() {
        return this.sessions;
    }

    /** Seconds run before this session. */
    long seconds() {
        return this.seconds;
    }

    /** Saves the totals with this session's bytes and time. */
    void save(long sessionDownloaded, long sessionUploaded, long sessionSeconds) {
        String text =
                "# njMule all-time statistics\n"
                        + "downloaded="
                        + (this.downloaded + sessionDownloaded)
                        + "\n"
                        + "uploaded="
                        + (this.uploaded + sessionUploaded)
                        + "\n"
                        + "sessions="
                        + (this.sessions + 1)
                        + "\n"
                        + "seconds="
                        + (this.seconds + sessionSeconds)
                        + "\n";
        try {
            AtomicFiles.writeBytes(this.file, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Cannot save {}", this.file, e);
        }
    }

    private static long number(Properties p, String key) {
        try {
            return Math.max(0, Long.parseLong(p.getProperty(key, "0").strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
