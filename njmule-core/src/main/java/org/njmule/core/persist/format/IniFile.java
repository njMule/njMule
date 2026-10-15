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
package org.njmule.core.persist.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * A read-only INI file as written by the reference client: case-insensitive sections and keys, the first occurrence of
 * a key winning, spaces around {@code =} ignored, values decoded as UTF-8 when valid and as Windows-1252 otherwise.
 * Booleans are {@code 0}/{@code 1}; the unlimited marker is stored as {@code -1}.
 */
public final class IniFile {

    /** The section of lines before the first {@code [section]}. */
    public static final String GLOBAL = "";

    private final Map<String, Section> sections;

    private record Section(String name, Map<String, String> values) {}

    private IniFile(Map<String, Section> sections) {
        this.sections = sections;
    }

    public static IniFile parse(byte[] data) {
        Map<String, Section> sections = new LinkedHashMap<>();
        Section current = new Section(GLOBAL, new LinkedHashMap<>());
        sections.put(GLOBAL, current);
        for (String raw : TextFiles.lines(data)) {
            String line = raw.strip();
            int close = sectionEnd(line);
            if (close > 1) {
                String name = line.substring(1, close).strip();
                String key = name.toLowerCase(Locale.ROOT);
                current = sections.computeIfAbsent(key, k -> new Section(name, new LinkedHashMap<>()));
                continue;
            }
            int eq = line.indexOf('=');
            if (line.length() < 2 || !Character.isLetterOrDigit(line.charAt(0)) || eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            current.values().putIfAbsent(key, line.substring(eq + 1).strip());
        }
        return new IniFile(sections);
    }

    // The ']' of a "[name]" line, which may end with a ';' comment; -1 when the line opens no section.
    private static int sectionEnd(String line) {
        if (line.isEmpty() || line.charAt(0) != '[') {
            return -1;
        }
        for (int close = line.indexOf(']'); close >= 0; close = line.indexOf(']', close + 1)) {
            String rest = line.substring(close + 1).strip();
            if (rest.isEmpty() || rest.charAt(0) == ';') {
                return close;
            }
        }
        return -1;
    }

    public List<String> sections() {
        List<String> names = new ArrayList<>();
        for (Section s : this.sections.values()) {
            if (!s.name().isEmpty()) {
                names.add(s.name());
            }
        }
        return Collections.unmodifiableList(names);
    }

    public boolean hasSection(String section) {
        return this.sections.containsKey(section.toLowerCase(Locale.ROOT));
    }

    public Optional<String> get(String section, String key) {
        Section s = this.sections.get(section.toLowerCase(Locale.ROOT));
        return s == null ? Optional.empty() : Optional.ofNullable(s.values().get(key.toLowerCase(Locale.ROOT)));
    }

    /** A decimal integer, signed; values that do not parse are absent. */
    public OptionalLong getLong(String section, String key) {
        Optional<String> value = get(section, key);
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value.get()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public Optional<Boolean> getBoolean(String section, String key) {
        OptionalLong value = getLong(section, key);
        return value.isPresent() ? Optional.of(value.getAsLong() != 0) : Optional.empty();
    }
}
