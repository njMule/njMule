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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The commands the association runs, against a pretend system: nothing here touches the real one. */
class LinkAssociationTest {

    private static final Path PROGRAM = Path.of("C:\\Program Files\\njMule\\njmule-gui.exe");
    private static final Path PROGRAM_OUTSIDE_ASCII =
            Path.of("C:\\Users\\J\u00FCrgen\\AppData\\Local\\Programs\\njMule\\njmule-gui.exe");
    private static final Path INSTALLED = Path.of("njMule", "njmule-gui.exe");
    private static final String OUR_LIST =
            "[Default Applications]\nx-scheme-handler/ed2k=" + LinkAssociation.DESKTOP_ID + "\n";

    @TempDir
    Path dir;

    private FakeRegistry registry = new FakeRegistry();
    // The pretend xdg-mime: the handler it answers with, none when null, and the output of a new default that fails.
    private String currentDefault;
    private String defaultFailure;
    private final List<List<String>> commands = new ArrayList<>();
    private String userListBefore;
    private LinkAssociation association;
    private Optional<LinkAssociation> found;
    private boolean answer;
    private Optional<String> defaultValue;
    private LinkAssociation.Result result;
    private Throwable exception;

    @Test
    void shouldNotTakeTheLinksOfAnotherProgramForOursOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM);
        givenTheEd2kCommandInTheRegistry("\"C:\\Program Files (x86)\\eMule\\emule.exe\" \"%1\"");

        whenItIsAskedWhetherTheLinksAreOurs();

        thenTheAnswerIs(false);
    }

    @Test
    void shouldSaveTheEd2kKeyBeforeReplacingItOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM);
        givenTheEd2kCommandInTheRegistry("\"C:\\Program Files (x86)\\eMule\\emule.exe\" \"%1\"");

        whenTheLinksAreTaken();

        thenTheKeyWasSavedFirst();
        thenTheLastImportStartsWith("\uFEFFWindows Registry Editor Version 5.00");
        thenTheLastImportContains("[HKEY_CURRENT_USER\\Software\\Classes\\ed2k\\shell\\open\\command]");
        thenTheLastImportContains("@=\"\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\" \\\"%1\\\"\"");
        thenTheLinksAreOurs();
    }

    @Test
    void shouldRestoreTheSavedEd2kKeyOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM);
        givenTheEd2kCommandInTheRegistry("\"C:\\Program Files (x86)\\eMule\\emule.exe\" \"%1\"");
        givenTheLinksWereTaken();

        whenTheLinksAreGivenBack();

        thenTheImportBeforeTheLastContains("[-HKEY_CURRENT_USER\\Software\\Classes\\ed2k]");
        thenTheSavedKeyComesBack();
        thenThereIsNoBackup();
    }

    @Test
    void shouldRecogniseAProgramFolderOutsideAsciiOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM_OUTSIDE_ASCII);

        whenTheLinksAreTaken();

        thenTheLinksAreOurs();
    }

    @Test
    void shouldGiveBackTheLinksOfAProgramFolderOutsideAsciiOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM_OUTSIDE_ASCII);
        givenTheLinksWereTaken();

        whenTheLinksAreGivenBack();

        thenTheLastImportContains("[-HKEY_CURRENT_USER\\Software\\Classes\\ed2k]");
        thenNoExportIsLeftBehind();
    }

    @Test
    void shouldGiveBackNothingThatIsNotOursOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM);
        givenTheEd2kCommandInTheRegistry("\"C:\\eMule\\emule.exe\" \"%1\"");

        whenTheLinksAreGivenBack();

        thenNothingWasImported();
    }

    @Test
    void shouldSaveNothingWithoutAHandlerBeforeOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM);
        givenTheLinksWereTaken();

        whenTheLinksAreTaken();

        thenNoKeyWasSaved();
        thenThereIsNoBackup();
    }

    @Test
    void shouldRemoveTheKeyWithoutAHandlerBeforeOnWindows() throws IOException {
        givenTheProgramOnWindows(PROGRAM);
        givenTheLinksWereTaken();
        givenTheLinksWereTaken();

        whenTheLinksAreGivenBack();

        thenTheNumberOfImportsIs(3);
        thenTheLastImportContains("[-HKEY_CURRENT_USER\\Software\\Classes\\ed2k]");
        thenTheLinksAreNotOurs();
    }

    @ParameterizedTest
    @MethodSource("exportsWithoutAStringDefault")
    void shouldFindNoDefaultValueInAnExportWithoutAString(String reg) {
        whenTheDefaultValueIsReadFrom(reg);

        thenThereIsNoDefaultValue();
    }

    @ParameterizedTest
    @MethodSource("exportsWithAStringDefault")
    void shouldReadTheDefaultValueOfAnExportedKey(String reg, String expected) {
        whenTheDefaultValueIsReadFrom(reg);

        thenTheDefaultValueIs(expected);
    }

    @Test
    void shouldMakeNjMuleTheDefaultHandlerOnLinux() throws IOException {
        givenTheDefaultHandler("amule.desktop");
        givenTheLinuxAssociation();

        whenTheLinksAreTaken();

        thenNjMuleWasMadeTheDefaultHandler();
        thenTheLinksAreOurs();
    }

    @Test
    void shouldBringThePreviousDefaultBackOnLinux() throws IOException {
        givenTheDefaultHandler("amule.desktop");
        givenTheLinuxAssociation();
        givenTheLinksWereTaken();

        whenTheLinksAreGivenBack();

        thenTheDefaultHandlerIs("amule.desktop");
    }

    @Test
    void shouldTakeOurLineOutOfTheUserListWithNoDefaultBeforeOnLinux() throws IOException {
        givenTheUserList("[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k="
                + LinkAssociation.DESKTOP_ID + "\n");
        givenTheDefaultHandler("");
        givenTheLinuxAssociation();
        givenTheLinksWereTaken();

        whenTheLinksAreGivenBack();

        thenTheUserListIs("[Default Applications]\ntext/html=firefox.desktop\n");
    }

    @Test
    void shouldTakeOnlyOurEntryOutOfTheUserListOnLinux() throws IOException {
        givenTheUserList("[Default Applications]\ntext/html=firefox.desktop\nx-scheme-handler/ed2k="
                + LinkAssociation.DESKTOP_ID
                + ";amule.desktop;\n[Added Associations]\nx-scheme-handler/ed2k="
                + LinkAssociation.DESKTOP_ID
                + ";\n");
        givenTheDefaultHandler(LinkAssociation.DESKTOP_ID);
        givenTheLinuxAssociation();

        whenTheLinksAreGivenBack();

        thenTheUserListIs("""
                        [Default Applications]
                        text/html=firefox.desktop
                        x-scheme-handler/ed2k=amule.desktop;
                        [Added Associations]
                        """);
    }

    @Test
    void shouldKeepAUserListKeptElsewhereALinkOnLinux() throws IOException {
        givenTheUserListKeptElsewhere(OUR_LIST);
        givenTheDefaultHandler(LinkAssociation.DESKTOP_ID);
        givenTheLinuxAssociation();

        whenTheLinksAreGivenBack();

        thenTheUserListIsStillALink();
        thenTheListKeptElsewhereIs("[Default Applications]\n");
    }

    @Test
    void shouldKeepWhatCameFirstWhenTakingTwiceOnLinux() throws IOException {
        givenTheDefaultHandler(LinkAssociation.DESKTOP_ID);
        givenTheLinuxAssociation();

        whenTheLinksAreTaken();

        thenNjMuleWasMadeTheDefaultHandler();
        thenNoPreviousHandlerIsKept();
    }

    @Test
    void shouldGiveBackNothingThatIsNotOursOnLinux() throws IOException {
        givenTheUserList("[Default Applications]\nx-scheme-handler/ed2k=amule.desktop;\n");
        givenTheDefaultHandler("amule.desktop");
        givenTheLinuxAssociation();

        whenTheLinksAreGivenBack();

        thenOnlyTheDefaultHandlerWasAskedFor();
        thenTheUserListIsLeftAsItWas();
    }

    @Test
    void shouldLeaveAUserListWithoutOurEntryAsItIsOnLinux() throws IOException {
        givenTheUserList("[Default Applications]\nx-scheme-handler/ed2k=amule.desktop;\ntext/html=firefox.desktop\n");
        givenTheDefaultHandler(LinkAssociation.DESKTOP_ID);
        givenTheLinuxAssociation();

        whenTheLinksAreGivenBack();

        thenTheUserListIsLeftAsItWas();
    }

    @Test
    void shouldNotTakeTheLinksForOursWhenXdgMimeKnowsNoDefaultOnLinux() throws IOException {
        givenNoDefaultHandler();
        givenTheLinuxAssociation();

        whenItIsAskedWhetherTheLinksAreOurs();

        thenTheAnswerIs(false);
    }

    @Test
    void shouldKeepThatThereWasNoDefaultWhenXdgMimeAnswersNoneOnLinux() throws IOException {
        givenNoDefaultHandler();
        givenTheLinuxAssociation();

        whenTheLinksAreTaken();

        thenThePreviousHandlerIsKept();
    }

    @Test
    void shouldLeaveNothingBehindWhenXdgMimeAnsweredNoDefaultOnLinux() throws IOException {
        givenNoDefaultHandler();
        givenTheLinuxAssociation();
        givenTheLinksWereTaken();

        whenTheLinksAreGivenBack();

        thenNoPreviousHandlerIsKept();
        thenThereIsNoUserListToClean();
    }

    @Test
    void shouldReportACommandThatFailsWithItsOutput() {
        givenTheDefaultHandler("amule.desktop");
        givenSettingADefaultFailsWith("xdg-mime: no desktop environment\n");
        givenTheLinuxAssociation();

        whenTryingToTakeTheLinks();

        thenExceptionOccurred(IOException.class);
        thenTheMessageIs("xdg-mime default " + LinkAssociation.DESKTOP_ID
                + " x-scheme-handler/ed2k failed: xdg-mime: no desktop environment");
    }

    @ParameterizedTest
    @MethodSource("programs")
    void shouldLetOnlyTheInstalledDesktopProgramTakeLinks(Path program, boolean expected) {
        whenItIsAskedWhetherItIsTheDesktopProgram(program);

        thenTheAnswerIs(expected);
    }

    @Test
    void shouldAssociateNothingOnTheJavaLauncherAsADevelopmentBuildRuns() {
        whenTheAssociationOfThisSystemIsLookedFor();

        thenThereIsNoAssociation();
    }

    @Test
    void shouldAssociateNoOtherProgramOnWindows() {
        whenTheAssociationIsLookedFor(Path.of("bin", "java"), "Windows 11");

        thenThereIsNoAssociation();
    }

    @Test
    void shouldAssociateNothingOnMacOsWhichHandsLinksOverAsSystemEvents() {
        whenTheAssociationIsLookedFor(INSTALLED, "Mac OS X");

        thenThereIsNoAssociation();
    }

    @Test
    void shouldAssociateTheInstalledProgramOnWindows() {
        whenTheAssociationIsLookedFor(INSTALLED, "Windows 11");

        thenTheWindowsCommandIs("\"" + INSTALLED + "\" \"%1\"");
    }

    @Test
    void shouldCleanTheUserListOfTheDesktopConfigurationOnLinux() throws IOException {
        givenOurEntryInTheListOfTheConfigurationDirectory();
        givenOurEntryInTheListOfTheHome();
        givenTheDefaultHandler(LinkAssociation.DESKTOP_ID);
        givenTheInstalledProgramOnLinuxWithTheConfigurationDirectory("xdg");

        whenTheLinksAreGivenBack();

        thenTheListOfTheConfigurationDirectoryIs("[Default Applications]\n");
        thenTheListOfTheHomeIs(OUR_LIST);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = " ")
    void shouldCleanTheUserListOfTheHomeWithoutAConfigurationDirectoryOnLinux(String xdgConfigHome) throws IOException {
        givenOurEntryInTheListOfTheHome();
        givenTheDefaultHandler(LinkAssociation.DESKTOP_ID);
        givenTheInstalledProgramOnLinuxWithTheConfigurationDirectory(xdgConfigHome);

        whenTheLinksAreGivenBack();

        thenTheListOfTheHomeIs("[Default Applications]\n");
    }

    @Test
    void shouldRunACommandAndKeepItsOutput() throws IOException {
        whenTheJavaLauncherRunsWith("-version");

        thenTheExitCodeIsZero();
        thenTheOutputContains("version");
    }

    @Test
    void shouldKeepTheOutputOfACommandThatFails() throws IOException {
        whenTheJavaLauncherRunsWith("--no-such-option");

        thenTheExitCodeIsNotZero();
        thenTheOutputContains("--no-such-option");
    }

    @Test
    void shouldFailToRunAProgramThatDoesNotExist() {
        whenAProgramThatDoesNotExistIsRun();

        thenExceptionOccurred(IOException.class);
    }

    static Stream<Arguments> exportsWithoutAStringDefault() {
        return Stream.of(
                arguments("Windows Registry Editor Version 5.00\r\n\r\n[HKEY]\r\n"),
                arguments("@=\"\r\n@=dword:00000001\r\n@=\"unterminated\r\n"));
    }

    static Stream<Arguments> exportsWithAStringDefault() {
        return Stream.of(
                arguments(
                        "[HKEY]\n@=\"\\\"C:\\\\njMule\\\\njmule-gui.exe\\\" \\\"%1\\\"\"\n",
                        "\"C:\\njMule\\njmule-gui.exe\" \"%1\""),
                arguments("@=\"ends with \\\"", "ends with \\"));
    }

    // Paths of this system: a Windows path is a single odd file name elsewhere.
    static Stream<Arguments> programs() {
        return Stream.of(
                arguments(Path.of("njMule", "njmule-gui.exe"), true),
                arguments(Path.of("usr", "bin", "njmule-gui"), true),
                arguments(Path.of("jdk", "bin", "java.exe"), false));
    }

    private void givenTheProgramOnWindows(Path program) {
        this.registry = new FakeRegistry(program);
        this.association = new LinkAssociation.Windows(this.dir, this.registry, program);
    }

    private void givenTheEd2kCommandInTheRegistry(String command) {
        this.registry.command = command;
    }

    private void givenTheDefaultHandler(String desktopId) {
        this.currentDefault = desktopId;
    }

    private void givenNoDefaultHandler() {
        this.currentDefault = null;
    }

    private void givenSettingADefaultFailsWith(String output) {
        this.defaultFailure = output;
    }

    private void givenTheUserList(String content) throws IOException {
        Files.writeString(mimeApps(), content, StandardCharsets.UTF_8);
        this.userListBefore = content;
    }

    private void givenTheUserListKeptElsewhere(String content) throws IOException {
        Path real = Files.createDirectories(this.dir.resolve("dotfiles")).resolve("mimeapps.list");
        Files.writeString(real, content, StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(mimeApps(), real);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links need a privilege here: " + e);
        }
    }

    private void givenOurEntryInTheListOfTheConfigurationDirectory() throws IOException {
        Path xdg = Files.createDirectories(this.dir.resolve("xdg"));
        Files.writeString(xdg.resolve("mimeapps.list"), OUR_LIST, StandardCharsets.UTF_8);
    }

    private void givenOurEntryInTheListOfTheHome() throws IOException {
        Path config = Files.createDirectories(this.dir.resolve("home/.config"));
        Files.writeString(config.resolve("mimeapps.list"), OUR_LIST, StandardCharsets.UTF_8);
    }

    private void givenTheLinuxAssociation() {
        this.association = new LinkAssociation.Linux(this.dir, this::xdgMime, mimeApps());
    }

    // A configuration directory that is not blank is a folder below the temporary one.
    private void givenTheInstalledProgramOnLinuxWithTheConfigurationDirectory(String xdgConfigHome) {
        String xdg = xdgConfigHome == null || xdgConfigHome.isBlank()
                ? xdgConfigHome
                : this.dir.resolve(xdgConfigHome).toString();
        this.association = LinkAssociation.forProgram(
                        this.dir,
                        this::pretendSystem,
                        Path.of("usr", "bin", "njmule-gui"),
                        "Linux",
                        xdg,
                        this.dir.resolve("home").toString())
                .orElseThrow();
    }

    private void givenTheLinksWereTaken() throws IOException {
        this.association.take();
    }

    private void whenItIsAskedWhetherTheLinksAreOurs() throws IOException {
        this.answer = this.association.isOurs();
    }

    private void whenTheLinksAreTaken() throws IOException {
        this.association.take();
    }

    private void whenTryingToTakeTheLinks() {
        this.exception = catchThrowable(() -> this.association.take());
    }

    private void whenTheLinksAreGivenBack() throws IOException {
        this.association.giveBack();
    }

    private void whenTheDefaultValueIsReadFrom(String reg) {
        this.defaultValue = LinkAssociation.Windows.defaultValue(reg);
    }

    private void whenItIsAskedWhetherItIsTheDesktopProgram(Path program) {
        this.answer = LinkAssociation.isDesktopProgram(program);
    }

    private void whenTheAssociationOfThisSystemIsLookedFor() {
        this.found = LinkAssociation.forThisSystem(this.dir);
    }

    private void whenTheAssociationIsLookedFor(Path program, String os) {
        this.found = LinkAssociation.forProgram(this.dir, this::pretendSystem, program, os, null, "home");
    }

    private void whenTheJavaLauncherRunsWith(String option) throws IOException {
        this.result = LinkAssociation.run(List.of(javaLauncher(), option));
    }

    private void whenAProgramThatDoesNotExistIsRun() {
        List<String> missing = List.of(javaBin().resolve("no-such-program").toString());
        this.exception = catchThrowable(() -> LinkAssociation.run(missing));
    }

    private void thenTheAnswerIs(boolean expected) {
        assertThat(this.answer).isEqualTo(expected);
    }

    private void thenTheLinksAreOurs() throws IOException {
        assertThat(this.association.isOurs()).isTrue();
    }

    private void thenTheLinksAreNotOurs() throws IOException {
        assertThat(this.association.isOurs()).isFalse();
    }

    private void thenTheKeyWasSavedFirst() {
        assertThat(this.registry.exported).as("eMule's key is saved first").isTrue();
    }

    private void thenNoKeyWasSaved() {
        assertThat(this.registry.exported).as("neither before nor once ours").isFalse();
    }

    private void thenTheLastImportStartsWith(String start) {
        assertThat(this.registry.imported.getLast()).startsWith(start);
    }

    private void thenTheLastImportContains(String text) {
        assertThat(this.registry.imported.getLast()).contains(text);
    }

    private void thenTheImportBeforeTheLastContains(String text) {
        assertThat(this.registry.imported.get(this.registry.imported.size() - 2))
                .contains(text);
    }

    private void thenTheSavedKeyComesBack() {
        assertThat(this.registry.imported.getLast())
                .as("the saved key comes back")
                .isEqualTo("eMule's key");
    }

    private void thenTheNumberOfImportsIs(int expected) {
        assertThat(this.registry.imported).hasSize(expected);
    }

    private void thenNothingWasImported() {
        assertThat(this.registry.imported).isEmpty();
    }

    private void thenThereIsNoBackup() {
        assertThat(this.dir.resolve(LinkAssociation.Windows.BACKUP)).doesNotExist();
    }

    private void thenNoExportIsLeftBehind() throws IOException {
        try (var left = Files.list(this.dir)) {
            assertThat(left).as("no export left behind").isEmpty();
        }
    }

    private void thenThereIsNoDefaultValue() {
        assertThat(this.defaultValue).isEmpty();
    }

    private void thenTheDefaultValueIs(String expected) {
        assertThat(this.defaultValue).contains(expected);
    }

    private void thenNjMuleWasMadeTheDefaultHandler() {
        assertThat(this.commands)
                .contains(List.of("xdg-mime", "default", LinkAssociation.DESKTOP_ID, "x-scheme-handler/ed2k"));
    }

    private void thenOnlyTheDefaultHandlerWasAskedFor() {
        assertThat(this.commands).containsExactly(List.of("xdg-mime", "query", "default", "x-scheme-handler/ed2k"));
    }

    private void thenTheDefaultHandlerIs(String expected) {
        assertThat(this.currentDefault).isEqualTo(expected);
    }

    private void thenThePreviousHandlerIsKept() {
        assertThat(this.dir.resolve(LinkAssociation.Linux.PREVIOUS)).exists();
    }

    private void thenNoPreviousHandlerIsKept() {
        assertThat(this.dir.resolve(LinkAssociation.Linux.PREVIOUS)).doesNotExist();
    }

    private void thenTheUserListIs(String expected) throws IOException {
        assertThat(Files.readString(mimeApps(), StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    private void thenTheUserListIsLeftAsItWas() throws IOException {
        thenTheUserListIs(this.userListBefore);
    }

    private void thenThereIsNoUserListToClean() {
        assertThat(mimeApps()).as("no list to clean").doesNotExist();
    }

    private void thenTheUserListIsStillALink() {
        assertThat(mimeApps()).isSymbolicLink();
    }

    private void thenTheListKeptElsewhereIs(String expected) throws IOException {
        assertThat(Files.readString(this.dir.resolve("dotfiles/mimeapps.list"), StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }

    private void thenTheListOfTheConfigurationDirectoryIs(String expected) throws IOException {
        assertThat(Files.readString(this.dir.resolve("xdg/mimeapps.list"), StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }

    private void thenTheListOfTheHomeIs(String expected) throws IOException {
        assertThat(Files.readString(this.dir.resolve("home/.config/mimeapps.list"), StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }

    private void thenThereIsNoAssociation() {
        assertThat(this.found).isEmpty();
    }

    private void thenTheWindowsCommandIs(String expected) {
        assertThat(this.found)
                .get()
                .isInstanceOfSatisfying(
                        LinkAssociation.Windows.class,
                        windows -> assertThat(windows.command()).isEqualTo(expected));
    }

    private void thenTheExitCodeIsZero() {
        assertThat(this.result.exit()).isZero();
    }

    private void thenTheExitCodeIsNotZero() {
        assertThat(this.result.exit()).isNotZero();
    }

    private void thenTheOutputContains(String text) {
        assertThat(this.result.output()).contains(text);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheMessageIs(String expected) {
        assertThat(this.exception).hasMessage(expected);
    }

    private Path mimeApps() {
        return this.dir.resolve("mimeapps.list");
    }

    private static Path javaBin() {
        return Path.of(System.getProperty("java.home"), "bin");
    }

    private static String javaLauncher() {
        Path bin = javaBin();
        return (Files.exists(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java")).toString();
    }

    // reg.exe goes to the pretend registry, anything else to the pretend xdg-mime.
    private LinkAssociation.Result pretendSystem(List<String> command) throws IOException {
        return command.getFirst().equals("reg") ? this.registry.run(command) : xdgMime(command);
    }

    private LinkAssociation.Result xdgMime(List<String> command) {
        this.commands.add(command);
        if (command.get(1).equals("default")) {
            if (this.defaultFailure != null) {
                return new LinkAssociation.Result(3, this.defaultFailure);
            }
            this.currentDefault = command.get(2);
        }
        return this.currentDefault == null
                ? new LinkAssociation.Result(4, "no default\n")
                : new LinkAssociation.Result(0, this.currentDefault + "\n");
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
