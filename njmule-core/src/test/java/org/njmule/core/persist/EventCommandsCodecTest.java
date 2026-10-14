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
package org.njmule.core.persist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.Preferences;

class EventCommandsCodecTest {

    private Preferences preferences = Preferences.defaults();
    private String text;
    private Preferences loaded;

    @Test
    void shouldKeepEveryEventCommandThroughTheFile() throws IOException {
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_COMPLETED, true, "\"C:\\Tools\\done.exe\" %FILE");
        this.givenTheCommand(EventCommands.Event.DOWNLOAD_FAILED, false, "alert = %NAME: %MESSAGE");
        this.givenTheCommand(EventCommands.Event.CHAT_MESSAGE, true, "notify-send '%SENDER' #%MESSAGE");
        this.givenTheCommand(EventCommands.Event.DISK_SPACE_SHORT, true, "warn %FILE");

        this.whenThePreferencesAreSavedAndLoaded();

        this.thenTheLoadedPreferencesAreTheSaved();
        this.thenTheFileHasTheLine("events.chatMessage.enabled=true");
    }

    @Test
    void shouldReadAHandEditedSwitchAfterItsCommandLine() throws IOException {
        this.givenTheFile("events.diskSpaceShort.command=beep\nevents.diskSpaceShort.enabled=on\n");

        this.whenTheFileIsLoaded();

        this.thenTheCommandOfIs(EventCommands.Event.DISK_SPACE_SHORT, new EventCommands.Command(true, "beep"));
    }

    @Test
    void shouldKeepTheDefaultForACommandLineOfSeveralLines() throws IOException {
        this.givenTheFile("events.downloadCompleted.command=first\\nsecond\nevents.downloadCompleted.enabled=true\n");

        this.whenTheFileIsLoaded();

        this.thenTheCommandOfIs(EventCommands.Event.DOWNLOAD_COMPLETED, new EventCommands.Command(true, ""));
    }

    private void givenTheCommand(EventCommands.Event event, boolean enabled, String commandLine) {
        this.preferences = this.preferences.toBuilder()
                .eventEnabled(event, enabled)
                .eventCommandLine(event, commandLine)
                .build();
    }

    private void givenTheFile(String contents) {
        this.text = contents;
    }

    private void whenThePreferencesAreSavedAndLoaded() throws IOException {
        StringWriter file = new StringWriter();
        PreferencesCodec.format(this.preferences, file);
        this.text = file.toString();
        this.whenTheFileIsLoaded();
    }

    private void whenTheFileIsLoaded() throws IOException {
        this.loaded = PreferencesCodec.parse(new StringReader(this.text));
    }

    private void thenTheLoadedPreferencesAreTheSaved() {
        assertThat(this.loaded).isEqualTo(this.preferences);
    }

    private void thenTheFileHasTheLine(String line) {
        assertThat(this.text.lines()).contains(line);
    }

    private void thenTheCommandOfIs(EventCommands.Event event, EventCommands.Command expected) {
        assertThat(this.loaded.eventCommands().get(event)).isEqualTo(expected);
    }
}
