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

import com.google.common.xml.XmlEscapers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The start at login of each system: a value of the user's Run key in the Windows registry, an XDG autostart entry on
 * Linux, a launch agent on macOS.
 */
abstract sealed class SystemAutostart implements Autostart {

    /** The id of njMule's autostart entry and launch agent. */
    static final String ID = "org.njmule.njMule";

    private static final Logger logger = LoggerFactory.getLogger(SystemAutostart.class);

    // The program first, then its arguments.
    final List<String> command;

    SystemAutostart(String program, List<String> arguments) {
        List<String> all = new ArrayList<>();
        all.add(Objects.requireNonNull(program, "program"));
        all.addAll(arguments);
        for (String part : all) {
            if (part.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("a control character in " + part);
            }
        }
        this.command = List.copyOf(all);
    }

    // The system's parts as arguments, so that tests choose them and never touch the real ones.
    static Optional<Autostart> forProgram(
            Path configDir,
            LinkAssociation.Shell shell,
            String program,
            List<String> arguments,
            String osName,
            String xdgConfigHome,
            String home) {
        String os = osName.toLowerCase(Locale.ROOT);
        // The program's own name, cut at the separators of the system it is for, whichever system this runs on.
        int cut = Math.max(program.lastIndexOf('/'), os.startsWith("windows") ? program.lastIndexOf('\\') : -1);
        if (!LinkAssociation.isDesktopProgram(Path.of(program.substring(cut + 1)))) {
            return Optional.empty();
        }
        if (os.startsWith("windows")) {
            return Optional.of(new Windows(configDir, shell, program, arguments));
        }
        if (os.startsWith("mac")) {
            return Optional.of(
                    new MacOs(Path.of(home, "Library", "LaunchAgents").resolve(ID + ".plist"), program, arguments));
        }
        if (os.startsWith("linux")) {
            Path base = xdgConfigHome == null || xdgConfigHome.isBlank()
                    ? Path.of(home, ".config")
                    : Path.of(xdgConfigHome);
            return Optional.of(new Linux(base.resolve("autostart").resolve(ID + ".desktop"), program, arguments));
        }
        return Optional.empty();
    }

    // Written whole and moved in place, so that the system never reads half a file.
    static void write(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The value {@code njMule} of the user's Run key, written as a .reg file to avoid the quoting rules of reg add. */
    static final class Windows extends SystemAutostart {
        static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
        static final String VALUE = "njMule";
        // Where the Task Manager keeps which values of the Run key are disabled.
        static final String APPROVED_KEY =
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
        private static final String KEY_PATH = "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

        private final Path configDir;
        private final LinkAssociation.Shell shell;
        private final String program;

        Windows(Path configDir, LinkAssociation.Shell shell, String program, List<String> arguments) {
            super(program, arguments);
            this.configDir = Objects.requireNonNull(configDir, "configDir");
            this.shell = Objects.requireNonNull(shell, "shell");
            this.program = program;
        }

        // Only a value naming this program counts: one left by another copy of njMule would start that one. One the
        // user disabled in the Task Manager stays, with a flag beside it.
        @Override
        public boolean isOn() throws IOException {
            return export(KEY)
                            .flatMap(Windows::stringValue)
                            .filter(value ->
                                    value.toLowerCase(Locale.ROOT).contains(this.program.toLowerCase(Locale.ROOT)))
                            .isPresent()
                    && !export(APPROVED_KEY).map(Windows::disabled).orElse(false);
        }

        @Override
        public void turnOn() throws IOException {
            Path file = Files.createTempFile(this.configDir, "autostart-", ".reg");
            try {
                // reg import takes version 5 files in UTF-16 with a byte order mark.
                Files.writeString(file, reg(), StandardCharsets.UTF_16LE);
                check(List.of("reg", "import", file.toString()));
            } finally {
                Files.deleteIfExists(file);
            }
            // Without its flag the value counts as enabled again.
            if (export(APPROVED_KEY).map(Windows::disabled).orElse(false)) {
                check(List.of("reg", "delete", APPROVED_KEY, "/v", VALUE, "/f"));
            }
            logger.info("njMule now starts when the user logs in");
        }

        @Override
        public void turnOff() throws IOException {
            if (export(KEY).flatMap(Windows::stringValue).isPresent()) {
                check(List.of("reg", "delete", KEY, "/v", VALUE, "/f"));
            }
            logger.info("njMule no longer starts when the user logs in");
        }

        /** The command line of the value. */
        String commandLine() {
            return WindowsCommandLine.of(this.command);
        }

        String reg() {
            return (char) 0xFEFF + "Windows Registry Editor Version 5.00\r\n\r\n"
                    + "[" + KEY_PATH + "]\r\n"
                    + "\"" + VALUE + "\"=" + LinkAssociation.Windows.regString(commandLine()) + "\r\n";
        }

        // reg query prints in the console's code page, which may lack letters of the program's path; an export is in
        // UTF-16. Empty when the key is missing.
        private Optional<String> export(String key) throws IOException {
            Path file = Files.createTempFile(this.configDir, "autostart-", ".reg");
            try {
                LinkAssociation.Result export = this.shell.run(List.of("reg", "export", key, file.toString(), "/y"));
                if (export.exit() != 0) {
                    return Optional.empty();
                }
                return Optional.of(Files.readString(file, StandardCharsets.UTF_16));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        // The Task Manager's flag of our value: binary, with an odd first byte while the value is disabled.
        static boolean disabled(String reg) {
            String start = "\"" + VALUE + "\"=hex:";
            for (String line : reg.split("\r?\n", -1)) {
                if (line.regionMatches(true, 0, start, 0, start.length()) && line.length() >= start.length() + 2) {
                    try {
                        return (Integer.parseInt(line.substring(start.length(), start.length() + 2), 16) & 1) != 0;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
            }
            return false;
        }

        // Our string value in an exported key, without the escapes of the format.
        static Optional<String> stringValue(String reg) {
            String start = "\"" + VALUE + "\"=\"";
            for (String line : reg.split("\r?\n", -1)) {
                if (line.regionMatches(true, 0, start, 0, start.length()) && line.endsWith("\"")) {
                    String quoted = line.substring(start.length(), line.length() - 1);
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

        private void check(List<String> command) throws IOException {
            LinkAssociation.Result result = this.shell.run(command);
            if (result.exit() != 0) {
                throw new IOException(String.join(" ", command) + " failed: "
                        + result.output().strip());
            }
        }
    }

    /** An entry of the XDG autostart directory, which desktops start at login unless it is hidden. */
    static final class Linux extends SystemAutostart {
        private final Path entry;

        Linux(Path entry, String program, List<String> arguments) {
            super(program, arguments);
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        @Override
        public boolean isOn() throws IOException {
            if (!Files.isRegularFile(this.entry)) {
                return false;
            }
            for (String line : Files.readAllLines(this.entry, StandardCharsets.UTF_8)) {
                String setting = line.strip().replace(" ", "").toLowerCase(Locale.ROOT);
                if (setting.equals("hidden=true") || setting.equals("x-gnome-autostart-enabled=false")) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void turnOn() throws IOException {
            write(this.entry, desktopEntry());
            logger.info("njMule now starts when the user logs in: {}", this.entry);
        }

        @Override
        public void turnOff() throws IOException {
            Files.deleteIfExists(this.entry);
            logger.info("njMule no longer starts when the user logs in");
        }

        String desktopEntry() {
            List<String> exec = new ArrayList<>();
            for (String part : this.command) {
                exec.add(execArgument(part));
            }
            return "[Desktop Entry]\n"
                    + "Type=Application\n"
                    + "Name=njMule\n"
                    + "Comment=eD2K and Kad file sharing\n"
                    + "Exec=" + String.join(" ", exec) + "\n"
                    + "Icon=" + ID + "\n"
                    + "Terminal=false\n"
                    + "X-GNOME-Autostart-enabled=true\n";
        }

        // The Desktop Entry quoting: reserved characters need double quotes, inside which ", `, $ and \ take a
        // backslash; % doubles, as it starts a field code; then the string value escapes each backslash again.
        static String execArgument(String part) {
            String text = part.replace("%", "%%");
            if (!text.isEmpty() && text.chars().noneMatch(c -> " \t\"'\\><~|&;$*?#()`".indexOf(c) >= 0)) {
                return text;
            }
            String quoted = "\""
                    + text.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("`", "\\`")
                            .replace("$", "\\$")
                    + "\"";
            return quoted.replace("\\", "\\\\");
        }
    }

    /** A launch agent of the user, which macOS runs at login. */
    static final class MacOs extends SystemAutostart {
        private final Path agent;

        MacOs(Path agent, String program, List<String> arguments) {
            super(program, arguments);
            this.agent = Objects.requireNonNull(agent, "agent");
        }

        @Override
        public boolean isOn() {
            return Files.isRegularFile(this.agent);
        }

        @Override
        public void turnOn() throws IOException {
            write(this.agent, plist());
            logger.info("njMule now starts when the user logs in: {}", this.agent);
        }

        @Override
        public void turnOff() throws IOException {
            Files.deleteIfExists(this.agent);
            logger.info("njMule no longer starts when the user logs in");
        }

        String plist() {
            StringBuilder arguments = new StringBuilder();
            for (String part : this.command) {
                arguments.append("        <string>").append(xml(part)).append("</string>\n");
            }
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                        <key>Label</key>
                    """
                    + "    <string>" + ID + "</string>\n"
                    + "    <key>ProgramArguments</key>\n"
                    + "    <array>\n"
                    + arguments
                    + """
                        </array>
                        <key>RunAtLoad</key>
                        <true/>
                        <key>ProcessType</key>
                        <string>Interactive</string>
                    </dict>
                    </plist>
                    """;
        }

        private static String xml(String text) {
            return XmlEscapers.xmlContentEscaper().escape(text);
        }
    }
}
