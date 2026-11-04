/*
 * njMule - New Jack Mule
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
package org.njmule.testkit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/**
 * The engine a flow tests: this JVM's, or with {@code -Pnjmule.engine=<path>} the njmule executable at that path,
 * started on the same profile and driven only through its API.
 */
final class Engines {

    private static final String EXECUTABLE = System.getProperty("njmule.engine", "");

    private Engines() {}

    static boolean inThisJvm() {
        return EXECUTABLE.isEmpty();
    }

    static NjMuleEngine start(AppDirectories dirs) throws IOException {
        return inThisJvm() ? NjMuleEngine.start(new EngineConfig(dirs)) : ApiEngine.start(Path.of(EXECUTABLE), dirs);
    }

    /** A start of the njmule executable with {@code arguments}; only out of this JVM. */
    static ProcessBuilder command(String... arguments) throws IOException {
        return ApiEngine.command(Path.of(EXECUTABLE), List.of(arguments));
    }
}
