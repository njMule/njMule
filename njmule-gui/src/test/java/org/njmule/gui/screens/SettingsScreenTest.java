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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.config.EventCommands;
import org.njmule.gui.FxTesting;
import org.njmule.gui.TestGui;

@Timeout(120)
class SettingsScreenTest {

    @TempDir
    Path dir;

    private TestGui test;
    private SettingsScreen screen;

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void applyWritesOnlyWhatTheUserChanged() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.onFx(screen::shown);
        int before = this.test.engine.preferences().maxUploadKiBps();

        // The tray menu or the scheduler changes a limit while the settings show.
        this.test
                .engine
                .updatePreferences(
                        p -> p.toBuilder().maxUploadKiBps(before + 77).build())
                .get(10, TimeUnit.SECONDS);
        FxTesting.onFx(() -> {
            screen.nickField().setText("Other");
            screen.apply();
        });
        FxTesting.await("saved", () -> this.test.told.contains("Settings saved."));

        assertThat(this.test.engine.preferences().nick()).isEqualTo("Other");
        assertThat(this.test.engine.preferences().maxUploadKiBps())
                .as("the limit set meanwhile")
                .isEqualTo(before + 77);

        // A second apply starts from what the first one wrote.
        FxTesting.onFx(() -> {
            screen.nickField().setText("Third");
            screen.apply();
        });
        FxTesting.await(
                "saved again", () -> this.test.engine.preferences().nick().equals("Third"));
        assertThat(this.test.engine.preferences().maxUploadKiBps()).isEqualTo(before + 77);
    }

    @Test
    void aNickTakesNoTextDirectionControls() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        SettingsScreen screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(screen::view));
        FxTesting.onFx(screen::shown);
        // Embeddings, overrides and isolates would show another name than the one sent, which the engine refuses.
        StringBuilder nick = new StringBuilder("ann");
        for (char c = 0x202A; c <= 0x202E; c++) {
            nick.append(c).append('x');
        }
        for (char c = 0x2066; c <= 0x2069; c++) {
            nick.append(c).append('y');
        }

        String typed = FxTesting.onFx(() -> {
            screen.nickField().setText(nick.toString());
            return screen.nickField().getText();
        });
        assertThat(typed).isEqualTo("annxxxxxyyyy");
        FxTesting.onFx(screen::apply);
        FxTesting.await("saved", () -> this.test.engine.preferences().nick().equals("annxxxxxyyyy"));
        assertThat(this.test.told).noneMatch(m -> m.startsWith("Not saved"));
    }

    @Test
    void shouldSaveTheEventCommandTheUserTyped() throws Exception {
        this.givenTheSettingsShow();

        this.whenTheUserTurnsOnTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, "notify \"%NAME done\"");
        this.whenTheUserApplies();

        this.thenTheEngineRunsOn(
                EventCommands.Event.DOWNLOAD_COMPLETED, new EventCommands.Command(true, "notify \"%NAME done\""));
    }

    @Test
    void shouldNotSaveACommandWithAQuoteLeftOpen() throws Exception {
        this.givenTheSettingsShow();

        this.whenTheUserTurnsOnTheCommand(EventCommands.Event.CHAT_MESSAGE, "say \"%MESSAGE");
        this.whenTheUserApplies();

        this.thenTheUserIsToldItWasNotSaved();
        this.thenTheEngineHasNoCommandFor(EventCommands.Event.CHAT_MESSAGE);
    }

    private void givenTheSettingsShow() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.screen = new SettingsScreen(this.test.gui);
        this.test.show(FxTesting.onFx(this.screen::view));
        FxTesting.onFx(this.screen::shown);
    }

    private void whenTheUserTurnsOnTheCommand(EventCommands.Event event, String commandLine) throws Exception {
        FxTesting.onFx(() -> {
            this.screen.eventSwitch(event).setSelected(true);
            this.screen.eventField(event).setText(commandLine);
        });
    }

    private void whenTheUserApplies() throws Exception {
        FxTesting.onFx(this.screen::apply);
    }

    private void thenTheEngineRunsOn(EventCommands.Event event, EventCommands.Command expected) throws Exception {
        FxTesting.await(
                "saved",
                () -> this.test.engine.preferences().eventCommands().get(event).equals(expected));
    }

    private void thenTheUserIsToldItWasNotSaved() {
        assertThat(this.test.told).anyMatch(m -> m.startsWith("Not saved"));
    }

    private void thenTheEngineHasNoCommandFor(EventCommands.Event event) {
        assertThat(this.test.engine.preferences().eventCommands().get(event)).isEqualTo(EventCommands.Command.NONE);
    }
}
