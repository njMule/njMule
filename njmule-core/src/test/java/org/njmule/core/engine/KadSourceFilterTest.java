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

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.ed2k.NatTraversalMessages;

class KadSourceFilterTest {

    private static final Hash16 USER = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    // 198.51.100.9 and 203.0.113.7: documentation addresses (RFC 5737).
    private static final KadNode.Buddy BUDDY =
            new KadNode.Buddy(0xC6336409, 4672, UInt128.of(Hash16.parseHex("fedcba9876543210fedcba9876543210")));
    private static final int SOURCE_IP = 0xCB007107;
    private static final int UTP = NatTraversalMessages.OPTION_UTP;

    private boolean callable = true;
    private boolean traversing;
    private KadNode.Source source;
    private boolean kept;

    @Test
    void shouldKeepAFirewalledSourceMetThroughTheRouters() {
        givenWeCannotBeCalledBack();
        givenWeMeetSourcesThroughTheRouters();
        givenAFirewalledSourceWithABuddy(UTP);

        whenTheSourceIsChecked();

        thenTheSourceIsKept(true);
    }

    @Test
    void shouldDropAFirewalledSourceWithoutUtpWhileWeCannotBeCalledBack() {
        givenWeCannotBeCalledBack();
        givenWeMeetSourcesThroughTheRouters();
        givenAFirewalledSourceWithABuddy(0);

        whenTheSourceIsChecked();

        thenTheSourceIsKept(false);
    }

    // Without a buddy there is no one to pass the rendezvous on.
    @Test
    void shouldDropASourceTakingDirectCallbacksWhileWeCannotBeCalledBack() {
        givenWeCannotBeCalledBack();
        givenWeMeetSourcesThroughTheRouters();
        givenASourceTakingDirectCallbacks(UTP);

        whenTheSourceIsChecked();

        thenTheSourceIsKept(false);
    }

    @Test
    void shouldDropAFirewalledSourceWhileWeNeitherAreCalledBackNorTraverse() {
        givenWeCannotBeCalledBack();
        givenAFirewalledSourceWithABuddy(UTP);

        whenTheSourceIsChecked();

        thenTheSourceIsKept(false);
    }

    @Test
    void shouldKeepAFirewalledSourceWhileWeCanBeCalledBack() {
        givenAFirewalledSourceWithABuddy(0);

        whenTheSourceIsChecked();

        thenTheSourceIsKept(true);
    }

    @Test
    void shouldKeepAnOpenSourceWhileWeCannotBeCalledBack() {
        givenWeCannotBeCalledBack();
        givenAnOpenSource();

        whenTheSourceIsChecked();

        thenTheSourceIsKept(true);
    }

    private void givenWeCannotBeCalledBack() {
        this.callable = false;
    }

    private void givenWeMeetSourcesThroughTheRouters() {
        this.traversing = true;
    }

    private void givenAFirewalledSourceWithABuddy(int options) {
        this.source = new KadNode.Source(USER, SOURCE_IP, 4662, options, 4672, Optional.of(BUDDY), false);
    }

    private void givenASourceTakingDirectCallbacks(int options) {
        this.source = new KadNode.Source(USER, SOURCE_IP, 4662, options, 4672, Optional.empty(), true);
    }

    private void givenAnOpenSource() {
        this.source = new KadNode.Source(USER, SOURCE_IP, 4662, 0);
    }

    private void whenTheSourceIsChecked() {
        this.kept = Network.keepsKadSource(this.source, this.callable, this.traversing);
    }

    private void thenTheSourceIsKept(boolean expected) {
        assertThat(this.kept).isEqualTo(expected);
    }
}
