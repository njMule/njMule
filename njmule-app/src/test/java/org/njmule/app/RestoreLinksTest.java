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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the uninstaller's {@code --restore-links} does, against a pretend registry: never the real one. */
class RestoreLinksTest {

    private static final Path PROGRAM = Path.of("C:\\Users\\Ann\\AppData\\Local\\Programs\\njMule\\njmule-gui.exe");
    private static final String OURS = "\"" + PROGRAM + "\" \"%1\"";
    private static final String EMULE = "\"C:\\Program Files\\eMule\\emule.exe\" \"%1\"";

    @TempDir
    Path dir;

    private final PretendRegistry registry = new PretendRegistry();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Path config;
    private boolean desktopProgram = true;
    private final PretendAutostart autostart = new PretendAutostart(false);
    private int exit;
    private LaunchOptions options;
    private Exception failure;

    @Test
    void shouldGiveTheLinksBackToTheHandlerSavedBefore() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(OURS);
        givenTheSavedHandlerIs(EMULE);

        whenTheLinksAreRestored();

        thenTheLinksOpenIn(EMULE);
        thenTheSavedHandlerIsGone();
        thenTheExitCodeIs(Main.EXIT_OK);
    }

    @Test
    void shouldStopStartingAtLoginAsTheUninstallersAsk() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(EMULE);
        givenItStartsAtLogin();

        whenTheLinksAreRestored();

        thenItStartsAtLogin(false);
        thenTheOutputSays("no longer starts when you log in");
        thenTheExitCodeIs(Main.EXIT_OK);
    }

    @Test
    void shouldRemoveOurHandlerWhenNoneWasSaved() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(OURS);

        whenTheLinksAreRestored();

        thenNoProgramOpensTheLinks();
        thenTheExitCodeIs(Main.EXIT_OK);
    }

    @Test
    void shouldLeaveTheLinksOfAnotherProgram() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(EMULE);
        givenTheSavedHandlerIs("\"C:\\old\\client.exe\" \"%1\"");

        whenTheLinksAreRestored();

        thenTheLinksOpenIn(EMULE);
        thenNothingWasImported();
        thenTheOutputSays("left as they are");
    }

    @Test
    void shouldRemoveOurHandlerWithoutAConfigurationDirectory() throws Exception {
        givenNoConfigurationDirectory();
        givenTheLinksOpenIn(OURS);

        whenTheLinksAreRestored();

        thenNoProgramOpensTheLinks();
        thenTheConfigurationDirectoryStillDoesNotExist();
        thenTheExitCodeIs(Main.EXIT_OK);
    }

    @Test
    void shouldDoNothingWhereThisProgramCannotOpenLinks() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(OURS);
        givenAProgramThatCannotOpenLinks();

        whenTheLinksAreRestored();

        thenTheLinksOpenIn(OURS);
        thenTheOutputSays("nothing to give back");
        thenTheExitCodeIs(Main.EXIT_OK);
    }

    @Test
    void shouldTellWhenTheRegistryRefusesTheChange() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(OURS);
        givenTheRegistryRefusesImports();

        whenTheLinksAreRestored();

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsSay("Cannot give the ed2k links back");
    }

    @Test
    void shouldTellWhenTheStartAtLoginCannotBeTurnedOff() throws Exception {
        givenTheConfigurationDirectory();
        givenTheLinksOpenIn(OURS);
        givenItStartsAtLogin();
        givenTheStartAtLoginCannotBeTurnedOff();

        whenTheLinksAreRestored();

        thenTheExitCodeIs(Main.EXIT_FAILURE);
        thenTheErrorsSay("Cannot stop njMule from starting at login: access denied");
    }

    @Test
    void shouldReadTheOptionWithAConfigurationDirectory() throws Exception {
        whenTheCommandLineIs("--restore-links", "--config-dir", this.dir.toString());

        thenTheActionIs(LaunchOptions.Action.RESTORE_LINKS);
    }

    @Test
    void shouldRefuseTheOptionBesideAnotherAction() throws Exception {
        whenTheCommandLineIs("--restore-links", "--exit");

        thenTheCommandLineIsRefused("cannot be combined");
    }

    @Test
    void shouldRefuseTheOptionBesideLinks() throws Exception {
        whenTheCommandLineIs("--restore-links", "ed2k://|server|1.2.3.4|4661|/");

        thenTheCommandLineIsRefused("cannot be combined with links");
    }

    private void givenTheConfigurationDirectory() throws IOException {
        this.config = Files.createDirectories(this.dir.resolve("config"));
    }

    private void givenNoConfigurationDirectory() {
        this.config = this.dir.resolve("never-created");
    }

    private void givenTheLinksOpenIn(String command) {
        this.registry.command = command;
    }

    private void givenTheSavedHandlerIs(String command) throws IOException {
        Files.writeString(this.config.resolve(LinkAssociation.Windows.BACKUP), command, StandardCharsets.UTF_8);
    }

    private void givenItStartsAtLogin() {
        this.autostart.turnOn();
    }

    private void givenTheStartAtLoginCannotBeTurnedOff() {
        this.autostart.refusesToTurnOff = true;
    }

    private void givenAProgramThatCannotOpenLinks() {
        this.desktopProgram = false;
    }

    private void givenTheRegistryRefusesImports() {
        this.registry.refuseImports = true;
    }

    private void whenTheLinksAreRestored() {
        this.exit = Main.restoreLinks(
                this.config,
                configDir -> this.desktopProgram
                        ? Optional.of(new LinkAssociation.Windows(configDir, this.registry, PROGRAM))
                        : Optional.empty(),
                configDir -> Optional.of(this.autostart),
                new PrintStream(this.out, true, StandardCharsets.UTF_8),
                new PrintStream(this.err, true, StandardCharsets.UTF_8));
    }

    private void whenTheCommandLineIs(String... args) {
        try {
            this.options = LaunchOptions.parse(args);
        } catch (LaunchOptions.UsageException e) {
            this.failure = e;
        }
    }

    private void thenTheLinksOpenIn(String command) {
        assertThat(this.registry.command).isEqualTo(command);
    }

    private void thenNoProgramOpensTheLinks() {
        assertThat(this.registry.command).isNull();
    }

    private void thenTheSavedHandlerIsGone() {
        assertThat(this.config.resolve(LinkAssociation.Windows.BACKUP)).doesNotExist();
    }

    private void thenNothingWasImported() {
        assertThat(this.registry.imported).isEmpty();
    }

    private void thenTheConfigurationDirectoryStillDoesNotExist() {
        assertThat(this.config).doesNotExist();
    }

    private void thenItStartsAtLogin(boolean expected) {
        assertThat(this.autostart.isOn()).isEqualTo(expected);
    }

    private void thenTheExitCodeIs(int expected) {
        assertThat(this.exit).isEqualTo(expected);
    }

    private void thenTheOutputSays(String text) {
        assertThat(this.out.toString(StandardCharsets.UTF_8)).contains(text);
    }

    private void thenTheErrorsSay(String text) {
        assertThat(this.err.toString(StandardCharsets.UTF_8)).contains(text);
    }

    private void thenTheActionIs(LaunchOptions.Action expected) {
        assertThat(this.failure).isNull();
        assertThat(this.options.action()).isEqualTo(expected);
    }

    private void thenTheCommandLineIsRefused(String reason) {
        assertThat(this.failure)
                .isInstanceOf(LaunchOptions.UsageException.class)
                .hasMessageContaining(reason);
    }

    /** The user's ed2k key as reg.exe shows and changes it; the saved handler is the backup file's text. */
    private static final class PretendRegistry implements LinkAssociation.Shell {
        private String command;
        private boolean refuseImports;
        private final List<String> imported = new ArrayList<>();

        @Override
        public LinkAssociation.Result run(List<String> args) throws IOException {
            return switch (args.get(1)) {
                case "query" -> new LinkAssociation.Result(this.command == null ? 1 : 0, "");
                case "export" -> export(Path.of(args.get(3)));
                case "import" -> importFile(Path.of(args.get(2)));
                default -> throw new IOException("unexpected " + args);
            };
        }

        private LinkAssociation.Result export(Path file) throws IOException {
            if (this.command == null) {
                return new LinkAssociation.Result(1, "not found");
            }
            Files.writeString(
                    file,
                    "\uFEFFWindows Registry Editor Version 5.00\r\n\r\n[HKEY_CURRENT_USER\\x]\r\n@="
                            + LinkAssociation.Windows.regString(this.command) + "\r\n",
                    StandardCharsets.UTF_16LE);
            return new LinkAssociation.Result(0, "");
        }

        private LinkAssociation.Result importFile(Path file) throws IOException {
            if (this.refuseImports) {
                return new LinkAssociation.Result(1, "access denied");
            }
            boolean backup = file.getFileName().toString().equals(LinkAssociation.Windows.BACKUP);
            String body = Files.readString(file, backup ? StandardCharsets.UTF_8 : StandardCharsets.UTF_16LE);
            this.imported.add(body);
            this.command = backup ? body : body.contains("[-HKEY") ? null : OURS;
            return new LinkAssociation.Result(0, "");
        }
    }
}
