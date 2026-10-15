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

import java.net.Inet4Address;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.util.Ipv4;

/** The public address the secure identification binds signatures to and checks them with. */
class PublicAddressTest {

    private static final int OURS = Ipv4.parse("198.51.100.7");
    private static final int SEEN_BY_KAD = Ipv4.parse("198.51.100.8");

    private Optional<ServerMessages.IdChange> server = Optional.empty();
    private int kad;
    private Optional<Inet4Address> address;

    @Test
    void aHighIdIsOurAddress() {
        givenAServerThatGaveUs(new ServerMessages.IdChange(Ipv4.toEd2k(OURS), 0, 0));

        whenOurPublicAddressIsWanted();

        thenItIs("198.51.100.7");
    }

    @Test
    void withALowIdOurAddressIsTheOneTheServerSaw() {
        givenAServerThatGaveUs(new ServerMessages.IdChange(1234, 0, OURS));
        givenKadSawUsAt(SEEN_BY_KAD);

        whenOurPublicAddressIsWanted();

        thenItIs("198.51.100.7");
    }

    @Test
    void withoutAServerKadTellsOurAddress() {
        givenKadSawUsAt(SEEN_BY_KAD);

        whenOurPublicAddressIsWanted();

        thenItIs("198.51.100.8");
    }

    @Test
    void aLowIdFromAServerThatDoesNotSayOurAddressLeavesItUnknown() {
        givenAServerThatGaveUs(new ServerMessages.IdChange(1234, 0, 0));

        whenOurPublicAddressIsWanted();

        thenItIsUnknown();
    }

    private void givenAServerThatGaveUs(ServerMessages.IdChange id) {
        this.server = Optional.of(id);
    }

    private void givenKadSawUsAt(int ip) {
        this.kad = ip;
    }

    private void whenOurPublicAddressIsWanted() {
        this.address = Network.publicAddress(this.server, this.kad);
    }

    private void thenItIs(String expected) {
        assertThat(this.address).map(Inet4Address::getHostAddress).contains(expected);
    }

    private void thenItIsUnknown() {
        assertThat(this.address).isEmpty();
    }
}
