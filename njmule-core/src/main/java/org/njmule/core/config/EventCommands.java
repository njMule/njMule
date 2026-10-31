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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The programs the user runs when something happens: a download completes or fails, a chat message arrives, the temp
 * disk runs short of space. No shell runs them: {@link #arguments(String)} splits a command line into the program and
 * its arguments, and the placeholders of each event are filled in inside the arguments.
 */
public record EventCommands(
        Command downloadCompleted, Command downloadFailed, Command chatMessage, Command diskSpaceShort) {

    /** Characters of a command line at most. */
    public static final int MAX_COMMAND_LINE = 4096;

    /** Nothing runs on any event. */
    public static final EventCommands NONE = new EventCommands(Command.NONE, Command.NONE, Command.NONE, Command.NONE);

    /** What a command can run on. */
    public enum Event {
        DOWNLOAD_COMPLETED("downloadCompleted"),
        DOWNLOAD_FAILED("downloadFailed"),
        CHAT_MESSAGE("chatMessage"),
        DISK_SPACE_SHORT("diskSpaceShort");

        private final String id;

        Event(String id) {
            this.id = id;
        }

        /** A short name in lowerCamelCase, for files and texts. */
        public String id() {
            return this.id;
        }
    }

    /** One event's command: whether it runs, and its command line. */
    public record Command(boolean enabled, String commandLine) {

        public static final Command NONE = new Command(false, "");

        public Command {
            commandLine = Objects.requireNonNull(commandLine, "commandLine").strip();
            if (commandLine.length() > MAX_COMMAND_LINE) {
                throw new IllegalArgumentException(
                        "a command line must be at most " + MAX_COMMAND_LINE + " characters");
            }
            if (commandLine.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("a command line must be one line of printable characters");
            }
        }
    }

    public EventCommands {
        Objects.requireNonNull(downloadCompleted, "downloadCompleted");
        Objects.requireNonNull(downloadFailed, "downloadFailed");
        Objects.requireNonNull(chatMessage, "chatMessage");
        Objects.requireNonNull(diskSpaceShort, "diskSpaceShort");
    }

    public Command get(Event event) {
        return switch (event) {
            case DOWNLOAD_COMPLETED -> this.downloadCompleted;
            case DOWNLOAD_FAILED -> this.downloadFailed;
            case CHAT_MESSAGE -> this.chatMessage;
            case DISK_SPACE_SHORT -> this.diskSpaceShort;
        };
    }

    /** These commands with {@code command} for {@code event}. */
    public EventCommands with(Event event, Command command) {
        return new EventCommands(
                event == Event.DOWNLOAD_COMPLETED ? command : this.downloadCompleted,
                event == Event.DOWNLOAD_FAILED ? command : this.downloadFailed,
                event == Event.CHAT_MESSAGE ? command : this.chatMessage,
                event == Event.DISK_SPACE_SHORT ? command : this.diskSpaceShort);
    }

    /**
     * The program and its arguments in {@code commandLine}: they are apart by spaces; double or single quotes keep
     * spaces in one, and two quotes of the same kind inside them stand for one. Backslashes are plain characters, as in
     * Windows paths. Throws {@link IllegalArgumentException} for a quote left open.
     */
    // The JDK's StreamTokenizer reads backslashes in quotes as escapes, which would spoil Windows paths.
    public static List<String> arguments(String commandLine) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        char quote = 0;
        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (quote != 0) {
                if (c != quote) {
                    current.append(c);
                } else if (i + 1 < commandLine.length() && commandLine.charAt(i + 1) == quote) {
                    current.append(c);
                    i++;
                } else {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inArgument = true;
            } else if (c == ' ' || c == '\t') {
                if (inArgument) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
            } else {
                current.append(c);
                inArgument = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("a quote is not closed: " + quote);
        }
        if (inArgument) {
            arguments.add(current.toString());
        }
        return List.copyOf(arguments);
    }
}
