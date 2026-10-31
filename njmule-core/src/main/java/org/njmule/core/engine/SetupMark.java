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
package org.njmule.core.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A configuration no one has set up yet: marked by the start that created it, until the questions of the first start
 * are answered or put aside in any interface. Kept as a file, so that an interface opened at a later start still asks.
 */
final class SetupMark {

    static final String FILE_NAME = "setup-pending";

    private SetupMark() {}

    static void make(Path config) throws IOException {
        Files.write(config.resolve(FILE_NAME), new byte[0]);
    }

    static boolean present(Path config) {
        return Files.exists(config.resolve(FILE_NAME));
    }

    static void remove(Path config) throws IOException {
        Files.deleteIfExists(config.resolve(FILE_NAME));
    }
}
