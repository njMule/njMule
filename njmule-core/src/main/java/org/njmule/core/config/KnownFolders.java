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
package org.njmule.core.config;

import com.google.common.base.Suppliers;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Downloads folder of Windows where the user moved it, read once from the user's shell folders in the registry
 * through reg.exe: Java has no API for Known Folders, and a native call would need metadata of its own in the native
 * image.
 */
final class KnownFolders {

    static final String DOWNLOADS = "{374DE290-123F-4565-9164-39C4925E467B}";

    private static final Logger logger = LoggerFactory.getLogger(KnownFolders.class);
    private static final String SHELL_FOLDERS =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders";
    private static final long WAIT_SECONDS = 5;
    private static final Pattern VARIABLE = Pattern.compile("%([^%]+)%");
    private static final Charset NATIVE =
            Charset.forName(System.getProperty("native.encoding"), StandardCharsets.UTF_8);
    private static final Supplier<Optional<String>> DOWNLOADS_VALUE = Suppliers.memoize(() -> query(DOWNLOADS));

    private KnownFolders() {}

    /**
     * The folder the user's Downloads are in, when the registry names one that exists: a name reg.exe wrote in another
     * code page than read here names none, and the default folder stays.
     */
    static Optional<Path> downloads(Map<String, String> env) {
        return DOWNLOADS_VALUE
                .get()
                .flatMap(value -> expand(value, env))
                .flatMap(KnownFolders::absolute)
                .filter(Files::isDirectory);
    }

    /** The value {@code name} in what reg.exe answers, as " name REG_EXPAND_SZ value". */
    static Optional<String> value(String answer, String name) {
        Pattern line = Pattern.compile("^\\s*" + Pattern.quote(name) + "\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
        return answer.lines()
                .map(line::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .findFirst();
    }

    /** {@code value} with its %VARIABLES% from {@code env}; empty when one is unknown. */
    static Optional<String> expand(String value, Map<String, String> env) {
        Matcher m = VARIABLE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = env.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(m.group(1)))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                return Optional.empty();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(found));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }

    private static Optional<Path> absolute(String folder) {
        try {
            Path path = Path.of(folder);
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> query(String name) {
        try {
            Process process = new ProcessBuilder(List.of("reg", "query", SHELL_FOLDERS, "/v", name))
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            String answer = new String(process.getInputStream().readAllBytes(), NATIVE);
            if (!process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroy();
                return Optional.empty();
            }
            return value(answer, name);
        } catch (IOException e) {
            logger.debug("Cannot read the shell folders: {}", e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
