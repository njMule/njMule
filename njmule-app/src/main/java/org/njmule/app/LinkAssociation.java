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
package org.njmule.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes njMule the program that opens ed2k links for this user (clicked in a browser, for instance) and gives them
 * back: what handled them before, such as eMule, is saved first and restored afterwards.
 */
public abstract sealed class LinkAssociation {

    /** Runs a system command and returns its exit code and output. */
    @FunctionalInterface
    interface Shell {
        Result run(List<String> command) throws IOException;
    }

    record Result(int exit, String output) {}

    /** The id of njMule's desktop entry on Linux. */
    static final String DESKTOP_ID = "org.njmule.njMule.desktop";

    private static final Logger logger = LoggerFactory.getLogger(LinkAssociation.class);

    final Path configDir;
    final Shell shell;

    LinkAssociation(Path configDir, Shell shell) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.shell = Objects.requireNonNull(shell, "shell");
    }

    /**
     * The association for this system, for the installed desktop program; empty where it cannot be made: on macOS,
     * where links come as system events, and when running from a development build.
     */
    public static Optional<LinkAssociation> forThisSystem(Path configDir) {
        Optional<Path> program =
                ProcessHandle.current().info().command().map(Path::of).filter(LinkAssociation::isDesktopProgram);
        if (program.isEmpty()) {
            return Optional.empty();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return Optional.of(new Windows(configDir, LinkAssociation::run, program.get()));
        }
        if (os.startsWith("linux")) {
            String config = System.getenv("XDG_CONFIG_HOME");
            Path base = config == null || config.isBlank()
                    ? Path.of(System.getProperty("user.home"), ".config")
                    : Path.of(config);
            return Optional.of(new Linux(configDir, LinkAssociation::run, base.resolve("mimeapps.list")));
        }
        return Optional.empty();
    }

    /** Whether ed2k links open in njMule now. */
    public abstract boolean isOurs() throws IOException;

    /** Makes njMule open ed2k links, saving what opened them before. */
    public abstract void take() throws IOException;

    /** Gives ed2k links back to what opened them before njMule took them, or to nothing. */
    public abstract void giveBack() throws IOException;

    static boolean isDesktopProgram(Path command) {
        String name = String.valueOf(command.getFileName()).toLowerCase(Locale.ROOT);
        return name.equals("njmule-gui.exe") || name.equals("njmule-gui");
    }

    static Result run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command.getFirst() + " did not finish");
            }
            return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while running " + command.getFirst(), e);
        }
    }

    Result check(List<String> command) throws IOException {
        Result result = this.shell.run(command);
        if (result.exit() != 0) {
            throw new IOException(
                    String.join(" ", command) + " failed: " + result.output().strip());
        }
        return result;
    }

    /** The user's ed2k key in the registry, exported before njMule writes its own and imported back. */
    static final class Windows extends LinkAssociation {
        static final String KEY = "HKCU\\Software\\Classes\\ed2k";
        static final String COMMAND_KEY = KEY + "\\shell\\open\\command";
        static final String BACKUP = "ed2k-handler-backup.reg";
        private static final String KEY_PATH = "HKEY_CURRENT_USER\\Software\\Classes\\ed2k";

        private final Path program;

        Windows(Path configDir, Shell shell, Path program) {
            super(configDir, shell);
            this.program = Objects.requireNonNull(program, "program");
        }

        @Override
        public boolean isOurs() throws IOException {
            // reg query prints in the console's code page, which may lack letters of the program's path; an export is
            // in UTF-16.
            Path file = Files.createTempFile(this.configDir, "ed2k-", ".reg");
            try {
                Result export = this.shell.run(List.of("reg", "export", COMMAND_KEY, file.toString(), "/y"));
                if (export.exit() != 0) {
                    return false;
                }
                String ours = this.command().toLowerCase(Locale.ROOT);
                return defaultValue(Files.readString(file, StandardCharsets.UTF_16))
                        .filter(value -> value.toLowerCase(Locale.ROOT).contains(ours))
                        .isPresent();
            } finally {
                Files.deleteIfExists(file);
            }
        }

        // The default value of an exported key when it is a string, without the escapes of the format.
        static Optional<String> defaultValue(String reg) {
            for (String line : reg.split("\r?\n", -1)) {
                if (line.length() >= 4 && line.startsWith("@=\"") && line.endsWith("\"")) {
                    String quoted = line.substring(3, line.length() - 1);
                    StringBuilder value = new StringBuilder(quoted.length());
                    for (int i = 0; i < quoted.length(); i++) {
                        char c = quoted.charAt(i);
                        value.append(c == '\\' && i + 1 < quoted.length() ? quoted.charAt(++i) : c);
                    }
                    return Optional.of(value.toString());
                }
            }
            return Optional.empty();
        }

        @Override
        public void take() throws IOException {
            Path backup = this.configDir.resolve(BACKUP);
            if (!this.isOurs() && this.shell.run(List.of("reg", "query", KEY)).exit() == 0) {
                this.check(List.of("reg", "export", KEY, backup.toString(), "/y"));
            }
            this.importReg(this.ours());
            logger.info("ed2k links now open in njMule");
        }

        @Override
        public void giveBack() throws IOException {
            if (!this.isOurs()) {
                return;
            }
            this.importReg("[-" + KEY_PATH + "]\r\n");
            Path backup = this.configDir.resolve(BACKUP);
            if (Files.exists(backup)) {
                this.check(List.of("reg", "import", backup.toString()));
                Files.delete(backup);
            }
            logger.info("ed2k links given back");
        }

        String command() {
            return "\"" + this.program + "\" \"%1\"";
        }

        // A .reg file avoids the quoting rules of reg add for values with quotes in them.
        String ours() {
            return "[" + KEY_PATH + "]\r\n"
                    + "@=\"URL:ed2k Protocol\"\r\n"
                    + "\"URL Protocol\"=\"\"\r\n\r\n"
                    + "[" + KEY_PATH + "\\DefaultIcon]\r\n"
                    + "@=" + regString("\"" + this.program + "\",0") + "\r\n\r\n"
                    + "[" + KEY_PATH + "\\shell\\open\\command]\r\n"
                    + "@=" + regString(this.command()) + "\r\n";
        }

        private void importReg(String body) throws IOException {
            Path file = Files.createTempFile(this.configDir, "ed2k-", ".reg");
            try {
                // reg import takes version 5 files in UTF-16 with a byte order mark.
                Files.writeString(
                        file, "\uFEFFWindows Registry Editor Version 5.00\r\n\r\n" + body, StandardCharsets.UTF_16LE);
                this.check(List.of("reg", "import", file.toString()));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        static String regString(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    /** The user's default handler of the ed2k scheme in the desktop's MIME associations. */
    static final class Linux extends LinkAssociation {
        static final String SCHEME = "x-scheme-handler/ed2k";
        static final String PREVIOUS = "ed2k-handler-previous";

        private final Path mimeApps;

        Linux(Path configDir, Shell shell, Path mimeApps) {
            super(configDir, shell);
            this.mimeApps = Objects.requireNonNull(mimeApps, "mimeApps");
        }

        @Override
        public boolean isOurs() throws IOException {
            return DESKTOP_ID.equals(this.current());
        }

        @Override
        public void take() throws IOException {
            String before = this.current();
            if (!DESKTOP_ID.equals(before)) {
                Files.writeString(this.configDir.resolve(PREVIOUS), before + "\n", StandardCharsets.UTF_8);
            }
            this.check(List.of("xdg-mime", "default", DESKTOP_ID, SCHEME));
            logger.info("ed2k links now open in njMule");
        }

        @Override
        public void giveBack() throws IOException {
            if (!this.isOurs()) {
                return;
            }
            Path previous = this.configDir.resolve(PREVIOUS);
            String before = Files.exists(previous)
                    ? Files.readString(previous, StandardCharsets.UTF_8).strip()
                    : "";
            if (before.endsWith(".desktop")) {
                this.check(List.of("xdg-mime", "default", before, SCHEME));
            } else {
                this.removeOurDefault();
            }
            Files.deleteIfExists(previous);
            logger.info("ed2k links given back");
        }

        // xdg-mime cannot unset a default: our id goes from the user's lists, and a list left empty goes too.
        private void removeOurDefault() throws IOException {
            if (!Files.exists(this.mimeApps)) {
                return;
            }
            List<String> lines = Files.readAllLines(this.mimeApps, StandardCharsets.UTF_8);
            List<String> kept = new ArrayList<>(lines.size());
            for (String line : lines) {
                String entry = line.strip();
                if (!entry.startsWith(SCHEME + "=")) {
                    kept.add(line);
                    continue;
                }
                List<String> ids = Arrays.stream(
                                entry.substring(SCHEME.length() + 1).split(";", -1))
                        .map(String::strip)
                        .filter(id -> !id.isEmpty())
                        .toList();
                List<String> others =
                        ids.stream().filter(id -> !id.equals(DESKTOP_ID)).toList();
                if (others.size() == ids.size()) {
                    kept.add(line);
                } else if (!others.isEmpty()) {
                    kept.add(SCHEME + "=" + String.join(";", others) + ";");
                }
            }
            if (!kept.equals(lines)) {
                // A list linked from elsewhere, such as a dotfiles repository, stays a link: its target changes.
                Path target = this.mimeApps.toRealPath();
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, String.join("\n", kept) + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        }

        private String current() throws IOException {
            Result query = this.shell.run(List.of("xdg-mime", "query", "default", SCHEME));
            return query.exit() == 0 ? query.output().strip() : "";
        }
    }
}
