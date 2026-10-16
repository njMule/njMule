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
package org.njmule.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** The name the system lists the desktop app under on Linux. */
@EnabledOnOs(OS.LINUX)
class LinuxProcessNameTest {

    private boolean named;

    @Test
    void shouldGiveTheCallingThreadTheApplicationsName() throws IOException {
        whenTheThreadIsNamed();

        thenTheSystemListsTheThreadAs("njMule");
    }

    @Test
    void shouldLeaveAJvmItsOwnName() {
        whenItIsAskedWhetherToNameTheProcess();

        thenTheProcessIsNotNamed();
    }

    private void whenTheThreadIsNamed() {
        this.named = LinuxProcessName.nameThisThread();
    }

    private void whenItIsAskedWhetherToNameTheProcess() {
        this.named = LinuxProcessName.applies();
    }

    // The steps run on the thread named.
    private void thenTheSystemListsTheThreadAs(String name) throws IOException {
        assertThat(this.named).isTrue();
        assertThat(Files.readString(Path.of("/proc/thread-self/comm")).strip()).isEqualTo(name);
    }

    private void thenTheProcessIsNotNamed() {
        assertThat(this.named).isFalse();
    }
}
