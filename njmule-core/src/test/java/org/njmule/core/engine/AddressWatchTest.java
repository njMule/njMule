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

import org.junit.jupiter.api.Test;
import org.njmule.core.util.Ipv4;

/** A change of our public address, as the server and Kad see it. */
class AddressWatchTest {

    private static final int OLD = Ipv4.of(93, 1, 1, 1);
    private static final int NEW = Ipv4.of(93, 2, 2, 2);
    private static final int OTHER = Ipv4.of(93, 3, 3, 3);

    private final AddressWatch watch = new AddressWatch();
    private AddressWatch.Reaction reaction;

    @Test
    void shouldDoNothingWhileTheServerAndKadNeverAgreed() {
        whenSeenByTheServerAndKadAt(OLD, OTHER);

        thenTheReactionIs(AddressWatch.Reaction.NONE);
    }

    @Test
    void shouldHaveKadLookAgainWhenTheServerSeesUsAtANewAddress() {
        givenBothSawUsAt(OLD);

        whenSeenByTheServerAndKadAt(NEW, OLD);

        thenTheReactionIs(AddressWatch.Reaction.RECHECK_KAD);
    }

    @Test
    void shouldReconnectToTheServerWhenKadSeesUsAtANewAddress() {
        givenBothSawUsAt(OLD);

        whenSeenByTheServerAndKadAt(OLD, NEW);

        thenTheReactionIs(AddressWatch.Reaction.RECONNECT_SERVER);
    }

    @Test
    void shouldActOnANewAddressOnce() {
        givenBothSawUsAt(OLD);
        givenSeenByTheServerAndKadAt(NEW, OLD);

        whenSeenByTheServerAndKadAt(NEW, OLD);

        thenTheReactionIs(AddressWatch.Reaction.NONE);
    }

    @Test
    void shouldDoNothingWhenKadSeesANewAddressWithoutAServer() {
        givenBothSawUsAt(OLD);

        whenSeenByTheServerAndKadAt(0, NEW);

        thenTheReactionIs(AddressWatch.Reaction.NONE);
    }

    @Test
    void shouldTakeTheNewAddressOnceBothSeeIt() {
        givenBothSawUsAt(OLD);
        givenSeenByTheServerAndKadAt(NEW, OLD);

        whenSeenByTheServerAndKadAt(NEW, NEW);

        thenTheAgreedAddressIs(NEW);
    }

    private void givenBothSawUsAt(int ip) {
        this.watch.seen(ip, ip);
    }

    private void givenSeenByTheServerAndKadAt(int serverIp, int kadIp) {
        this.watch.seen(serverIp, kadIp);
    }

    private void whenSeenByTheServerAndKadAt(int serverIp, int kadIp) {
        this.reaction = this.watch.seen(serverIp, kadIp);
    }

    private void thenTheReactionIs(AddressWatch.Reaction expected) {
        assertThat(this.reaction).isEqualTo(expected);
    }

    private void thenTheAgreedAddressIs(int ip) {
        assertThat(this.watch.agreed()).isEqualTo(ip);
    }
}
