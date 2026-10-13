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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.njmule.core.protocol.io.Ed2kStrings;

/**
 * Line-based files: {@code staticservers.dat} ({@code host:port,priority,name}), {@code shareddir.dat} and
 * {@code sharedfiles.dat} (one path per line, {@code -} excluding a file), {@code addresses.dat} (server list URLs) and
 * the {@code AC_*.dat} histories. They are UTF-16LE with a BOM when written; ANSI and UTF-8 files are read too.
 */
public final class TextFiles {

    /** Server priorities as stored: normal 0, high 1, low 2. */
    public static final int PRIORITY_NORMAL = 0;

    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_LOW = 2;

    public record StaticServer(String host, int port, int priority, String name) {
        public StaticServer {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(name, "name");
        }
    }

    /** A line of {@code sharedfiles.dat}: a file shared explicitly, or excluded ({@code -}). */
    public record SharedFile(String path, boolean excluded) {
        public SharedFile {
            Objects.requireNonNull(path, "path");
        }
    }

    private TextFiles() {}

    /** The lines of a text file, without line ends, decoding a UTF-16LE or UTF-8 BOM. */
    public static List<String> lines(byte[] data) {
        String text;
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            text = new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        } else {
            text = Ed2kStrings.decode(data);
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n|\n|\r", -1)) {
            lines.add(line);
        }
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    /** UTF-16LE with a BOM and CRLF line ends. */
    public static byte[] writeLines(List<String> lines) {
        StringBuilder text = new StringBuilder("﻿");
        for (String line : lines) {
            text.append(line).append("\r\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_16LE);
    }

    /** Parses {@code host:port,priority,name}; comment lines start with {@code #} or {@code /}. */
    public static List<StaticServer> staticServers(byte[] data) {
        List<StaticServer> servers = new ArrayList<>();
        for (String raw : lines(data)) {
            String line = raw;
            if (line.length() < 5 || line.charAt(0) == '#' || line.charAt(0) == '/') {
                continue;
            }
            int colon = line.indexOf(':');
            int hostEnd = colon >= 0 ? colon : line.indexOf(',');
            if (hostEnd < 0) {
                continue;
            }
            String host = line.substring(0, hostEnd).strip();
            line = line.substring(hostEnd + 1);
            int comma = line.indexOf(',');
            if (comma < 0) {
                continue;
            }
            int port = leadingInt(line.substring(0, comma));
            line = line.substring(comma + 1);
            int priority = PRIORITY_HIGH;
            comma = line.indexOf(',');
            if (comma == 1) {
                int p = leadingInt(line.substring(0, 1));
                priority = p >= PRIORITY_NORMAL && p <= PRIORITY_LOW ? p : PRIORITY_HIGH;
                line = line.substring(2);
            }
            if (host.isEmpty() || port < 1 || port > 0xFFFF) {
                continue;
            }
            servers.add(new StaticServer(host, port, priority, line));
        }
        return servers;
    }

    public static byte[] writeStaticServers(List<StaticServer> servers) {
        List<String> lines = new ArrayList<>(servers.size());
        for (StaticServer s : servers) {
            lines.add(s.host() + ":" + s.port() + "," + s.priority() + "," + s.name());
        }
        return writeLines(lines);
    }

    public static List<SharedFile> sharedFiles(byte[] data) {
        List<SharedFile> files = new ArrayList<>();
        for (String line : lines(data)) {
            String path = line.strip();
            if (path.startsWith("-")) {
                files.add(new SharedFile(path.substring(1), true));
            } else if (!path.isEmpty()) {
                files.add(new SharedFile(path, false));
            }
        }
        return files;
    }

    /** Non-empty lines, stripped: shared directories, server list URLs, histories. */
    public static List<String> entries(byte[] data) {
        List<String> entries = new ArrayList<>();
        for (String line : lines(data)) {
            String entry = line.strip();
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static int leadingInt(String text) {
        String s = text.strip();
        int end = 0;
        while (end < s.length() && end < 9 && s.charAt(end) >= '0' && s.charAt(end) <= '9') {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
    }
}
