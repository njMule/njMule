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

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.config.Preferences;
import org.njmule.core.persist.format.IniFile;

class EmuleBindAddressImportTest {

    private Preferences imported;

    @ParameterizedTest(name = "BindAddr={0} binds to [{1}]")
    @CsvSource({"10.8.0.2, 10.8.0.2", "' 10.8.0.2 ', 10.8.0.2", "0.0.0.0, ''", "'', ''", "vpn, ''", "10.8.0, ''"})
    void shouldTakeTheAddressEmuleBindsTo(String bindAddr, String expected) {
        whenEmuleSaysItBindsTo(bindAddr);
        thenNjMuleBindsTo(expected);
    }

    private void whenEmuleSaysItBindsTo(String bindAddr) {
        IniFile ini = IniFile.parse(("[eMule]\nBindAddr=" + bindAddr + "\n").getBytes(StandardCharsets.UTF_8));
        this.imported = EmuleImporter.mapPreferences(ini, Preferences.defaults());
    }

    private void thenNjMuleBindsTo(String expected) {
        assertThat(this.imported.bindInterface()).isEqualTo(expected);
    }
}
