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
package org.njmule.core.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Turns file names received from the network into names that are safe on every supported system and paths that stay
 * inside their directory.
 */
public final class PathSanitizer {

    /** Longest name kept, in UTF-8 bytes (well below every file system's limit). */
    public static final int MAX_NAME_BYTES = 200;

    private static final String FORBIDDEN = "\\/:*?\"<>|";
    private static final int MAX_EXTENSION = 16;
    // Windows device names, with the superscript digits it also takes for digits.
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
            "COM7", "COM8", "COM9", "COM¹", "COM²", "COM³", "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
            "LPT7", "LPT8", "LPT9", "LPT¹", "LPT²", "LPT³");

    private PathSanitizer() {}

    /**
     * A file name without separators, control or forbidden characters, nor a reserved name; never empty, at most
     * {@value #MAX_NAME_BYTES} bytes, and its own sanitized form.
     */
    public static String fileName(String name) {
        StringBuilder out = new StringBuilder(name.length());
        name.codePoints().forEach(c -> {
            if (c < 0x20 || c == 0x7F || FORBIDDEN.indexOf(c) >= 0) {
                out.append('_');
            } else {
                out.appendCodePoint(c);
            }
        });
        String cleaned = trimEnd(truncate(trimEnd(out.toString().strip())));
        if (cleaned.isEmpty()) {
            return "_";
        }
        int dot = cleaned.indexOf('.');
        String base = (dot < 0 ? cleaned : cleaned.substring(0, dot)).toUpperCase(Locale.ROOT);
        if (RESERVED.contains(base.strip())) {
            cleaned = trimEnd(truncate("_" + cleaned));
        }
        return cleaned;
    }

    // Windows drops trailing dots and spaces, which would make two names collide.
    private static String trimEnd(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || Character.isWhitespace(name.charAt(end - 1)))) {
            end--;
        }
        return name.substring(0, end);
    }

    /** {@code directory/name}, with the name sanitized; never outside {@code directory}. */
    public static Path resolveInside(Path directory, String name) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        try {
            Path resolved = root.resolve(fileName(name)).normalize();
            if (!root.equals(resolved.getParent())) {
                throw new IOException("name escapes its directory: " + name);
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new IOException("invalid file name: " + name, e);
        }
    }

    /** A path in {@code directory} that does not exist yet: the name, else "name (2).ext", ... */
    public static Path unique(Path directory, String name) throws IOException {
        Path first = resolveInside(directory, name);
        if (!Files.exists(first)) {
            return first;
        }
        String safe = fileName(name);
        for (int n = 2; n < 10_000; n++) {
            Path candidate = resolveInside(directory, numbered(safe, n));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("no free name for " + name + " in " + directory);
    }

    /**
     * As {@link #unique}, but the empty file is created at once and atomically, so two callers never get the same name;
     * the caller replaces it.
     */
    public static Path reserve(Path directory, String name) throws IOException {
        Path first = resolveInside(directory, name);
        try {
            return Files.createFile(first);
        } catch (FileAlreadyExistsException e) {
            // Taken: the numbered names below.
        }
        String safe = fileName(name);
        for (int n = 2; n < 10_000; n++) {
            try {
                return Files.createFile(resolveInside(directory, numbered(safe, n)));
            } catch (FileAlreadyExistsException e) {
                // Taken too.
            }
        }
        throw new IOException("no free name for " + name + " in " + directory);
    }

    // "stem (n).ext" from a sanitized name, with the stem cut first so that the number always fits; the result is
    // already sanitized, so resolving it changes nothing.
    private static String numbered(String safe, int n) {
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String suffix = " (" + n + ")" + (dot > 0 ? safe.substring(dot) : "");
        if (utf8Length(suffix) > MAX_NAME_BYTES / 2) {
            // An extension this long is no extension: the number goes at the end.
            stem = safe;
            suffix = " (" + n + ")";
        }
        return cut(stem, MAX_NAME_BYTES - utf8Length(suffix)) + suffix;
    }

    // Keeps a short extension, cutting the stem on code point boundaries.
    private static String truncate(String name) {
        if (utf8Length(name) <= MAX_NAME_BYTES) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= MAX_EXTENSION ? name.substring(dot) : "";
        String stem = extension.isEmpty() ? name : name.substring(0, dot);
        return cut(stem, MAX_NAME_BYTES - utf8Length(extension)).stripTrailing() + extension;
    }

    // The longest start of text within budget UTF-8 bytes, never splitting a code point.
    private static String cut(String text, int budget) {
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            int bytes = utf8Length(Character.toString(c));
            if (used + bytes > budget) {
                break;
            }
            kept.appendCodePoint(c);
            used += bytes;
            i += Character.charCount(c);
        }
        return kept.toString();
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
