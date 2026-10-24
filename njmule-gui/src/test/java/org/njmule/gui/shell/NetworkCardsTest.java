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
package org.njmule.gui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.config.Preferences;
import org.njmule.gui.FxTesting;
import org.njmule.gui.Statuses;
import org.njmule.gui.TestGui;
import org.njmule.gui.model.EngineModel;

@Timeout(60)
class NetworkCardsTest {

    private static final Preferences PORTS = Preferences.defaults().toBuilder()
            .tcpPort(4662)
            .udpPort(4672)
            .udpEnabled(true)
            .upnp(false)
            .build();

    @TempDir
    Path dir;

    private TestGui test;
    private NetworkCards cards;
    private final List<String> shown = new CopyOnWriteArrayList<>();

    @AfterEach
    void close() {
        if (this.test != null) {
            this.test.close();
        }
    }

    @Test
    void shouldSayWeAreReachableFromOutsideWithAHighId() throws Exception {
        givenTheCards();

        whenTheStateIs(EngineModel.Connection.CONNECTED, onBig(false, Optional.empty()));

        thenTheHeadlineReads("ed2k", "Reachable from outside", "High ID");
        thenTheDetailHolds("ed2k", "Big");
        thenTheEd2kButtonReads("Disconnect");
    }

    @Test
    void shouldSayWeAreNotReachableFromOutsideWithALowId() throws Exception {
        givenTheCards();

        whenTheStateIs(EngineModel.Connection.CONNECTED, onBig(true, Optional.empty()));

        thenTheHeadlineReads("ed2k", "Not reachable from outside", "Low ID");
    }

    @Test
    void shouldSayKadIsOffWhenItDoesNotRun() throws Exception {
        givenTheCards();

        whenTheStateIs(EngineModel.Connection.DISCONNECTED, Statuses.kadOnly(Optional.empty(), 4662));

        thenTheHeadlineReads("kad", "Off", "");
        thenTheKadButtonReads("Connect");
        thenTheFirewallCanBeRechecked(false);
    }

    @Test
    void shouldSayKadIsNotReachableBehindAFirewallWithItsContacts() throws Exception {
        givenTheCards();

        whenTheStateIs(
                EngineModel.Connection.DISCONNECTED,
                Statuses.kadOnly(Optional.of(Statuses.kad(true, true, "198.51.100.7")), 4662));

        thenTheHeadlineReads("kad", "Not reachable from outside", "Firewalled");
        thenTheDetailHolds("kad", "Contacts: 10");
        thenTheFirewallCanBeRechecked(true);
    }

    @Test
    void shouldShowThePortsAsTheyAreSet() throws Exception {
        givenTheCards();

        whenTheStateIs(EngineModel.Connection.DISCONNECTED, Statuses.kadOnly(Optional.empty(), 4662));

        thenTheHeadlineReads("ports", "TCP 4662 · UDP 4672", "");
        thenTheDetailHolds("ports", "UPnP: No");
    }

    @Test
    void shouldShowOurIpv6AddressAndThatFirewalledClientsAreMetThroughRouters() throws Exception {
        givenTheCards();

        whenTheStateIs(
                EngineModel.Connection.DISCONNECTED,
                Statuses.kadOnlyWithIpv6(Optional.of(Statuses.kad(true, true, "198.51.100.7")), 4662, "2001:db8::1"));

        thenTheDetailHolds("ports", "2001:db8::1");
        thenTheDetailHolds("ports", "NAT traversal: Yes");
    }

    @Test
    void shouldSayFirewalledClientsAreNotMetThroughRoutersWithoutKad() throws Exception {
        givenTheCards();

        whenTheStateIs(EngineModel.Connection.DISCONNECTED, Statuses.kadOnly(Optional.empty(), 4662));

        thenTheDetailHolds("ports", "NAT traversal: No");
    }

    private void givenTheCards() throws Exception {
        this.test = TestGui.start(this.dir.resolve("njmule"));
        this.cards = FxTesting.onFx(() -> new NetworkCards(this.test.gui, this.shown::add));
        this.test.show(this.cards.view());
    }

    private void whenTheStateIs(EngineModel.Connection connection, EngineStatus status) throws Exception {
        FxTesting.onFx(() -> this.cards.update(connection, status, "Big", PORTS));
    }

    private void thenTheHeadlineReads(String card, String text, String term) throws Exception {
        assertThat(FxTesting.onFx(() -> this.cards.headline(card).getText())).isEqualTo(text);
        assertThat(FxTesting.onFx(() -> this.cards.headline(card).getTooltip() == null
                        ? ""
                        : this.cards.headline(card).getTooltip().getText()))
                .isEqualTo(term);
    }

    private void thenTheDetailHolds(String card, String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.cards.detail(card).getText())).contains(text);
    }

    private void thenTheEd2kButtonReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.cards.ed2kAction().getText())).isEqualTo(text);
    }

    private void thenTheFirewallCanBeRechecked(boolean can) throws Exception {
        assertThat(FxTesting.onFx(() -> this.cards.recheck().isDisabled())).isEqualTo(!can);
    }

    private void thenTheKadButtonReads(String text) throws Exception {
        assertThat(FxTesting.onFx(() -> this.cards.kadAction().getText())).isEqualTo(text);
    }

    private static EngineStatus onBig(boolean lowId, Optional<KadInfo> kad) {
        return Statuses.of(
                Optional.of(Statuses.server("81.2.3.4:4661", "Big", false)),
                lowId ? 99 : 0x5A000000L,
                lowId,
                kad,
                false);
    }
}
