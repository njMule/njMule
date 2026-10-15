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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The start at login against a pretend registry and temp folders: nothing here touches the real system. */
class AutostartTest {

    // Exports start with a byte order mark.
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String WINDOWS_PROGRAM = "C:\\Program Files\\njMule\\njmule-gui.exe";

    @TempDir
    Path dir;

    private String program = WINDOWS_PROGRAM;
    private List<String> arguments = List.of();
    private String os = "Windows 11";
    private String xdgConfigHome = "";
    // The njMule value of the pretend Run key, as an export writes it; null when there is none.
    private String runValue;
    // The first byte of the Task Manager's flag of the njMule value, in hex; null when there is none.
    private String approvedFlag;
    private final List<List<String>> commands = new ArrayList<>();
    private final List<String> imported = new ArrayList<>();
    private Optional<Autostart> autostart;
    private boolean on;

    @Test
    void shouldOfferNothingToAJavaLauncher() {
        givenTheProgram("C:\\jdk\\bin\\java.exe");

        whenTheStartAtLoginIsLookedFor();

        thenThereIsNone();
    }

    @Test
    void shouldWriteTheRunValueWithTheProgramAndItsArgumentsOnWindows() throws IOException {
        givenTheArguments("--config-dir", "D:\\njMule data\\");
        givenTheStartAtLogin();

        whenItIsTurnedOn();

        thenTheImportedRegistryFileContains("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]");
        thenTheImportedRegistryFileContains(
                "\"njMule\"=\"\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\" \\\"--config-dir\\\""
                        + " \\\"D:\\\\njMule data\\\\\\\\\\\"\"");
        thenNoWorkFileIsLeft();
    }

    @Test
    void shouldReadTheRunValueBackOnWindows() throws IOException {
        givenTheRunValue("\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\"");
        givenTheStartAtLogin();

        whenTheSystemIsAsked();

        thenItIsOn(true);
        thenNoWorkFileIsLeft();
    }

    @Test
    void shouldTakeAValueOfAnotherCopyAsOffOnWindows() throws IOException {
        givenTheRunValue("\\\"D:\\\\old\\\\njmule-gui.exe\\\"");
        givenTheStartAtLogin();

        whenTheSystemIsAsked();

        thenItIsOn(false);
    }

    @Test
    void shouldDeleteTheRunValueOnWindows() throws IOException {
        givenTheRunValue("\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\"");
        givenTheStartAtLogin();

        whenItIsTurnedOff();

        thenTheCommandsInclude(List.of("reg", "delete", SystemAutostart.Windows.KEY, "/v", "njMule", "/f"));
    }

    @Test
    void shouldTakeAValueDisabledInTheTaskManagerAsOffOnWindows() throws IOException {
        givenTheRunValue("\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\"");
        givenTheTaskManagerFlag("03");
        givenTheStartAtLogin();

        whenTheSystemIsAsked();

        thenItIsOn(false);
    }

    @Test
    void shouldTakeAValueEnabledInTheTaskManagerAsOnOnWindows() throws IOException {
        givenTheRunValue("\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\"");
        givenTheTaskManagerFlag("02");
        givenTheStartAtLogin();

        whenTheSystemIsAsked();

        thenItIsOn(true);
    }

    @Test
    void shouldEnableAgainAValueDisabledInTheTaskManagerOnWindows() throws IOException {
        givenTheRunValue("\\\"C:\\\\Program Files\\\\njMule\\\\njmule-gui.exe\\\"");
        givenTheTaskManagerFlag("03");
        givenTheStartAtLogin();

        whenItIsTurnedOn();

        thenTheCommandsInclude(List.of("reg", "delete", SystemAutostart.Windows.APPROVED_KEY, "/v", "njMule", "/f"));
    }

    @Test
    void shouldDeleteNothingWithoutARunValueOnWindows() throws IOException {
        givenTheStartAtLogin();

        whenItIsTurnedOff();

        thenNothingWasDeleted();
    }

    @Test
    void shouldWriteAnAutostartEntryOnLinux() throws IOException {
        givenLinux();
        givenTheArguments("--config-dir", "/home/ann/my files/100%");
        givenTheStartAtLogin();

        whenItIsTurnedOn();

        thenTheLinuxEntryContains("Exec=/usr/lib/njmule/njmule-gui --config-dir \"/home/ann/my files/100%%\"");
        thenTheLinuxEntryContains("X-GNOME-Autostart-enabled=true");
        thenItIsOnNow(true);
    }

    @Test
    void shouldUseTheDefaultConfigFolderWithoutXdgConfigHome() throws IOException {
        givenLinux();
        givenNoXdgConfigHome();
        givenTheStartAtLogin();

        whenItIsTurnedOn();

        thenTheFileExists(this.dir.resolve("home/.config/autostart/org.njmule.njMule.desktop"));
    }

    @Test
    void shouldTakeAHiddenEntryAsOffOnLinux() throws IOException {
        givenLinux();
        givenTheStartAtLogin();
        givenItWasTurnedOn();
        givenTheUserHidTheEntry();

        whenTheSystemIsAsked();

        thenItIsOn(false);
    }

    @Test
    void shouldFollowAnEntryRemovedElsewhereOnLinux() throws IOException {
        givenLinux();
        givenTheStartAtLogin();
        givenItWasTurnedOn();
        givenTheUserRemovedTheEntry();

        whenTheSystemIsAsked();

        thenItIsOn(false);
    }

    @Test
    void shouldEscapeTheReservedCharactersOfAnExecLine() {
        thenTheExecArgumentOfIs("plain", "plain");
        thenTheExecArgumentOfIs("a b", "\"a b\"");
        thenTheExecArgumentOfIs("say \"$hi\"", "\"say \\\\\"\\\\$hi\\\\\"\"");
        thenTheExecArgumentOfIs("C:\\x", "\"C:\\\\\\\\x\"");
        thenTheExecArgumentOfIs("", "\"\"");
    }

    @Test
    void shouldWriteALaunchAgentOnMacOs() throws IOException {
        givenMacOs();
        givenTheArguments("--config-dir", "/Users/ann/a&b");
        givenTheStartAtLogin();

        whenItIsTurnedOn();

        thenTheLaunchAgentContains("<string>/Applications/njMule.app/Contents/MacOS/njmule-gui</string>");
        thenTheLaunchAgentContains("<string>/Users/ann/a&amp;b</string>");
        thenTheLaunchAgentContains("<key>RunAtLoad</key>\n    <true/>");
        thenItIsOnNow(true);
    }

    @Test
    void shouldRemoveTheLaunchAgentOnMacOs() throws IOException {
        givenMacOs();
        givenTheStartAtLogin();
        givenItWasTurnedOn();

        whenItIsTurnedOff();

        thenItIsOnNow(false);
    }

    private void givenTheProgram(String path) {
        this.program = path;
    }

    private void givenTheArguments(String... values) {
        this.arguments = List.of(values);
    }

    private void givenLinux() {
        this.os = "Linux";
        this.program = "/usr/lib/njmule/njmule-gui";
        this.xdgConfigHome = this.dir.resolve("xdg").toString();
    }

    private void givenNoXdgConfigHome() {
        this.xdgConfigHome = "";
    }

    private void givenMacOs() {
        this.os = "Mac OS X";
        this.program = "/Applications/njMule.app/Contents/MacOS/njmule-gui";
    }

    private void givenTheRunValue(String exported) {
        this.runValue = exported;
    }

    private void givenTheTaskManagerFlag(String firstByte) {
        this.approvedFlag = firstByte;
    }

    private void givenTheStartAtLogin() {
        whenTheStartAtLoginIsLookedFor();
    }

    private void givenItWasTurnedOn() throws IOException {
        whenItIsTurnedOn();
    }

    private void givenTheUserHidTheEntry() throws IOException {
        Path entry = linuxEntry();
        Files.writeString(entry, Files.readString(entry, StandardCharsets.UTF_8) + "Hidden=true\n");
    }

    private void givenTheUserRemovedTheEntry() throws IOException {
        Files.delete(linuxEntry());
    }

    private void whenTheStartAtLoginIsLookedFor() {
        this.autostart = SystemAutostart.forProgram(
                this.dir,
                this::registry,
                this.program,
                this.arguments,
                this.os,
                this.xdgConfigHome,
                this.dir.resolve("home").toString());
    }

    private void whenItIsTurnedOn() throws IOException {
        this.autostart.orElseThrow().turnOn();
    }

    private void whenItIsTurnedOff() throws IOException {
        this.autostart.orElseThrow().turnOff();
    }

    private void whenTheSystemIsAsked() throws IOException {
        this.on = this.autostart.orElseThrow().isOn();
    }

    private void thenThereIsNone() {
        assertThat(this.autostart).isEmpty();
    }

    private void thenItIsOn(boolean expected) {
        assertThat(this.on).isEqualTo(expected);
    }

    private void thenItIsOnNow(boolean expected) throws IOException {
        assertThat(this.autostart.orElseThrow().isOn()).isEqualTo(expected);
    }

    private void thenTheImportedRegistryFileContains(String text) {
        assertThat(this.imported).singleElement().asString().contains(text);
    }

    private void thenTheCommandsInclude(List<String> command) {
        assertThat(this.commands).contains(command);
    }

    private void thenNothingWasDeleted() {
        assertThat(this.commands).noneMatch(c -> c.get(1).equals("delete"));
    }

    private void thenNoWorkFileIsLeft() throws IOException {
        try (var left = Files.list(this.dir)) {
            assertThat(left).isEmpty();
        }
    }

    private void thenTheLinuxEntryContains(String line) throws IOException {
        assertThat(Files.readString(linuxEntry(), StandardCharsets.UTF_8).lines())
                .contains(line);
    }

    private void thenTheFileExists(Path file) {
        assertThat(file).isRegularFile();
    }

    private void thenTheExecArgumentOfIs(String argument, String expected) {
        assertThat(SystemAutostart.Linux.execArgument(argument)).isEqualTo(expected);
    }

    private void thenTheLaunchAgentContains(String text) throws IOException {
        Path agent = this.dir.resolve("home/Library/LaunchAgents/org.njmule.njMule.plist");
        assertThat(Files.readString(agent, StandardCharsets.UTF_8)).contains(text);
    }

    private Path linuxEntry() {
        return this.dir.resolve("xdg/autostart/org.njmule.njMule.desktop");
    }

    // The pretend reg tool: an export writes the njMule value or its Task Manager flag, if any; an import is only
    // recorded.
    private LinkAssociation.Result registry(List<String> command) throws IOException {
        this.commands.add(command);
        String tool = command.get(1);
        if (tool.equals("import")) {
            this.imported.add(Files.readString(Path.of(command.get(2)), StandardCharsets.UTF_16LE));
        } else if (tool.equals("export")) {
            boolean approved = command.get(2).equals(SystemAutostart.Windows.APPROVED_KEY);
            String exported = approved ? approvedExport() : runExport();
            if (exported == null) {
                return new LinkAssociation.Result(1, "ERROR");
            }
            Files.writeString(Path.of(command.get(3)), BOM + exported, StandardCharsets.UTF_16LE);
        }
        return new LinkAssociation.Result(0, "");
    }

    private String runExport() {
        return this.runValue == null
                ? null
                : "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows"
                        + "\\CurrentVersion\\Run]\r\n\"Other\"=\"x.exe\"\r\n\"njMule\"=\"" + this.runValue + "\"\r\n";
    }

    private String approvedExport() {
        return this.approvedFlag == null
                ? null
                : "Windows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows"
                        + "\\CurrentVersion\\Explorer\\StartupApproved\\Run]\r\n"
                        + "\"Other\"=hex:02,00,00,00,00,00,00,00,00,00,00,00\r\n\"njMule\"=hex:" + this.approvedFlag
                        + ",00,00,00,a1,b2,c3,d4,e5,f6,07,18\r\n";
    }
}
