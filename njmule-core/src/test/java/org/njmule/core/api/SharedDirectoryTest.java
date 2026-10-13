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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SharedDirectoryTest {

    @Test
    void rootsSystemDirectoriesAndPersonalFoldersAreSensitive() {
        Path home = Path.of(System.getProperty("user.home"));

        assertThat(SharedDirectory.isSensitive(home.getRoot())).isTrue();
        assertThat(SharedDirectory.isSensitive(home.resolve("Documents"))).isTrue();
        assertThat(SharedDirectory.isSensitive(home.resolve("Desktop").resolve("photos")))
                .isTrue();
        assertThat(SharedDirectory.isSensitive(home.resolve("Music"))).isFalse();
        String windows = System.getenv("SystemRoot");
        if (windows != null) {
            assertThat(SharedDirectory.isSensitive(Path.of(windows, "System32")))
                    .isTrue();
        } else {
            assertThat(SharedDirectory.isSensitive(Path.of("/etc"))).isTrue();
        }
    }
}
