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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tidies the names files travel under on the network: escapes, separators used for spaces, tags in brackets and words
 * of advertising go, and the words start with a capital. The name stays a name to edit; it is made safe for a file
 * system where it is used.
 */
public final class FileNames {

    // Removed outright: a name keeps no separator or wildcard of a file system.
    private static final String UNWANTED = "\"*<>?|\\:/";
    private static final Pattern SPACES = Pattern.compile(" {2,}");

    private FileNames() {}

    /**
     * {@code name} tidied, without the {@code words} (apart by {@code |}, in any case); the name as it was when nothing
     * would be left of it.
     */
    public static String cleanUp(String name, String words) {
        String clean = decoded(name).toLowerCase(Locale.ROOT);
        for (String word : words.toLowerCase(Locale.ROOT).split("\\|", -1)) {
            if (!word.isEmpty()) {
                clean = clean.replace(word, "");
            }
        }
        clean = dotsAsSpaces(clean);
        clean = clean.replace('_', ' ').replace('+', ' ').replace('=', ' ');
        StringBuilder kept = new StringBuilder(clean.length());
        clean.chars().filter(c -> UNWANTED.indexOf(c) < 0).forEach(c -> kept.append((char) c));
        clean = capitalized(withoutTags(kept.toString()));
        clean = SPACES.matcher(clean.replace("()", "")).replaceAll(" ");
        clean = clean.replace(" .", ".")
                .replace("( ", "(")
                .replace(" )", ")")
                .replace("()", "")
                .replace("{ ", "{")
                .replace(" }", "}")
                .replace("{}", "")
                .strip();
        return clean.isEmpty() || clean.startsWith(".") ? name : clean;
    }

    // %20 and the like as the characters they stand for; a name with a lone % stays as it is.
    private static String decoded(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    // Dots before the extension's are spaces, but for those between digits, as in a version number.
    private static String dotsAsSpaces(String name) {
        char[] chars = name.toCharArray();
        int extension = name.lastIndexOf('.');
        for (int i = 0; i < extension; i++) {
            if (chars[i] == '.'
                    && !(i > 0
                            && i < chars.length - 1
                            && Character.isDigit(chars[i - 1])
                            && Character.isDigit(chars[i + 1]))) {
                chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    // Tags in square brackets go, such as a site or a group; a bracket mostly of digits, such as a year, stays.
    private static String withoutTags(String name) {
        StringBuilder out = new StringBuilder(name);
        int from = 0;
        while (true) {
            int open = out.indexOf("[", from);
            int close = open < 0 ? -1 : out.indexOf("]", open);
            if (close < 0) {
                return out.toString();
            }
            String inside = out.substring(open + 1, close);
            long digits = inside.chars().filter(Character::isDigit).count();
            if (digits * 2 > inside.length()) {
                from = open + 1;
            } else {
                out.delete(open, close + 1);
                from = open;
            }
        }
    }

    // Each word starts with a capital, up to the extension; a letter a digit follows stays as it is, as in x264.
    private static String capitalized(String name) {
        if (name.length() < 2) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        int dot = name.lastIndexOf('.');
        int last = dot > 0 ? dot - 1 : chars.length - 1;
        for (int i = 0; i < last; i++) {
            if (!Character.isLetter(chars[i])
                    && chars[i] != '\''
                    && (i >= chars.length - 2 || !Character.isDigit(chars[i + 2]))) {
                chars[i + 1] = Character.toUpperCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }
}
