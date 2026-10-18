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
package org.njmule.core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Starts a program that goes on by itself: the installer, or njMule again. */
@FunctionalInterface
interface Launcher {

    /** The variable the Windows launcher sets for the desktop app; a program started from it must not inherit it. */
    String DESKTOP_VARIABLE = "NJMULE_DESKTOP";

    /** Real processes, their output appended to {@code log} or dropped. */
    Launcher SYSTEM = Launcher::system;

    Process start(List<String> command, Optional<Path> log) throws IOException;

    private static Process system(List<String> command, Optional<Path> log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove(DESKTOP_VARIABLE);
        if (log.isPresent()) {
            Files.createDirectories(UpdateFiles.parent(log.get()));
            builder.redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.get().toFile()));
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        Process process = builder.start();
        process.getOutputStream().close();
        return process;
    }
}
