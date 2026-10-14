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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void onWindowsAProgramFolderOutsideAsciiIsRecognised() throws IOException {
        Path program = Path.of("C:\\Users\\J\u00FCrgen\\AppData\\Local\\Programs\\njMule\\njmule-gui.exe");
        FakeRegistry registry = new FakeRegistry(program);
        LinkAssociation.Windows windows = new LinkAssociation.Windows(this.dir, registry, program);

        windows.take();

        assertThat(windows.isOurs()).isTrue();
        windows.giveBack();
        assertThat(registry.imported.getLast()).contains("[-HKEY_CURRENT_USER\\Software\\Classes\\ed2k]");
        try (var left = Files.list(this.dir)) {
            assertThat(left).as("no export left behind").isEmpty();
        }
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
    void onLinuxAListKeptElsewhereStaysALink() throws IOException {
        Path real = Files.createDirectories(this.dir.resolve("dotfiles")).resolve("mimeapps.list");
        Files.writeString(
                real,
                "[Default Applications]\nx-scheme-handler/ed2k=" + LinkAssociation.DESKTOP_ID + "\n",
                StandardCharsets.UTF_8);
        Path mimeApps = this.dir.resolve("mimeapps.list");
        try {
            Files.createSymbolicLink(mimeApps, real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links need a privilege here: " + e);
        }
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir, command -> new LinkAssociation.Result(0, LinkAssociation.DESKTOP_ID + "\n"), mimeApps);

        linux.giveBack();

        assertThat(mimeApps).isSymbolicLink();
        assertThat(Files.readString(real, StandardCharsets.UTF_8)).isEqualTo("[Default Applications]\n");
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

    @Test
    void onlyTheInstalledProgramOnWindowsOrLinuxIsAssociated() throws IOException {
        assertThat(LinkAssociation.forThisSystem(this.dir))
                .as("tests run on the java launcher, as a development build does")
                .isEmpty();
        Path installed = Path.of("njMule", "njmule-gui.exe");
        FakeRegistry registry = new FakeRegistry();
        assertThat(LinkAssociation.forProgram(this.dir, registry, Path.of("bin", "java"), "Windows 11", null, "home"))
                .isEmpty();
        assertThat(LinkAssociation.forProgram(this.dir, registry, installed, "Mac OS X", null, "home"))
                .as("macOS hands links over as system events")
                .isEmpty();

        assertThat(LinkAssociation.forProgram(this.dir, registry, installed, "Windows 11", null, "home"))
                .get()
                .isInstanceOfSatisfying(
                        LinkAssociation.Windows.class,
                        windows -> assertThat(windows.command()).isEqualTo("\"" + installed + "\" \"%1\""));
    }

    @Test
    void onLinuxTheUserListIsTheOneOfTheDesktopConfiguration() throws IOException {
        Path xdg = Files.createDirectories(this.dir.resolve("xdg"));
        Path home = Files.createDirectories(this.dir.resolve("home/.config")).getParent();
        String ours = "[Default Applications]\nx-scheme-handler/ed2k=" + LinkAssociation.DESKTOP_ID + "\n";
        Files.writeString(xdg.resolve("mimeapps.list"), ours, StandardCharsets.UTF_8);
        Files.writeString(home.resolve(".config/mimeapps.list"), ours, StandardCharsets.UTF_8);
        LinkAssociation.Shell ourDefault = command -> new LinkAssociation.Result(0, LinkAssociation.DESKTOP_ID + "\n");
        Path program = Path.of("usr", "bin", "njmule-gui");

        LinkAssociation.forProgram(this.dir, ourDefault, program, "Linux", xdg.toString(), home.toString())
                .orElseThrow()
                .giveBack();
        assertThat(Files.readString(xdg.resolve("mimeapps.list"), StandardCharsets.UTF_8))
                .isEqualTo("[Default Applications]\n");
        assertThat(Files.readString(home.resolve(".config/mimeapps.list"), StandardCharsets.UTF_8))
                .isEqualTo(ours);

        LinkAssociation.forProgram(this.dir, ourDefault, program, "Linux", " ", home.toString())
                .orElseThrow()
                .giveBack();
        assertThat(Files.readString(home.resolve(".config/mimeapps.list"), StandardCharsets.UTF_8))
                .as("with a blank configuration directory, the one in the home")
                .isEqualTo("[Default Applications]\n");

        Files.writeString(home.resolve(".config/mimeapps.list"), ours, StandardCharsets.UTF_8);
        LinkAssociation.forProgram(this.dir, ourDefault, program, "Linux", null, home.toString())
                .orElseThrow()
                .giveBack();
        assertThat(Files.readString(home.resolve(".config/mimeapps.list"), StandardCharsets.UTF_8))
                .as("without a configuration directory of its own, the one in the home")
                .isEqualTo("[Default Applications]\n");
    }

    @Test
    void onLinuxTakingTwiceKeepsWhatCameFirst() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir,
                command -> {
                    commands.add(command);
                    return new LinkAssociation.Result(0, LinkAssociation.DESKTOP_ID + "\n");
                },
                this.dir.resolve("mimeapps.list"));

        linux.take();

        assertThat(commands)
                .contains(List.of("xdg-mime", "default", LinkAssociation.DESKTOP_ID, "x-scheme-handler/ed2k"));
        assertThat(this.dir.resolve(LinkAssociation.Linux.PREVIOUS)).doesNotExist();
    }

    @Test
    void onLinuxNothingIsGivenBackThatIsNotOurs() throws IOException {
        Path mimeApps = this.dir.resolve("mimeapps.list");
        String theirs = "[Default Applications]\nx-scheme-handler/ed2k=amule.desktop;\n";
        Files.writeString(mimeApps, theirs, StandardCharsets.UTF_8);
        List<List<String>> commands = new ArrayList<>();
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir,
                command -> {
                    commands.add(command);
                    return new LinkAssociation.Result(0, "amule.desktop\n");
                },
                mimeApps);

        linux.giveBack();

        assertThat(commands).containsExactly(List.of("xdg-mime", "query", "default", "x-scheme-handler/ed2k"));
        assertThat(Files.readString(mimeApps, StandardCharsets.UTF_8)).isEqualTo(theirs);
    }

    @Test
    void onLinuxAListWithoutOurEntryIsLeftAsItIs() throws IOException {
        Path mimeApps = this.dir.resolve("mimeapps.list");
        String theirs = "[Default Applications]\nx-scheme-handler/ed2k=amule.desktop;\ntext/html=firefox.desktop\n";
        Files.writeString(mimeApps, theirs, StandardCharsets.UTF_8);
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir, command -> new LinkAssociation.Result(0, LinkAssociation.DESKTOP_ID + "\n"), mimeApps);

        linux.giveBack();

        assertThat(Files.readString(mimeApps, StandardCharsets.UTF_8)).isEqualTo(theirs);
    }

    @Test
    void onLinuxWithoutXdgMimeAnswersThereWasNoDefault() throws IOException {
        String[] current = {null};
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir,
                command -> {
                    if (command.get(1).equals("default")) {
                        current[0] = command.get(2);
                    }
                    return current[0] == null
                            ? new LinkAssociation.Result(4, "no default\n")
                            : new LinkAssociation.Result(0, current[0] + "\n");
                },
                this.dir.resolve("mimeapps.list"));

        assertThat(linux.isOurs()).isFalse();
        linux.take();
        assertThat(this.dir.resolve(LinkAssociation.Linux.PREVIOUS)).exists();
        linux.giveBack();

        assertThat(this.dir.resolve(LinkAssociation.Linux.PREVIOUS)).doesNotExist();
        assertThat(this.dir.resolve("mimeapps.list")).as("no list to clean").doesNotExist();
    }

    @Test
    void aCommandThatFailsIsReportedWithItsOutput() {
        LinkAssociation.Linux linux = new LinkAssociation.Linux(
                this.dir,
                command -> command.get(1).equals("default")
                        ? new LinkAssociation.Result(3, "xdg-mime: no desktop environment\n")
                        : new LinkAssociation.Result(0, "amule.desktop\n"),
                this.dir.resolve("mimeapps.list"));

        assertThatThrownBy(linux::take)
                .isInstanceOf(IOException.class)
                .hasMessage("xdg-mime default " + LinkAssociation.DESKTOP_ID
                        + " x-scheme-handler/ed2k failed: xdg-mime: no desktop environment");
    }

    @Test
    void onWindowsWithoutAHandlerBeforeNothingIsSaved() throws IOException {
        FakeRegistry registry = new FakeRegistry();
        LinkAssociation.Windows windows = new LinkAssociation.Windows(this.dir, registry, PROGRAM);

        windows.take();
        windows.take();
        assertThat(registry.exported).as("neither before nor once ours").isFalse();
        assertThat(this.dir.resolve(LinkAssociation.Windows.BACKUP)).doesNotExist();
        windows.giveBack();

        assertThat(registry.imported).hasSize(3);
        assertThat(registry.imported.getLast()).contains("[-HKEY_CURRENT_USER\\Software\\Classes\\ed2k]");
        assertThat(windows.isOurs()).isFalse();
    }

    @Test
    void readsTheDefaultValueOfAnExportedKey() {
        assertThat(LinkAssociation.Windows.defaultValue("Windows Registry Editor Version 5.00\r\n\r\n[HKEY]\r\n"))
                .isEmpty();
        assertThat(LinkAssociation.Windows.defaultValue("@=\"\r\n@=dword:00000001\r\n@=\"unterminated\r\n"))
                .as("no string value")
                .isEmpty();
        assertThat(LinkAssociation.Windows.defaultValue(
                        "[HKEY]\n@=\"\\\"C:\\\\njMule\\\\njmule-gui.exe\\\" \\\"%1\\\"\"\n"))
                .contains("\"C:\\njMule\\njmule-gui.exe\" \"%1\"");
        assertThat(LinkAssociation.Windows.defaultValue("@=\"ends with \\\"")).contains("ends with \\");
    }

    @Test
    void runsACommandAndKeepsItsOutput() throws IOException {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        String java =
                (Files.exists(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java")).toString();

        LinkAssociation.Result version = LinkAssociation.run(List.of(java, "-version"));
        assertThat(version.exit()).isZero();
        assertThat(version.output()).contains("version");

        LinkAssociation.Result wrong = LinkAssociation.run(List.of(java, "--no-such-option"));
        assertThat(wrong.exit()).isNotZero();
        assertThat(wrong.output()).contains("--no-such-option");

        List<String> missing = List.of(bin.resolve("no-such-program").toString());
        assertThatThrownBy(() -> LinkAssociation.run(missing)).isInstanceOf(IOException.class);
    }

    /** The registry seen through reg.exe: one ed2k command value, exports and imports recorded. */
    private static final class FakeRegistry implements LinkAssociation.Shell {
        private final Path program;
        private String command;
        private boolean exported;
        private final List<String> imported = new ArrayList<>();

        FakeRegistry() {
            this(PROGRAM);
        }

        FakeRegistry(Path program) {
            this.program = program;
        }

        @Override
        public LinkAssociation.Result run(List<String> args) throws IOException {
            switch (args.get(1)) {
                case "query" -> {
                    // reg.exe writes in the console's code page, not in UTF-8 as the output is read.
                    return this.command == null
                            ? new LinkAssociation.Result(1, "not found")
                            : new LinkAssociation.Result(
                                    0,
                                    new String(
                                            ("    (Default)    REG_SZ    " + this.command + "\r\n")
                                                    .getBytes(StandardCharsets.ISO_8859_1),
                                            StandardCharsets.UTF_8));
                }
                case "export" -> {
                    if (args.get(2).equals(LinkAssociation.Windows.KEY)) {
                        Files.writeString(Path.of(args.get(3)), "eMule's key", StandardCharsets.UTF_8);
                        this.exported = true;
                    } else if (this.command == null) {
                        return new LinkAssociation.Result(1, "not found");
                    } else {
                        Files.writeString(
                                Path.of(args.get(3)),
                                "﻿Windows Registry Editor Version 5.00\r\n\r\n["
                                        + args.get(2).replace("HKCU", "HKEY_CURRENT_USER") + "]\r\n@="
                                        + LinkAssociation.Windows.regString(this.command) + "\r\n\r\n",
                                StandardCharsets.UTF_16LE);
                    }
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
                        this.command = "\"" + this.program + "\" \"%1\"";
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
