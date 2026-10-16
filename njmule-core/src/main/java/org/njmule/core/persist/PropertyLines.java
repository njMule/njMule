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
package org.njmule.core.persist;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Properties files of numbered entries ({@code prefix.N.key}), read and written in UTF-8. */
final class PropertyLines {

    private static final Logger logger = LoggerFactory.getLogger(PropertyLines.class);
    private static final char BYTE_ORDER_MARK = '﻿';

    private PropertyLines() {}

    /** Reads {@code file}, or its backup. */
    static Properties read(Path file) throws IOException {
        return AtomicFiles.read(file, in -> {
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(r);
            }
        });
    }

    /**
     * Loads properties as {@link Properties#load(Reader)} does, but a leading byte order mark (editors write one) is
     * skipped, and a line with a malformed {@code \}{@code u} escape is left out instead of the whole file.
     */
    static Properties parse(Reader reader) throws IOException {
        StringWriter all = new StringWriter();
        reader.transferTo(all);
        String text = all.toString();
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
            return props;
        } catch (IllegalArgumentException e) {
            logger.warn("Leaving out lines with a malformed escape: {}", e.getMessage());
        }
        props = new Properties();
        for (String entry : entries(text)) {
            try {
                props.load(new StringReader(entry));
            } catch (IllegalArgumentException e) {
                // That line only.
            }
        }
        return props;
    }

    // The text split into entries: lines ending with an odd number of backslashes go on in the next one, except
    // comments.
    private static List<String> entries(String text) {
        List<String> entries = new ArrayList<>();
        StringBuilder entry = new StringBuilder();
        for (String line : text.lines().toList()) {
            boolean first = entry.isEmpty();
            entry.append(line).append('\n');
            String start = line.stripLeading();
            boolean comment = first && (start.startsWith("#") || start.startsWith("!"));
            if (comment || !continues(line)) {
                entries.add(entry.toString());
                entry.setLength(0);
            }
        }
        if (!entry.isEmpty()) {
            entries.add(entry.toString());
        }
        return entries;
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 != 0;
    }

    /** The numbers N of the {@code prefix.N.*} keys, in order; other keys are left out. */
    static SortedSet<Integer> numbers(Properties p, String prefix) {
        TreeSet<Integer> numbers = new TreeSet<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                int dot = rest.indexOf('.');
                try {
                    numbers.add(Integer.parseInt(dot < 0 ? rest : rest.substring(0, dot)));
                } catch (NumberFormatException e) {
                    // Not an entry of ours.
                }
            }
        }
        return numbers;
    }

    // Properties escaping of one entry, without the date comment store() writes.
    static void append(StringBuilder text, String key, String value) {
        Properties one = new Properties();
        one.setProperty(key, value);
        StringWriter out = new StringWriter();
        try {
            one.store(out, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        out.toString()
                .lines()
                .filter(l -> !l.startsWith("#"))
                .forEach(l -> text.append(l).append('\n'));
    }
}
