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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Starts the desktop program when the user logs in. Whether it does is always read from the system, so that it stays
 * right when the user turned it off elsewhere.
 */
public interface Autostart {

    /** Whether the system starts this program at login now. */
    boolean isOn() throws IOException;

    /** Makes the system start this program at login. */
    void turnOn() throws IOException;

    /** Stops the system from starting this program at login. */
    void turnOff() throws IOException;

    /**
     * The start at login for this system, for the installed desktop program with {@code arguments}; empty when running
     * from a development build, through a {@code java} launcher.
     *
     * @param configDir where the work files of the system tools go for a moment
     */
    static Optional<Autostart> forThisSystem(Path configDir, List<String> arguments) {
        String os = System.getProperty("os.name", "");
        return ProcessHandle.current()
                .info()
                .command()
                .map(command -> LinkAssociation.desktopProgram(command, os, Files::isRegularFile))
                .flatMap(command -> SystemAutostart.forProgram(
                        configDir,
                        LinkAssociation::run,
                        command,
                        arguments,
                        os,
                        System.getenv("XDG_CONFIG_HOME"),
                        System.getProperty("user.home")));
    }
}
