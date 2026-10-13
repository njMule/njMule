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
package org.njmule.gui.infra;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the desktop interface remembers between runs: theme, language, window and screen. Kept in
 * {@code gui.properties} of the front-end directory; unknown or broken values fall back to the
 * defaults.
 */
public final class GuiSettings {

    public static final String FILE_NAME = "gui.properties";

    /** Light, dark, or as the system is. */
    public enum Mode {
        SYSTEM,
        LIGHT,
        DARK
    }

    private static final Logger logger = LoggerFactory.getLogger(GuiSettings.class);

    private final Path file;
    private final Properties values = new Properties();
    private boolean existed;

    private GuiSettings(Path file) {
        this.file = file;
    }

    public static GuiSettings load(Path directory) {
        GuiSettings settings = new GuiSettings(directory.resolve(FILE_NAME));
        settings.existed = Files.exists(settings.file);
        if (settings.existed) {
            try (Reader in = Files.newBufferedReader(settings.file, StandardCharsets.UTF_8)) {
                settings.values.load(in);
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("Cannot read {}, using the defaults", settings.file, e);
                settings.values.clear();
            }
        }
        return settings;
    }

    /** Whether the settings were saved before: false at the first start. */
    public boolean existed() {
        return this.existed;
    }

    public Themes.Family theme() {
        return parse(Themes.Family.class, this.values.getProperty("theme"), Themes.Family.NORD);
    }

    public void setTheme(Themes.Family family) {
        this.values.setProperty("theme", family.name().toLowerCase(Locale.ROOT));
    }

    public Mode mode() {
        return parse(Mode.class, this.values.getProperty("mode"), Mode.SYSTEM);
    }

    public void setMode(Mode mode) {
        this.values.setProperty("mode", mode.name().toLowerCase(Locale.ROOT));
    }

    /** The chosen language; empty to follow the system. */
    public Optional<Locale> language() {
        String tag = this.values.getProperty("language", "").strip();
        return tag.isEmpty() ? Optional.empty() : Optional.of(Locale.forLanguageTag(tag));
    }

    public void setLanguage(Optional<Locale> language) {
        this.values.setProperty("language", language.map(Locale::toLanguageTag).orElse(""));
    }

    public String screen() {
        return this.values.getProperty("screen", "transfers");
    }

    public void setScreen(String screen) {
        this.values.setProperty("screen", Objects.requireNonNull(screen, "screen"));
    }

    /** The window bounds of the last run, if any. */
    public Optional<double[]> window() {
        double[] bounds = new double[4];
        String[] keys = {"window.x", "window.y", "window.width", "window.height"};
        for (int i = 0; i < keys.length; i++) {
            String value = this.values.getProperty(keys[i]);
            if (value == null) {
                return Optional.empty();
            }
            try {
                bounds[i] = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return bounds[2] >= 200 && bounds[3] >= 150 ? Optional.of(bounds) : Optional.empty();
    }

    public void setWindow(double x, double y, double width, double height) {
        this.values.setProperty("window.x", String.valueOf(x));
        this.values.setProperty("window.y", String.valueOf(y));
        this.values.setProperty("window.width", String.valueOf(width));
        this.values.setProperty("window.height", String.valueOf(height));
    }

    public boolean maximized() {
        return Boolean.parseBoolean(this.values.getProperty("window.maximized"));
    }

    public void setMaximized(boolean maximized) {
        this.values.setProperty("window.maximized", String.valueOf(maximized));
    }

    /** Writes the settings, sorted and replacing the old file in one step. */
    public void save() throws IOException {
        StringBuilder text = new StringBuilder("# njMule desktop settings\n");
        for (var e : new TreeMap<>(this.values).entrySet()) {
            StringWriter line = new StringWriter();
            Properties one = new Properties();
            one.setProperty(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            one.store(line, null);
            // store() writes a date comment first; keep only the entry.
            line.toString()
                    .lines()
                    .filter(l -> !l.startsWith("#"))
                    .forEach(l -> text.append(l).append('\n'));
        }
        Path dir = this.file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = this.file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(
                tmp,
                this.file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
