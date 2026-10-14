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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EventCommandsTest {

    private String commandLine;
    private List<String> arguments;
    private EventCommands commands;
    private RuntimeException exception;

    @Test
    void shouldSplitACommandLineAtSpaces() {
        this.givenTheCommandLine("notify-send  njMule\t%NAME");

        this.whenTheCommandLineIsSplit();

        this.thenTheArgumentsAre("notify-send", "njMule", "%NAME");
    }

    @Test
    void shouldKeepSpacesAndBackslashesInsideDoubleQuotes() {
        this.givenTheCommandLine("\"C:\\Program Files\\Tools\\tell.exe\" --title \"Download done\"");

        this.whenTheCommandLineIsSplit();

        this.thenTheArgumentsAre("C:\\Program Files\\Tools\\tell.exe", "--title", "Download done");
    }

    @Test
    void shouldReadSingleQuotesAndDoubledQuotes() {
        this.givenTheCommandLine("echo 'it''s' \"say \"\"hi\"\"\" a\"b c\"d \"\"");

        this.whenTheCommandLineIsSplit();

        this.thenTheArgumentsAre("echo", "it's", "say \"hi\"", "ab cd", "");
    }

    @Test
    void shouldRefuseAQuoteLeftOpen() {
        this.givenTheCommandLine("player \"my file");

        this.whenTheCommandLineIsSplit();

        this.thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldGiveNoArgumentsForAnEmptyCommandLine() {
        this.givenTheCommandLine("   ");

        this.whenTheCommandLineIsSplit();

        this.thenTheArgumentsAre();
    }

    @Test
    void shouldRefuseACommandLineOfSeveralLines() {
        this.whenACommandIsMadeOf("first\nsecond");

        this.thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldReplaceTheCommandOfOneEventOnly() {
        this.givenNoCommands();

        this.whenTheCommandOfAnEventIsSet(EventCommands.Event.CHAT_MESSAGE, new EventCommands.Command(true, "beep"));

        this.thenTheCommandOfIs(EventCommands.Event.CHAT_MESSAGE, new EventCommands.Command(true, "beep"));
        this.thenTheCommandOfIs(EventCommands.Event.DOWNLOAD_COMPLETED, EventCommands.Command.NONE);
        this.thenTheCommandOfIs(EventCommands.Event.DOWNLOAD_FAILED, EventCommands.Command.NONE);
        this.thenTheCommandOfIs(EventCommands.Event.DISK_SPACE_SHORT, EventCommands.Command.NONE);
    }

    private void givenTheCommandLine(String line) {
        this.commandLine = line;
    }

    private void givenNoCommands() {
        this.commands = EventCommands.NONE;
    }

    private void whenTheCommandLineIsSplit() {
        try {
            this.arguments = EventCommands.arguments(this.commandLine);
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void whenACommandIsMadeOf(String line) {
        try {
            new EventCommands.Command(true, line);
        } catch (RuntimeException e) {
            this.exception = e;
        }
    }

    private void whenTheCommandOfAnEventIsSet(EventCommands.Event event, EventCommands.Command command) {
        this.commands = this.commands.with(event, command);
    }

    private void thenTheArgumentsAre(String... expected) {
        assertThat(this.exception).isNull();
        assertThat(this.arguments).containsExactly(expected);
    }

    private void thenExceptionOccurred(Class<? extends RuntimeException> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenTheCommandOfIs(EventCommands.Event event, EventCommands.Command expected) {
        assertThat(this.commands.get(event)).isEqualTo(expected);
    }
}
