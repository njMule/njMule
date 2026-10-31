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
package org.njmule.webui;

import com.google.common.base.CharMatcher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The browser app built by njmule-webapp, shipped in this module's resources: its page, whose session token is filled
 * in for each session, and its files, read once and kept.
 */
final class WebApp {

    private static final String ROOT = "app/";
    private static final String TOKEN = "__NJMULE_CSRF__";
    private static final String ROLE = "__NJMULE_ROLE__";
    // The build ships the text files compressed only.
    private static final String GZIP = ".gz";

    // Only the names Vite and the translations give files: nothing else of the resources can be asked for.
    private static final String ASSETS = "/assets/";
    private static final CharMatcher NAME = CharMatcher.inRange('a', 'z')
            .or(CharMatcher.inRange('A', 'Z'))
            .or(CharMatcher.inRange('0', '9'))
            .or(CharMatcher.anyOf("._-"));
    private static final Set<String> EXTENSIONS = Set.of("js", "css", "svg", "woff2", "ttf");
    private static final Pattern TRANSLATION = Pattern.compile("/i18n/[A-Za-z-]{2,10}\\.json");
    // The flags of the desktop app, by country code.
    private static final Pattern FLAG = Pattern.compile("/flags/[a-z]{2}\\.png");

    /** A file of the app; the bundled files have their content's hash in their names and never change. */
    static final class File {
        private final byte[] bytes;
        private final String type;
        private final boolean immutable;
        private final boolean gzipped;

        File(byte[] bytes, String type, boolean immutable, boolean gzipped) {
            this.bytes = bytes;
            this.type = type;
            this.immutable = immutable;
            this.gzipped = gzipped;
        }

        /** The content as it is kept: compressed with gzip when {@link #gzipped()}. */
        byte[] bytes() {
            return this.bytes.clone();
        }

        boolean gzipped() {
            return this.gzipped;
        }

        /** The content itself, for a client that does not take gzip. */
        byte[] unpacked() throws IOException {
            if (!this.gzipped) {
                return bytes();
            }
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(this.bytes))) {
                return in.readAllBytes();
            }
        }

        String type() {
            return this.type;
        }

        boolean immutable() {
            return this.immutable;
        }
    }

    private final ConcurrentMap<String, Optional<File>> files = new ConcurrentHashMap<>();
    private final String page;

    WebApp() {
        this.page = new String(read("index.html").orElse(new byte[0]), StandardCharsets.UTF_8);
    }

    /** The app's page for a session with {@code csrf}, a token of URL-safe characters, and for a guest or not. */
    String page(String csrf, boolean guest) {
        return this.page.replace(TOKEN, Html.text(csrf)).replace(ROLE, guest ? "guest" : "admin");
    }

    Optional<File> file(String path) {
        if (!servable(path)) {
            return Optional.empty();
        }
        return this.files.computeIfAbsent(path, WebApp::load);
    }

    private static Optional<File> load(String path) {
        String name = path.substring(1);
        Optional<File> packed = read(name + GZIP).map(b -> new File(b, type(path), isHashed(path), true));
        return packed.isPresent() ? packed : read(name).map(b -> new File(b, type(path), isHashed(path), false));
    }

    static boolean servable(String path) {
        if (path.startsWith(ASSETS)) {
            String name = path.substring(ASSETS.length());
            int dot = name.lastIndexOf('.');
            return dot > 0
                    && NAME.matchesAllOf(name)
                    && !name.contains("..")
                    && EXTENSIONS.contains(name.substring(dot + 1));
        }
        return path.equals("/THIRD-PARTY.md")
                || TRANSLATION.matcher(path).matches()
                || FLAG.matcher(path).matches();
    }

    private static boolean isHashed(String path) {
        return path.startsWith(ASSETS);
    }

    private static String type(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1);
        return switch (extension) {
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "png" -> "image/png";
            case "json" -> "application/json; charset=utf-8";
            default -> "text/markdown; charset=utf-8";
        };
    }

    private static Optional<byte[]> read(String name) {
        try (InputStream in = WebApp.class.getResourceAsStream(ROOT + name)) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
