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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.util.Ipv4;

class DefaultListsTest {

    private List<ServerMet.Server> servers;
    private NodesDat.Contents nodes;

    @Test
    void theShippedServersAreServersOnPublicAddresses() {
        this.whenTheShippedServersAreRead();
        this.thenThereAreServers();
        this.thenEveryServerHasAPublicAddressAndAPort();
    }

    @Test
    void theShippedContactsAreOnlyToBootstrapFrom() {
        this.whenTheShippedContactsAreRead();
        this.thenTheyAreOnlyToBootstrapFrom();
        this.thenEveryContactHasAPublicAddressAndAPort();
    }

    private void whenTheShippedServersAreRead() {
        this.servers = DefaultLists.servers();
    }

    private void whenTheShippedContactsAreRead() {
        this.nodes = DefaultLists.nodes();
    }

    private void thenThereAreServers() {
        assertThat(this.servers).hasSizeGreaterThanOrEqualTo(5);
    }

    private void thenEveryServerHasAPublicAddressAndAPort() {
        assertThat(this.servers).allSatisfy(s -> {
            assertThat(Ipv4.isUnicastPublicCandidate(s.ip()) && !Ipv4.isLan(s.ip()))
                    .as(Ipv4.format(s.ip()))
                    .isTrue();
            assertThat(s.port()).isPositive();
        });
    }

    private void thenTheyAreOnlyToBootstrapFrom() {
        assertThat(this.nodes.bootstrap()).isTrue();
        assertThat(this.nodes.contacts()).hasSizeGreaterThanOrEqualTo(50);
    }

    private void thenEveryContactHasAPublicAddressAndAPort() {
        assertThat(this.nodes.contacts()).allSatisfy(c -> {
            assertThat(Ipv4.isUnicastPublicCandidate(c.ip()) && !Ipv4.isLan(c.ip()))
                    .as(Ipv4.format(c.ip()))
                    .isTrue();
            assertThat(c.udpPort()).isPositive();
        });
    }
}
