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
package org.njmule.gui.infra;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Shows a download that is not complete yet with the video player the user chose. */
public final class Preview {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Preview() {}

    /** Starts {@code player} with its {@code args} and the {@code data} file; the player runs on its own. */
    public static void start(String player, String args, Path data) throws IOException {
        process(player, args, data).start();
    }

    /** Whether {@code player} names a program. */
    public static boolean isSet(String player) {
        return !program(player).isEmpty();
    }

    // Nobody reads what the player writes: a full pipe would stop a verbose one.
    static ProcessBuilder process(String player, String args, Path data) {
        return new ProcessBuilder(command(player, args, data))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    // A path copied from a file manager may come in quotes, which no system takes as part of a program's name.
    static String program(String player) {
        String program = player.strip();
        for (String quote : List.of("\"", "'")) {
            if (program.length() >= 2 && program.startsWith(quote) && program.endsWith(quote)) {
                return program.substring(1, program.length() - 1).strip();
            }
        }
        return program;
    }

    // The player, its arguments apart by spaces, then the file: each one argument, never parsed by a shell.
    static List<String> command(String player, String args, Path data) {
        List<String> command = new ArrayList<>();
        command.add(program(player));
        WHITESPACE.splitAsStream(args.strip()).filter(a -> !a.isEmpty()).forEach(command::add);
        command.add(data.toAbsolutePath().toString());
        return command;
    }
}
