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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commands the association runs, against a pretend system: nothing here touches the real one. */
class LinkAssociationTest {

    private static final Path PROGRAM = Path.of("C:\\Program Files\\njMule\\njmule-gui.exe");

    @TempDir
    Path dir;

    @Test
    void onWindowsTheEd2kKeyIsSavedReplacedAndRestored() throws IOException {
        FakeRegistry registry = new FakeRegistry();
        registry.command = "\"C:\\Program Files (x86)\\eMule\\emule.exe\" \"%1\"";
        LinkAssociation.Windows windows = new LinkAssociation.Windows(this.dir, registry, PROGRAM);

        assertThat(windows.isOurs()).isFalse();
        windows.take();

        assertThat(registry.exported).as("eMule's key is saved first").isTrue();
        assertThat(registry.imported.getLast())
                .startsWith("\uFEFFWindows Registry Editor Version 5.00")
                .contains("[HKEY_CURRENT_USER\\Software\\Classes\\ed2k\\shell\\open\\command]")
                .contains("@=\"\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\" \\\"%1\\\"\"");
        assertThat(windows.isOurs()).isTrue();

        windows.giveBack();

        assertThat(registry.imported.get(registry.imported.size() - 2))
                .contains("[-HKEY_CURRENT_USER\\Software\\Classes\\ed2k]");
        assertThat(registry.imported.getLast()).as("the saved key comes back").isEqualTo("eMule's key");
        assertThat(this.dir.resolve(LinkAssociation.Windows.BACKUP)).doesNotExist();
    }

    @Test
    void onWindowsNothingIsGivenBackThatIsNotOurs() throws IOException {
        FakeRegistry registry = new FakeRegistry();
        registry.command = "\"C:\\eMule\\emule.exe\" \"%1\"";
        new LinkAssociation.Windows(this.dir, registry, PROGRAM).giveBack();

        assertThat(registry.imported).isEmpty();
    }

    @Test
    void onLinuxThePreviousDefaultComesBack() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        String[] current = {"amule.desktop"};
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir,
                command -> {
                    commands.add(command);
                    if (command.get(1).equals("default")) {
                        current[0] = command.get(2);
                    }
                    return new LinkAssociation.Result(0, current[0] + "\n");
                },
                this.dir.resolve("mimeapps.list"));

        linux.take();
        assertThat(linux.isOurs()).isTrue();
        linux.giveBack();

        assertThat(commands)
                .contains(List.of("xdg-mime", "default", LinkAssociation.DESKTOP_ID, "x-scheme-handler/ed2k"));
        assertThat(current[0]).isEqualTo("amule.desktop");
    }

    @Test
    void onLinuxWithNoDefaultBeforeOurLineLeavesTheUserList() throws IOException {
        Path mimeApps = this.dir.resolve("mimeapps.list");
        Files.writeString(
                mimeApps,
                "[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k=" + LinkAssociation.DESKTOP_ID
                        + "\n",
                StandardCharsets.UTF_8);
        String[] current = {""};
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir,
                command -> {
                    if (command.get(1).equals("default")) {
                        current[0] = command.get(2);
                    }
                    return new LinkAssociation.Result(0, current[0] + "\n");
                },
                mimeApps);

        linux.take();
        linux.giveBack();

        assertThat(Files.readString(mimeApps, StandardCharsets.UTF_8))
                .isEqualTo("[Default Applications]\ntext/html=firefox.desktop\n");
    }

    @Test
    void onLinuxOnlyOurEntryLeavesTheUserList() throws IOException {
        Path mimeApps = this.dir.resolve("mimeapps.list");
        Files.writeString(
                mimeApps,
                "[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k="
                        + LinkAssociation.DESKTOP_ID
                        + ";amule.desktop;\n[Added Associations]\nx-scheme-handler/ed2k="
                        + LinkAssociation.DESKTOP_ID
                        + ";\n",
                StandardCharsets.UTF_8);
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir, command -> new LinkAssociation.Result(0, LinkAssociation.DESKTOP_ID + "\n"), mimeApps);

        linux.giveBack();

        assertThat(Files.readString(mimeApps, StandardCharsets.UTF_8)).isEqualTo("""
                        [Default Applications]
                        text/html=firefox.desktop
                        x-scheme-handler/ed2k=amule.desktop;
                        [Added Associations]
                        """);
    }

    @Test
    void onlyTheInstalledDesktopProgramTakesLinks() {
        // Paths of this system: a Windows path is a single odd file name elsewhere.
        assertThat(LinkAssociation.isDesktopProgram(Path.of("njMule", "njmule-gui.exe")))
                .isTrue();
        assertThat(LinkAssociation.isDesktopProgram(Path.of("usr", "bin", "njmule-gui")))
                .isTrue();
        assertThat(LinkAssociation.isDesktopProgram(Path.of("jdk", "bin", "java.exe")))
                .isFalse();
    }

    /** The registry seen through reg.exe: one ed2k command value, exports and imports recorded. */
    private static final class FakeRegistry implements LinkAssociation.Shell {
        private String command;
        private boolean exported;
        private final List<String> imported = new ArrayList<>();

        @Override
        public LinkAssociation.Result run(List<String> args) throws IOException {
            switch (args.get(1)) {
                case "query" -> {
                    return this.command == null
                            ? new LinkAssociation.Result(1, "not found")
                            : new LinkAssociation.Result(0, "    (Default)    REG_SZ    " + this.command + "\r\n");
                }
                case "export" -> {
                    Files.writeString(Path.of(args.get(3)), "eMule's key", StandardCharsets.UTF_8);
                    this.exported = true;
                }
                case "import" -> {
                    Path file = Path.of(args.get(2));
                    String body = file.getFileName().toString().equals(LinkAssociation.Windows.BACKUP)
                            ? Files.readString(file, StandardCharsets.UTF_8)
                            : Files.readString(file, StandardCharsets.UTF_16LE);
                    this.imported.add(body);
                    if (body.contains("[-HKEY")) {
                        this.command = null;
                    } else if (body.contains("njmule-gui.exe")) {
                        this.command = "\"" + PROGRAM + "\" \"%1\"";
                    } else {
                        this.command = "\"C:\\eMule\\emule.exe\" \"%1\"";
                    }
                }
                default -> throw new IOException("unexpected " + args);
            }
            return new LinkAssociation.Result(0, "");
        }
    }
}
