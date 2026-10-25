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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The other njMule instances the desktop app connects to, each with the token of its API, kept in a file of this
 * profile that only its owner reads; and the start of the desktop app on one of them, in a window of its own.
 */
public final class RemoteConnections {

    /** Where a token can also come from, as for the command line. */
    public static final String TOKEN_VARIABLE = "NJMULE_API_TOKEN";

    static final String FILE_NAME = "remotes.properties";

    private RemoteConnections() {}

    /** The token for the API at {@code api}: the environment's, else the one remembered. */
    public static Optional<String> token(Path guiDir, URI api, Map<String, String> environment) throws IOException {
        String given = environment.getOrDefault(TOKEN_VARIABLE, "").strip();
        if (!given.isEmpty()) {
            return Optional.of(given);
        }
        return Optional.ofNullable(load(guiDir).getProperty(api.toString()));
    }

    /** The addresses remembered, in the order of their names. */
    public static List<URI> remembered(Path guiDir) throws IOException {
        List<URI> all = new ArrayList<>();
        load(guiDir).stringPropertyNames().stream().sorted().forEach(a -> all.add(URI.create(a)));
        return all;
    }

    /** Remembers the token of the API at {@code api}. */
    public static void remember(Path guiDir, URI api, String token) throws IOException {
        Properties all = load(guiDir);
        all.setProperty(api.toString(), token);
        StringWriter text = new StringWriter();
        all.store(text, "njMule remote connections");
        Files.createDirectories(guiDir);
        Path tmp = guiDir.resolve(FILE_NAME + ".tmp");
        Files.deleteIfExists(tmp);
        // Only its owner may read the tokens, where the file system can say so.
        if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(tmp, text.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, guiDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Properties load(Path guiDir) throws IOException {
        Properties all = new Properties();
        try (Reader in = Files.newBufferedReader(guiDir.resolve(FILE_NAME), StandardCharsets.UTF_8)) {
            all.load(in);
        } catch (NoSuchFileException e) {
            // Nothing remembered yet.
        }
        return all;
    }

    /**
     * Starts the desktop app again on the API at {@code api}, the token in its environment rather than on its command
     * line; false when this is no installed njMule (a development build), which cannot start itself.
     */
    public static boolean open(URI api, String token, List<String> startArguments) throws IOException {
        Optional<String> program = ProcessHandle.current().info().command().flatMap(RemoteConnections::desktop);
        if (program.isEmpty()) {
            return false;
        }
        List<String> command = new ArrayList<>();
        command.add(program.get());
        command.addAll(startArguments);
        command.addAll(List.of("--remote-url", api.toString()));
        ProcessBuilder start = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        start.environment().put(TOKEN_VARIABLE, token);
        start.start();
        return true;
    }

    // On Windows the app runs in njmule.exe, a console program: njmule-gui.exe beside it starts it for the desktop.
    static Optional<String> desktop(String command) {
        Path running = Path.of(command);
        String name = String.valueOf(running.getFileName()).toLowerCase(Locale.ROOT);
        if (!name.startsWith("njmule")) {
            return Optional.empty();
        }
        Path launcher = running.resolveSibling("njmule-gui.exe");
        return Optional.of(name.equals("njmule.exe") && Files.isRegularFile(launcher) ? launcher.toString() : command);
    }
}
