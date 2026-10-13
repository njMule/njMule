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
package org.njmule.gui.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.ServerInfo;

class ServersFilterTest {

    private static final ServerInfo SUNRISE = new ServerInfo(
            "176.123.5.89:4725",
            "eMule Sunrise",
            "Sunrise server",
            0,
            0,
            0,
            0,
            ServerInfo.Priority.NORMAL,
            40,
            0,
            0,
            0,
            "17.15 (lugdunum)",
            true,
            false);

    @Test
    void everyWordMustMatchSomewhereInAnyCase() {
        assertThat(ServersScreen.matching("")).accepts(SUNRISE);
        assertThat(ServersScreen.matching("  ")).accepts(SUNRISE);
        assertThat(ServersScreen.matching("SUNRISE")).accepts(SUNRISE);
        assertThat(ServersScreen.matching("sunrise 176.123")).accepts(SUNRISE);
        assertThat(ServersScreen.matching("lugdunum")).as("the version").accepts(SUNRISE);
        Predicate<ServerInfo> other = ServersScreen.matching("sunrise donkey");
        assertThat(other).rejects(SUNRISE);
    }

    @Test
    void kadShowsOurAddressAsTheNumberItsIdIsMadeOf() {
        assertThat(MyInfoPane.number("1.2.3.4")).isEqualTo("16909060");
        assertThat(MyInfoPane.number("255.255.255.255")).isEqualTo("4294967295");
    }
}
