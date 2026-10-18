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
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.persist.AtomicFiles;
import org.semver4j.Semver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What njMule remembers of its updates between runs, in {@value #FILE_NAME}: when a check last succeeded, the highest
 * version each channel offered (an older one offered later is a replay), the version the user skipped, the version that
 * last ran on this configuration and one whose installation failed, which is not tried again on its own.
 *
 * @param lastCheck when a check last succeeded
 * @param highest the highest version each channel offered
 * @param skipped the version the user asked not to be told of again
 * @param ran the version that last started on this configuration
 * @param failedInstall the version whose installation failed
 */
record UpdateState(
        Optional<Instant> lastCheck,
        Map<UpdateChannel, Semver> highest,
        Optional<String> skipped,
        Optional<String> ran,
        Optional<String> failedInstall) {

    static final String FILE_NAME = "update.properties";
    static final UpdateState NONE =
            new UpdateState(Optional.empty(), Map.of(), Optional.empty(), Optional.empty(), Optional.empty());

    private static final Logger logger = LoggerFactory.getLogger(UpdateState.class);
    private static final String LAST_CHECK_KEY = "lastCheck";
    private static final String HIGHEST_KEY = "highest.";
    private static final String SKIPPED_KEY = "skipped";
    private static final String RAN_KEY = "ran";
    private static final String FAILED_KEY = "failedInstall";

    UpdateState {
        Objects.requireNonNull(lastCheck, "lastCheck");
        highest = Map.copyOf(highest);
        Objects.requireNonNull(skipped, "skipped");
        Objects.requireNonNull(ran, "ran");
        Objects.requireNonNull(failedInstall, "failedInstall");
    }

    UpdateState checked(Instant when, UpdateChannel channel, Semver offered) {
        Map<UpdateChannel, Semver> next = new EnumMap<>(UpdateChannel.class);
        next.putAll(this.highest);
        next.merge(channel, offered, (was, now) -> now.isGreaterThan(was) ? now : was);
        return new UpdateState(Optional.of(when), next, this.skipped, this.ran, this.failedInstall);
    }

    UpdateState skipping(String version) {
        return new UpdateState(this.lastCheck, this.highest, Optional.of(version), this.ran, this.failedInstall);
    }

    UpdateState running(String version) {
        return new UpdateState(this.lastCheck, this.highest, this.skipped, Optional.of(version), this.failedInstall);
    }

    UpdateState failing(Optional<String> version) {
        return new UpdateState(this.lastCheck, this.highest, this.skipped, this.ran, version);
    }

    /** The state kept in {@code file}; none when it is missing, and what can be read of it when it is damaged. */
    static UpdateState load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return NONE;
        } catch (IOException e) {
            logger.warn("Cannot read {}: {}", file, e.toString());
            return NONE;
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Cannot read {}: {}", file, e.toString());
            return NONE;
        }
        Optional<Instant> lastCheck = Optional.empty();
        try {
            lastCheck =
                    Optional.ofNullable(properties.getProperty(LAST_CHECK_KEY)).map(Instant::parse);
        } catch (DateTimeParseException e) {
            logger.debug("Ignoring the last check in {}: {}", file, e.toString());
        }
        Map<UpdateChannel, Semver> highest = new EnumMap<>(UpdateChannel.class);
        for (UpdateChannel channel : UpdateChannel.values()) {
            Semver version = Semver.parse(properties.getProperty(key(channel), ""));
            if (version != null) {
                highest.put(channel, version);
            }
        }
        return new UpdateState(
                lastCheck,
                highest,
                version(properties, SKIPPED_KEY),
                version(properties, RAN_KEY),
                version(properties, FAILED_KEY));
    }

    private static Optional<String> version(Properties properties, String key) {
        return Optional.ofNullable(properties.getProperty(key))
                .map(String::strip)
                .filter(v -> Semver.parse(v) != null);
    }

    /** Keeps the state in {@code file}, replacing it whole. */
    void save(Path file) throws IOException {
        StringBuilder text = new StringBuilder();
        this.lastCheck.ifPresent(
                t -> text.append(LAST_CHECK_KEY).append('=').append(t).append('\n'));
        for (UpdateChannel channel : UpdateChannel.values()) {
            Semver version = this.highest.get(channel);
            if (version != null) {
                text.append(key(channel))
                        .append('=')
                        .append(version.getVersion())
                        .append('\n');
            }
        }
        this.skipped.ifPresent(
                v -> text.append(SKIPPED_KEY).append('=').append(v).append('\n'));
        this.ran.ifPresent(v -> text.append(RAN_KEY).append('=').append(v).append('\n'));
        this.failedInstall.ifPresent(
                v -> text.append(FAILED_KEY).append('=').append(v).append('\n'));
        AtomicFiles.writeBytes(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String key(UpdateChannel channel) {
        return HIGHEST_KEY + channel.name().toLowerCase(Locale.ROOT);
    }
}
