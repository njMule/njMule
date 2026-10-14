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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the desktop interface remembers between runs: theme, language, window and screen. Kept in {@code gui.properties}
 * of the front-end directory; unknown or broken values fall back to the defaults.
 */
public final class GuiSettings {

    public static final String FILE_NAME = "gui.properties";

    /** Entries kept in each list of things used before, such as addresses. */
    public static final int MAX_HISTORY = 10;

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
        // it_IT, as Java writes a locale, is the tag it-IT.
        String tag = this.values.getProperty("language", "").strip().replace('_', '-');
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
            if (!Double.isFinite(bounds[i])) {
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

    /** Whether closing asks first; on by default. */
    public boolean confirmExit() {
        return Boolean.parseBoolean(this.values.getProperty("window.confirmExit", "true"));
    }

    public void setConfirmExit(boolean value) {
        this.values.setProperty("window.confirmExit", String.valueOf(value));
    }

    /** Whether njMule starts minimised, in the notification area when it hides there. */
    public boolean startMinimized() {
        return Boolean.parseBoolean(this.values.getProperty("window.startMinimized", "false"));
    }

    public void setStartMinimized(boolean value) {
        this.values.setProperty("window.startMinimized", String.valueOf(value));
    }

    /** How the columns of the table {@code name} were left: see {@code TableLayout}. */
    public String tableColumns(String name) {
        return this.values.getProperty("table." + name + ".columns", "");
    }

    public void setTableColumns(String name, String layout) {
        this.values.setProperty("table." + name + ".columns", layout);
    }

    public String tableSort(String name) {
        return this.values.getProperty("table." + name + ".sort", "");
    }

    public void setTableSort(String name, String sort) {
        this.values.setProperty("table." + name + ".sort", sort);
    }

    /** Whether eD2K file links copied in other programs are downloaded; off by default. */
    public boolean watchClipboard() {
        return Boolean.parseBoolean(this.values.getProperty("links.watchClipboard", "false"));
    }

    public void setWatchClipboard(boolean value) {
        this.values.setProperty("links.watchClipboard", String.valueOf(value));
    }

    /** Whether a minimised window hides into the notification area; on unless turned off. */
    public boolean minimizeToTray() {
        return Boolean.parseBoolean(this.values.getProperty("window.minimizeToTray", "true"));
    }

    public void setMinimizeToTray(boolean value) {
        this.values.setProperty("window.minimizeToTray", String.valueOf(value));
    }

    /** The entries of the list {@code name}, the latest first. */
    public List<String> history(String name) {
        List<String> entries = new ArrayList<>();
        for (int i = 1; i <= MAX_HISTORY; i++) {
            String entry = this.values.getProperty("history." + name + "." + i);
            if (entry != null && !entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** Starts the history {@code name}, when it is empty, with eMule's box history in {@code file}, if any. */
    public void seedHistory(String name, Path file) {
        if (!this.history(name).isEmpty() || !Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> entries = emuleHistory(Files.readAllBytes(file));
            for (String entry : entries.reversed()) {
                this.remember(name, entry);
            }
            if (!entries.isEmpty()) {
                this.save();
            }
        } catch (IOException e) {
            logger.debug("Cannot read {}: {}", file, e.toString());
        }
    }

    /** The lines of an eMule box history: UTF-16LE after a byte order mark, newest first. */
    public static List<String> emuleHistory(byte[] data) {
        if (data.length < 2 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xFE) {
            return List.of();
        }
        return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE)
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .distinct()
                .limit(MAX_HISTORY)
                .toList();
    }

    /** Puts {@code entry} at the top of the list {@code name}, once, keeping the latest {@value #MAX_HISTORY}. */
    public void remember(String name, String entry) {
        List<String> entries = new ArrayList<>(this.history(name));
        entries.remove(entry);
        entries.addFirst(entry);
        for (int i = 1; i <= MAX_HISTORY; i++) {
            String key = "history." + name + "." + i;
            if (i <= entries.size()) {
                this.values.setProperty(key, entries.get(i - 1));
            } else {
                this.values.remove(key);
            }
        }
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
        Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
