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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.config.AppDirectories;
import org.njmule.webui.WebUiSettings;

class WindowsServiceTest {

    private static final String PASSWORD = "correct horse";
    private static final String KEPT_HASH = "pbkdf2-sha512$1$AA$AA";

    @TempDir
    Path dir;

    private final List<List<String>> commands = new ArrayList<>();
    private final Map<String, Integer> answers = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private boolean windows = true;
    private boolean elevated = true;
    private String elevatedSays = "The service njMule runs.\n";
    private List<String> elevatedArgs;
    private Path program;
    private Path passwordFile;
    private boolean asked;
    private int code;
    private String line;

    @BeforeEach
    void givenNjmulesWindowsPackage() throws IOException {
        Path app = Files.createDirectories(this.dir.resolve("app"));
        this.program = Files.createFile(app.resolve("njmule.exe"));
        Files.createFile(app.resolve(WindowsService.HOST));
    }

    @Test
    void shouldInstallTheServiceWithItsProfileItsRightsAndAFirewallRule() throws Exception {
        givenAPasswordFile();

        whenRun("--service=install", "--webui-password-file", this.passwordFile.toString());

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheCommandsWere(
                List.of(host(), "status"),
                List.of(host(), "install"),
                List.of("icacls", profile().toString(), "/grant", "NT SERVICE\\njMule:(OI)(CI)M", "/T", "/Q"),
                List.of("icacls", app().toString(), "/grant", "NT SERVICE\\njMule:(OI)(CI)RX", "/T", "/Q"),
                List.of("netsh", "advfirewall", "firewall", "delete", "rule", "name=njMule (service)"),
                List.of(
                        "netsh",
                        "advfirewall",
                        "firewall",
                        "add",
                        "rule",
                        "name=njMule (service)",
                        "dir=in",
                        "action=allow",
                        "program=" + this.program,
                        "enable=yes",
                        "profile=private,domain"),
                List.of(host(), "start"));
        thenTheProfileHasAWebInterfacePassword();
        thenItSaid("http://localhost:4711/");
    }

    @Test
    void shouldRestartAServiceThatRunsSoThatItTakesItsNewSettings() throws Exception {
        givenAPasswordFile();
        givenTheServiceIs(4);

        whenRun("--service=install", "--webui-password-file", this.passwordFile.toString());

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheServiceWasStoppedAndStartedAgain();
    }

    @Test
    void shouldChangeTheWebInterfaceOfTheServiceAndKeepItsPassword() throws Exception {
        givenTheProfileHasAWebInterfacePassword();

        whenRun("--service=install", "--webui-lan");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenNoPasswordWasAsked();
        thenTheProfileKeepsItsPassword();
        thenTheWebInterfaceIsOpenToOtherComputers();
    }

    @Test
    void shouldAskForTheRightsWithTheWebInterfaceOptionsAlone() throws Exception {
        givenNoAdministratorsRights();

        whenRun("--service=install", "--webui-lan");

        thenItAskedForTheRightsWith("--service=install", "--webui-lan", "--service-output");
    }

    @Test
    void shouldAskForTheWebInterfacePasswordOfANewProfile() throws Exception {
        whenRun("--service=install");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheProfileHasAWebInterfacePassword();
    }

    @Test
    void shouldAskForTheAdministratorsRightsAndShowWhatTheServiceSaid() throws Exception {
        givenNoAdministratorsRights();

        whenRun("--service=install", "--webui-lan", "--webui-password-file", "password.txt");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenItAskedForTheRightsWith(
                "--service=install",
                "--webui-lan",
                "--webui-password-file",
                Path.of("password.txt").toAbsolutePath().toString());
        thenItSaid("The service njMule runs.");
        thenTheCommandsWere();
    }

    @Test
    void shouldChangeNothingWhenTheRightsAreRefused() throws Exception {
        givenNoAdministratorsRights();
        givenTheRightsAreRefused();

        whenRun("--service=start");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenItComplained("did not get the administrator's rights");
    }

    @Test
    void shouldRemoveTheServiceAndKeepItsProfile() throws Exception {
        givenTheServiceIs(4);

        whenRun("--service=uninstall");

        thenTheExitCodeIs(Main.EXIT_OK);
        thenTheCommandsWere(
                List.of(host(), "status"),
                List.of("icacls", app().toString(), "/remove", "NT SERVICE\\njMule", "/T", "/Q"),
                List.of("icacls", profile().toString(), "/remove", "NT SERVICE\\njMule", "/T", "/Q"),
                List.of(host(), "uninstall"),
                List.of("netsh", "advfirewall", "firewall", "delete", "rule", "name=njMule (service)"));
        thenItSaid("its profile stays in " + profile());
    }

    @Test
    void shouldTellThatTheServiceIsNotInstalled() throws Exception {
        givenTheServiceIs(1060);

        whenRun("--service=status");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenItSaid("is not installed");
    }

    @Test
    void shouldReportAWindowsErrorOfAStep() throws Exception {
        givenTheServiceFailsTo("stop", 1053);

        whenRun("--service=stop");

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenItComplained("Windows error 1053");
    }

    @Test
    void shouldLeaveOtherSystemsToTheirOwnServices() throws Exception {
        givenAnotherSystem();

        whenRun("--service=install");

        thenTheExitCodeIs(Main.EXIT_USAGE);
        thenItComplained("systemd");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "--service=start|--service=start",
                "C:\\Program Files\\njMule\\a.txt|\"C:\\Program Files\\njMule\\a.txt\"",
                "C:\\a b\\|\"C:\\a b\\\\\"",
                "say \"hi\"|\"say \\\"hi\\\"\""
            })
    void shouldQuoteAnArgumentAsTheCRuntimeOfWindowsReadsIt(String arg, String expected) {
        whenTheCommandLineIsMadeOf(arg);

        thenTheCommandLineIs(expected);
    }

    private void givenAPasswordFile() throws IOException {
        this.passwordFile = Files.writeString(this.dir.resolve("password.txt"), PASSWORD + "\n");
    }

    private void givenTheProfileHasAWebInterfacePassword() throws IOException {
        Path config = AppDirectories.resolve(Optional.of(profile())).config();
        new WebUiSettings(true, 4711, false, KEPT_HASH, false).save(config);
    }

    private void givenNoAdministratorsRights() {
        this.elevated = false;
    }

    private void givenTheRightsAreRefused() {
        this.elevatedSays = "";
    }

    private void givenTheServiceIs(int state) {
        this.answers.put("status", state);
    }

    private void givenTheServiceFailsTo(String verb, int error) {
        this.answers.put(verb, error);
    }

    private void givenAnotherSystem() {
        this.windows = false;
    }

    private void whenRun(String... args) throws Exception {
        LaunchOptions cli = LaunchOptions.parse(args);
        this.code = new WindowsService(
                        new FakeWindows(),
                        new PrintStream(this.out, true, StandardCharsets.UTF_8),
                        new PrintStream(this.err, true, StandardCharsets.UTF_8),
                        prompt -> {
                            this.asked = true;
                            return PASSWORD.toCharArray();
                        })
                .run(cli);
    }

    private void whenTheCommandLineIsMadeOf(String arg) {
        this.line = WindowsService.commandLine(List.of(arg));
    }

    private void thenTheExitCodeIs(int expected) {
        assertThat(this.code).isEqualTo(expected);
    }

    private void thenTheCommandsWere(List<?>... expected) {
        assertThat(this.commands).isEqualTo(List.of(expected));
    }

    private void thenTheServiceWasStoppedAndStartedAgain() {
        int size = this.commands.size();
        assertThat(this.commands.subList(size - 2, size))
                .isEqualTo(List.of(List.of(host(), "stop"), List.of(host(), "start")));
    }

    private void thenNoPasswordWasAsked() {
        assertThat(this.asked).isFalse();
    }

    private void thenTheProfileKeepsItsPassword() {
        Path config = AppDirectories.resolve(Optional.of(profile())).config();
        assertThat(WebUiSettings.load(config).passwordHash()).isEqualTo(KEPT_HASH);
    }

    private void thenTheWebInterfaceIsOpenToOtherComputers() {
        Path config = AppDirectories.resolve(Optional.of(profile())).config();
        assertThat(WebUiSettings.load(config).lan()).isTrue();
    }

    private void thenTheProfileHasAWebInterfacePassword() {
        Path config = AppDirectories.resolve(Optional.of(profile())).config();
        assertThat(WebUiSettings.load(config).passwordHash()).isNotEmpty();
    }

    private void thenItAskedForTheRightsWith(String... args) {
        assertThat(this.elevatedArgs).startsWith(args).contains("--service-output");
    }

    private void thenItSaid(String part) {
        assertThat(this.out.toString(StandardCharsets.UTF_8)).contains(part);
    }

    private void thenItComplained(String part) {
        assertThat(this.err.toString(StandardCharsets.UTF_8)).contains(part);
    }

    private void thenTheCommandLineIs(String expected) {
        assertThat(this.line).isEqualTo(expected);
    }

    private Path app() {
        return this.program.getParent();
    }

    private String host() {
        return app().resolve(WindowsService.HOST).toString();
    }

    private Path profile() {
        return this.dir.resolve("ProgramData").resolve("njMule");
    }

    private final class FakeWindows implements WindowsService.Windows {
        @Override
        public boolean applies() {
            return WindowsServiceTest.this.windows;
        }

        @Override
        public boolean elevated() {
            return WindowsServiceTest.this.elevated;
        }

        @Override
        public Optional<Path> program() {
            return Optional.of(WindowsServiceTest.this.program);
        }

        @Override
        public Path programData() {
            return WindowsServiceTest.this.dir.resolve("ProgramData");
        }

        @Override
        public Path ownFolder() {
            return WindowsServiceTest.this.dir.resolve("own");
        }

        @Override
        public int run(List<String> command) {
            WindowsServiceTest.this.commands.add(command);
            boolean ours = command.getFirst().endsWith(WindowsService.HOST);
            return ours ? WindowsServiceTest.this.answers.getOrDefault(command.get(1), 0) : 0;
        }

        @Override
        public int runElevated(Path program, List<String> args) throws IOException {
            WindowsServiceTest.this.elevatedArgs = args;
            Path output = Path.of(args.get(args.indexOf("--service-output") + 1));
            Files.writeString(output, WindowsServiceTest.this.elevatedSays);
            return WindowsServiceTest.this.elevatedSays.isEmpty() ? 1 : 0;
        }
    }
}
