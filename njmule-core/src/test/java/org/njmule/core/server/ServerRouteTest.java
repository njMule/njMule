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
package org.njmule.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class ServerRouteTest {

    private static final int SERVER_IP = Ipv4.of(81, 2, 3, 4);
    private static final int OUR_IP = Ipv4.of(93, 40, 1, 2);
    private static final int KEY = 0x5EC2_E701;

    private final List<Tag> tags = new ArrayList<>();
    private Optional<ServerRoute> route;

    @Test
    void shouldAskAServerObfuscatedOnItsOwnPortWhenItGaveUsAKey() {
        givenAServerTakingObfuscatedDatagramsOnPort(4665);
        givenItsKeyWasGivenFor(OUR_IP);
        whenRoutedWith(ObfuscationMode.REQUESTED);
        thenTheDatagramGoesObfuscatedTo(4665);
    }

    @Test
    void shouldNotUseAKeyGivenForAnotherAddressOfOurs() {
        givenAServerTakingObfuscatedDatagramsOnPort(4665);
        givenItsKeyWasGivenFor(Ipv4.of(93, 40, 9, 9));
        whenRoutedWith(ObfuscationMode.REQUESTED);
        thenTheDatagramGoesPlainToTheTcpPortPlusFour();
    }

    @Test
    void shouldAskAServerPlainWithObfuscationOff() {
        givenAServerTakingObfuscatedDatagramsOnPort(4665);
        givenItsKeyWasGivenFor(OUR_IP);
        whenRoutedWith(ObfuscationMode.DISABLED);
        thenTheDatagramGoesPlainToTheTcpPortPlusFour();
    }

    @Test
    void shouldNotAskAServerWithoutObfuscationWhenItIsRequired() {
        whenRoutedWith(ObfuscationMode.REQUIRED);
        thenNothingIsSent();
    }

    private void givenAServerTakingObfuscatedDatagramsOnPort(int port) {
        this.tags.add(intTag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_OBFUSCATION));
        this.tags.add(intTag(ServerTag.UDPPORTOBFUSCATION, port));
    }

    private void givenItsKeyWasGivenFor(int ourIp) {
        this.tags.add(intTag(ServerTag.UDPKEY, Integer.toUnsignedLong(KEY)));
        this.tags.add(intTag(ServerTag.UDPKEYIP, Ipv4.toEd2k(ourIp)));
    }

    private void whenRoutedWith(ObfuscationMode mode) {
        this.route = ServerRoute.of(new ServerMet.Server(SERVER_IP, 4661, this.tags), OUR_IP, mode);
    }

    private void thenTheDatagramGoesObfuscatedTo(int port) {
        assertThat(this.route).hasValueSatisfying(r -> {
            assertThat(r.key()).isEqualTo(KEY);
            assertThat(r.target().getPort()).isEqualTo(port);
            assertThat(r.target().getHostString()).isEqualTo("81.2.3.4");
        });
    }

    private void thenTheDatagramGoesPlainToTheTcpPortPlusFour() {
        assertThat(this.route).hasValueSatisfying(r -> {
            assertThat(r.isObfuscated()).isFalse();
            assertThat(r.target().getPort()).isEqualTo(4661 + ServerUdp.PORT_OFFSET);
        });
    }

    private void thenNothingIsSent() {
        assertThat(this.route).isEmpty();
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }
}
