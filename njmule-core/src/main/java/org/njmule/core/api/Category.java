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

import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A group of downloads: listed apart, finished into its own incoming folder when it has one, with the priority given to
 * the downloads put in it and the file names that put new downloads in it.
 *
 * @param id 1 and up; downloads with no category have 0
 * @param incoming empty for the incoming folder of the preferences
 * @param color one of the {@link #COLORS} category colours of the interface, 0 for none
 * @param priority given to the downloads put in it; AUTO leaves theirs
 * @param patterns names that send new downloads here, apart by {@code |}: with {@code *} and {@code ?} a pattern
 *     matches the whole name, without them a part of it; when {@code regex}, one regular expression (where {@code |} is
 *     alternation) that must match the whole name, ignoring case
 */
public record Category(
        int id,
        String name,
        Optional<Path> incoming,
        String comment,
        int color,
        DownloadPriority priority,
        String patterns,
        boolean regex) {

    /** No category. */
    public static final int NONE = 0;

    public static final int COLORS = 8;
    public static final int MAX_NAME = 64;
    public static final int MAX_COMMENT = 256;
    public static final int MAX_PATTERNS = 1024;

    private static final Splitter PATTERNS = Splitter.on('|').trimResults().omitEmptyStrings();

    public Category {
        if (id < 1) {
            throw new IllegalArgumentException("a category id is 1 or more: " + id);
        }
        name = Objects.requireNonNull(name, "name").strip();
        if (name.isEmpty() || name.length() > MAX_NAME || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("a category name has 1-" + MAX_NAME + " printable characters");
        }
        Objects.requireNonNull(incoming, "incoming").ifPresent(dir -> {
            if (!dir.isAbsolute()) {
                throw new IllegalArgumentException("the incoming folder of a category must be absolute: " + dir);
            }
        });
        comment = Objects.requireNonNull(comment, "comment").strip();
        if (comment.length() > MAX_COMMENT) {
            throw new IllegalArgumentException("a category comment has at most " + MAX_COMMENT + " characters");
        }
        if (color < 0 || color > COLORS) {
            throw new IllegalArgumentException("a category colour is 0-" + COLORS + ": " + color);
        }
        Objects.requireNonNull(priority, "priority");
        patterns = Objects.requireNonNull(patterns, "patterns").strip();
        if (patterns.length() > MAX_PATTERNS) {
            throw new IllegalArgumentException("category patterns have at most " + MAX_PATTERNS + " characters");
        }
        if (regex) {
            compiled(patterns);
        }
    }

    /** Whether a new download named {@code fileName} goes in this category. */
    public boolean matches(String fileName) {
        if (this.regex) {
            return !this.patterns.isEmpty()
                    && compiled(this.patterns).matcher(fileName).matches();
        }
        for (String p : PATTERNS.split(this.patterns)) {
            if (p.indexOf('*') >= 0 || p.indexOf('?') >= 0) {
                if (glob(p).matcher(fileName).matches()) {
                    return true;
                }
            } else if (fileName.toLowerCase(Locale.ROOT).contains(p.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Pattern compiled(String patterns) {
        return Pattern.compile(patterns, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // * any characters, ? one, the rest as it is.
    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }
}
