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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.ActionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.app.Autostart;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class SettingsScreenTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen screen;
    private final PretendAutostart system = new PretendAutostart();
    private int uploadLimitSetMeanwhile;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldWriteOnlyWhatTheUserChangedFromOneApplyToTheNext() throws Exception {
        givenTheSettingsShow();
        givenTheUploadLimitRaisedMeanwhileBy(77);
        givenTheNickApplied("Other");
        givenTheEngineHasTheNick("Other");
        givenTheEngineKeptTheUploadLimitSetMeanwhile();
        givenTheNickTyped("Third");

        whenTheUserApplies();

        thenTheEngineSavesTheNick("Third");
        thenTheEngineKeptTheUploadLimitSetMeanwhile();
    }

    @Test
    void shouldTakeNoTextDirectionControlsInANick() throws Exception {
        givenTheSettingsShow();
        givenTheNickTypedWithTextDirectionControls("ann");
        givenTheNickFieldShows("annxxxxxyyyy");

        whenTheUserApplies();

        thenTheEngineSavesTheNick("annxxxxxyyyy");
        thenTheUserIsNotToldItWasNotSaved();
    }

    @Test
    void shouldSaveTheEventCommandTheUserTyped() throws Exception {
        givenTheSettingsShow();
        givenTheUserTurnedOnTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "notify \"%NAME done\"");

        whenTheUserApplies();

        thenTheEngineRunsOn(
                EventCommands.Event.DOWNLOAD_COMPLETED, new EventCommands.Command(true, "notify \"%NAME done\""));
    }

    @Test
    void shouldNotSaveACommandWithAQuoteLeftOpen() throws Exception {
        givenTheSettingsShow();
        givenTheUserTurnedOnTheCommand(EventCommands.Event.CHAT_MESSAGE, "say \"%MESSAGE");

        whenTheUserApplies();

        thenTheUserIsToldItWasNotSaved();
        thenTheEngineRunsNoCommandFor(EventCommands.Event.CHAT_MESSAGE);
    }

    @Test
    void shouldPlayTheTypedSoundFileForATest() throws Exception {
        givenTheSettingsShow();
        givenTheUserTypedTheSoundFile(ding());

        whenTheUserTestsTheSound();

        thenThePlayedSoundsAre(ding());
        thenTheEngineHasNoSoundFile();
    }

    @Test
    void shouldSaveTheNotificationSound() throws Exception {
        givenTheSettingsShow();
        givenTheUserTurnedTheSoundOn();
        givenTheUserTypedTheSoundFile(ding());

        whenTheUserApplies();

        thenTheEnginePlays(ding());
    }

    @Test
    void shouldSaveHowItLooksForNewVersions() throws Exception {
        givenTheSettingsShow();
        givenTheUserChose(UpdateMode.OFF, UpdateChannel.PRERELEASE);

        whenTheUserApplies();

        thenTheEngineLooksForNewVersions(UpdateMode.OFF, UpdateChannel.PRERELEASE);
    }

    @Test
    void shouldSaveWhenAndInWhichHoursItInstalls() throws Exception {
        givenTheSettingsShow();
        givenTheUserChose(UpdateMode.INSTALL, UpdateChannel.STABLE);
        givenTheUserChoseToInstall(UpdateInstallTime.EXIT_OR_NIGHT, "01:30-04:00");

        whenTheUserApplies();

        thenTheEngineInstalls(UpdateInstallTime.EXIT_OR_NIGHT, "01:30-04:00");
    }

    @Test
    void shouldOfferTheNightHoursOnlyWhenNjMuleInstallsAtNight() throws Exception {
        givenTheSettingsShow();
        givenTheUserChose(UpdateMode.NOTIFY, UpdateChannel.STABLE);

        whenTheUserChoseToInstall(UpdateInstallTime.EXIT_OR_NIGHT, "01:30-04:00");

        thenTheNightHoursCanBeSet(false);
    }

    @Test
    void shouldOfferNoStartAtLoginOutsideTheInstalledProgram() throws Exception {
        givenNjMuleRunningOutsideTheInstalledProgram();

        whenTheSettingsShow();

        thenTheStartAtLoginBoxIsOff();
        thenTheStartAtLoginBoxSaysWhy();
    }

    @Test
    void shouldShowTheStartAtLoginAsTheSystemHasIt() throws Exception {
        givenTheSystemStartsNjMuleAtLogin(true);
        givenTheInstalledNjMuleRunning();

        whenTheSettingsShowWithTheStartAtLogin();

        thenTheStartAtLoginBoxShows(true);
    }

    @Test
    void shouldTurnTheStartAtLoginOnWhenApplied() throws Exception {
        givenTheSystemStartsNjMuleAtLogin(false);
        givenTheSettingsShowWithTheStartAtLogin();
        givenTheUserTickedTheStartAtLogin(true);

        whenTheUserApplies();

        thenTheSystemStartsNjMuleAtLogin(true);
    }

    @Test
    void shouldLeaveTheSystemAloneWhenTheStartAtLoginDidNotChange() throws Exception {
        givenTheSystemStartsNjMuleAtLogin(true);
        givenTheSettingsShowWithTheStartAtLogin();

        whenTheUserApplies();

        thenTheSystemWasNotChanged();
    }

    private void givenTheSettingsShow() throws Exception {
        givenNjMuleRunningOutsideTheInstalledProgram();
        whenTheSettingsShow();
    }

    private void givenNjMuleRunningOutsideTheInstalledProgram() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
    }

    private void givenTheSystemStartsNjMuleAtLogin(boolean on) {
        this.system.on = on;
    }

    private void givenTheInstalledNjMuleRunning() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"), Optional.of(this.system));
    }

    private void givenTheSettingsShowWithTheStartAtLogin() throws Exception {
        givenTheInstalledNjMuleRunning();
        whenTheSettingsShowWithTheStartAtLogin();
    }

    // The tray menu or the scheduler changes a limit while the settings show.
    private void givenTheUploadLimitRaisedMeanwhileBy(int kiBps) throws Exception {
        int before = this.test.engine.preferences().maxUploadKiBps();
        this.uploadLimitSetMeanwhile = before + kiBps;
        this.test
                .engine
                .updatePreferences(p -> p.toBuilder()
                        .maxUploadKiBps(this.uploadLimitSetMeanwhile)
                        .build())
                .get(10, TimeUnit.SECONDS);
    }

    // A second apply starts from what this one wrote.
    private void givenTheNickApplied(String nick) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.nickField().setText(nick);
            this.screen.apply();
        });
        FxTesting.await("saved", () -> this.test.engine.preferences().nick().equals(nick));
    }

    private void givenTheEngineHasTheNick(String nick) {
        assertThat(this.test.engine.preferences().nick()).isEqualTo(nick);
    }

    private void givenTheEngineKeptTheUploadLimitSetMeanwhile() {
        thenTheEngineKeptTheUploadLimitSetMeanwhile();
    }

    private void givenTheNickTyped(String nick) throws Exception {
        FxTesting.onFx(() -> this.screen.nickField().setText(nick));
    }

    // Embeddings, overrides and isolates would show another name than the one sent, which the engine refuses.
    private void givenTheNickTypedWithTextDirectionControls(String start) throws Exception {
        StringBuilder nick = new StringBuilder(start);
        for (char c = 0x202A; c <= 0x202E; c++) {
            nick.append(c).append('x');
        }
        for (char c = 0x2066; c <= 0x2069; c++) {
            nick.append(c).append('y');
        }
        givenTheNickTyped(nick.toString());
    }

    private void givenTheNickFieldShows(String nick) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.nickField().getText())).isEqualTo(nick);
    }

    private void givenTheUserTurnedOnTheCommand(EventCommands.Event event, String commandLine) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.eventSwitch(event).setSelected(true);
            this.screen.eventField(event).setText(commandLine);
        });
    }

    private void givenTheUserChose(UpdateMode mode, UpdateChannel channel) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.updateModeBox().setValue(mode);
            this.screen.updateChannelBox().setValue(channel);
        });
    }

    private void givenTheUserChoseToInstall(UpdateInstallTime when, String hours) throws Exception {
        chooseToInstall(when, hours);
    }

    private void whenTheUserChoseToInstall(UpdateInstallTime when, String hours) throws Exception {
        chooseToInstall(when, hours);
    }

    private void chooseToInstall(UpdateInstallTime when, String hours) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.updateInstallTimeBox().setValue(when);
            this.screen.nightHoursField().setText(hours);
            this.screen.nightHoursField().fireEvent(new ActionEvent());
        });
    }

    private void givenTheUserTurnedTheSoundOn() throws Exception {
        FxTesting.onFx(() -> this.screen.soundBox().setSelected(true));
    }

    private void givenTheUserTypedTheSoundFile(Path file) throws Exception {
        FxTesting.onFx(() -> this.screen.soundFileField().setText(file.toString()));
    }

    private void givenTheUserTickedTheStartAtLogin(boolean on) throws Exception {
        FxTesting.onFx(() -> this.screen.autostartBox().setSelected(on));
    }

    private void whenTheSettingsShow() throws Exception {
        showTheSettings();
    }

    private void whenTheSettingsShowWithTheStartAtLogin() throws Exception {
        showTheSettings();
        FxTesting.await(
                "the system was asked", () -> !this.screen.autostartBox().isDisabled());
    }

    private void showTheSettings() throws Exception {
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
    }

    private void thenTheStartAtLoginBoxIsOff() {
        assertThat(this.screen.autostartBox().isDisabled()).isTrue();
        assertThat(this.screen.autostartBox().isSelected()).isFalse();
    }

    private void thenTheStartAtLoginBoxSaysWhy() {
        assertThat(this.screen.autostartBox().getTooltip().getText()).contains("installed njMule");
    }

    private void thenTheStartAtLoginBoxShows(boolean on) {
        assertThat(this.screen.autostartBox().isSelected()).isEqualTo(on);
    }

    private void thenTheSystemStartsNjMuleAtLogin(boolean on) throws Exception {
        FxTesting.assertEventually(
                "the system was changed", () -> this.system.on == on && this.system.changes.get() == 1);
    }

    private void thenTheSystemWasNotChanged() throws Exception {
        FxTesting.onFx(() -> {});
        assertThat(this.system.changes.get()).isZero();
    }

    /** A pretend start at login: the real one is never touched. */
    private static final class PretendAutostart implements Autostart {
        private volatile boolean on;
        private final AtomicInteger changes = new AtomicInteger();

        @Override
        public boolean isOn() {
            return this.on;
        }

        @Override
        public void turnOn() {
            this.on = true;
            this.changes.incrementAndGet();
        }

        @Override
        public void turnOff() {
            this.on = false;
            this.changes.incrementAndGet();
        }
    }

    private void whenTheUserApplies() throws Exception {
        FxTesting.onFx(this.screen::apply);
    }

    private void whenTheUserTestsTheSound() throws Exception {
        FxTesting.onFx(this.screen::testSound);
    }

    private void thenThePlayedSoundsAre(Path... expected) {
        assertThat(this.test.played).containsExactly(expected);
    }

    private void thenTheEngineLooksForNewVersions(UpdateMode mode, UpdateChannel channel) throws Exception {
        FxTesting.assertEventually(
                "the update settings",
                () -> this.test.engine.preferences().updateMode() == mode
                        && this.test.engine.preferences().updateChannel() == channel);
    }

    private void thenTheEngineInstalls(UpdateInstallTime when, String hours) throws Exception {
        FxTesting.assertEventually(
                "the installation settings",
                () -> this.test.engine.preferences().updateInstallTime() == when
                        && this.test
                                .engine
                                .preferences()
                                .nightHours()
                                .toString()
                                .equals(hours));
    }

    private void thenTheNightHoursCanBeSet(boolean expected) throws Exception {
        assertThat(FxTesting.onFx(() -> this.screen.nightHoursField().isDisabled()))
                .isEqualTo(!expected);
    }

    private void thenTheEngineHasNoSoundFile() {
        assertThat(this.test.engine.preferences().notificationSoundFile()).isEmpty();
    }

    private void thenTheEnginePlays(Path file) throws Exception {
        FxTesting.assertEventually(
                "saved",
                () -> this.test.engine.preferences().notificationSound()
                        && this.test
                                .engine
                                .preferences()
                                .notificationSoundFile()
                                .equals(Optional.of(file)));
    }

    private Path ding() {
        return this.dir.resolve("ding.wav").toAbsolutePath();
    }

    private void thenTheEngineRunsOn(EventCommands.Event event, EventCommands.Command expected) throws Exception {
        FxTesting.assertEventually(
                "saved",
                () -> this.test.engine.preferences().eventCommands().get(event).equals(expected));
    }

    private void thenTheEngineSavesTheNick(String nick) throws Exception {
        FxTesting.assertEventually(
                "the nick saved", () -> this.test.engine.preferences().nick().equals(nick));
    }

    private void thenTheEngineKeptTheUploadLimitSetMeanwhile() {
        assertThat(this.test.engine.preferences().maxUploadKiBps())
                .as("the limit set meanwhile")
                .isEqualTo(this.uploadLimitSetMeanwhile);
    }

    private void thenTheUserIsNotToldItWasNotSaved() {
        assertThat(this.test.told).noneMatch(m -> m.startsWith("Not saved"));
    }

    private void thenTheUserIsToldItWasNotSaved() {
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Not saved"));
    }

    // The switch applies at once; the command with its quote left open is never saved.
    private void thenTheEngineRunsNoCommandFor(EventCommands.Event event) {
        assertThat(this.test.engine.preferences().eventCommands().get(event).commandLine())
                .isEmpty();
    }
}
