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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmulePortsTest {

    @TempDir
    Path dir;

    private Optional<EmulePorts> ports;

    @Test
    void theClientPortsOfAProfileAreRead() throws Exception {
        this.givenThePreferences("[eMule]\r\nNick=someone\r\nPort=4662\r\nUDPPort=4672\r\n");
        this.whenThePortsAreRead();
        this.thenThePortsAre(4662, OptionalInt.of(4672));
    }

    @Test
    void aUdpPortOfZeroMeansUdpIsOff() throws Exception {
        this.givenThePreferences("[eMule]\r\nPort=5000\r\nUDPPort=0\r\n");
        this.whenThePortsAreRead();
        this.thenThePortsAre(5000, OptionalInt.empty());
    }

    @Test
    void aProfileWithoutPreferencesHasNoPorts() {
        this.whenThePortsAreRead();
        this.thenThereAreNoPorts();
    }

    @Test
    void aTcpPortOutOfRangeIsNoPort() throws Exception {
        this.givenThePreferences("[eMule]\r\nPort=70000\r\n");
        this.whenThePortsAreRead();
        this.thenThereAreNoPorts();
    }

    @Test
    void thePortsOfOtherSectionsAreLeftAlone() throws Exception {
        this.givenThePreferences("[WebServer]\r\nPort=4711\r\n[eMule]\r\nPort=4663\r\n");
        this.whenThePortsAreRead();
        this.thenThePortsAre(4663, OptionalInt.empty());
    }

    private void givenThePreferences(String text) throws Exception {
        Files.writeString(this.dir.resolve("preferences.ini"), text, StandardCharsets.UTF_8);
    }

    private void whenThePortsAreRead() {
        this.ports = EmulePorts.read(this.dir);
    }

    private void thenThePortsAre(int tcp, OptionalInt udp) {
        assertThat(this.ports).contains(new EmulePorts(tcp, udp));
    }

    private void thenThereAreNoPorts() {
        assertThat(this.ports).isEmpty();
    }
}
