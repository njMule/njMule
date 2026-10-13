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
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

/** Properties files of numbered entries ({@code prefix.N.key}), read and written in UTF-8. */
final class PropertyLines {

    private PropertyLines() {}

    /** Reads {@code file}, or its backup. */
    static Properties read(Path file) throws IOException {
        return AtomicFiles.read(file, in -> {
            Properties props = new Properties();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(r);
            }
            return props;
        });
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
