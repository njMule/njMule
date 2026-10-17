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
package org.njmule.core.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.config.ProxySettings;

@Timeout(30)
class RelayKeeperTest {

    private static final Duration SHORT_WAIT = Duration.ofMillis(100);

    private final List<UdpRoute> routes = new CopyOnWriteArrayList<>();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private FakeSocks5Proxy proxy;
    private RelayKeeper keeper;

    @AfterEach
    void close() throws IOException {
        if (this.keeper != null) {
            this.keeper.close();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
    }

    @Test
    void shouldSendStraightWithoutAProxy() {
        whenKeptFor(ProxySettings.NONE);

        thenTheRouteIs("direct");
        thenNothingIsLogged();
    }

    @Test
    void shouldSendStraightAndSaySoWithAnHttpProxy() {
        whenKeptFor(new ProxySettings(ProxySettings.Type.HTTP, "127.0.0.1", 3128, "", ""));

        thenTheRouteIs("direct");
        thenTheLogSays("WARNING Test UDP goes straight to the network: an HTTP proxy carries TCP only");
    }

    @Test
    void shouldGoThroughTheRelayOfASocksProxy() throws Exception {
        givenAProxy(FakeSocks5Proxy.start());

        whenKeptFor(this.proxy.settings());

        thenTheFirstRouteIs("held");
        thenTheRouteBecomesRelayed();
        thenTheLogSaysSomethingStartingWith("INFO Test UDP goes through the UDP relay of the SOCKS5 proxy");
    }

    @Test
    void shouldSendStraightAndSaySoWithASocksProxyThatRelaysNoUdp() throws Exception {
        givenAProxy(FakeSocks5Proxy.refusingUdp());

        whenKeptFor(this.proxy.settings());

        thenTheRouteBecomes("direct");
        thenTheLogSaysSomethingStartingWith("WARNING Test UDP goes straight to the network: the proxy relays no UDP");
    }

    @Test
    void shouldHoldWhileTheProxyCannotBeReached() throws Exception {
        int closedPort;
        try (ServerSocket gone = new ServerSocket(0)) {
            closedPort = gone.getLocalPort();
        }

        whenKeptFor(new ProxySettings(ProxySettings.Type.SOCKS, "127.0.0.1", closedPort, "", ""));

        thenTheLogSaysSomethingStartingWith("WARNING Test UDP is held until the SOCKS5 proxy answers");
        thenTheRouteIs("held");
    }

    @Test
    void shouldAskForAnotherRelayWhenTheProxyEndsItsOwn() throws Exception {
        givenAProxy(FakeSocks5Proxy.start());
        givenKeptThroughTheRelay();

        whenTheProxyEndsItsAssociations();

        thenTheLogSaysSomethingStartingWith("WARNING Test UDP is held: the SOCKS5 proxy ended its UDP relay");
        thenTheProxyIsAskedTimes(2);
        thenTheRouteBecomesRelayed();
    }

    @Test
    void shouldSendStraightAndEndTheAssociationWhenTheProxyIsTurnedOff() throws Exception {
        givenAProxy(FakeSocks5Proxy.start());
        givenKeptThroughTheRelay();

        whenTheSettingsBecome(ProxySettings.NONE);

        thenTheRouteIs("direct");
        thenTheProxySawTheAssociationEnd();
    }

    private void givenAProxy(FakeSocks5Proxy fake) {
        this.proxy = fake;
    }

    private void givenKeptThroughTheRelay() throws InterruptedException {
        whenKeptFor(this.proxy.settings());
        thenTheRouteBecomesRelayed();
    }

    private void whenKeptFor(ProxySettings settings) {
        this.keeper = RelayKeeper.start(
                "Test UDP",
                this.routes::add,
                settings,
                null,
                (level, text) -> this.log.add(level + " " + text),
                SHORT_WAIT);
    }

    private void whenTheProxyEndsItsAssociations() throws IOException {
        this.proxy.endAssociations();
    }

    private void whenTheSettingsBecome(ProxySettings settings) {
        this.keeper.use(settings);
    }

    private void thenTheRouteIs(String route) {
        assertThat(this.routes.getLast()).hasToString(route);
    }

    private void thenTheFirstRouteIs(String route) {
        assertThat(this.routes.getFirst()).hasToString(route);
    }

    private void thenTheRouteBecomes(String route) throws InterruptedException {
        eventually(() -> this.routes.getLast().toString().equals(route));
    }

    private void thenTheRouteBecomesRelayed() throws InterruptedException {
        eventually(() -> this.routes.getLast().isRelayed());
    }

    private void thenNothingIsLogged() {
        assertThat(this.log).isEmpty();
    }

    private void thenTheLogSays(String line) {
        assertThat(this.log).containsExactly(line);
    }

    private void thenTheLogSaysSomethingStartingWith(String start) throws InterruptedException {
        eventually(() -> this.log.stream().anyMatch(l -> l.startsWith(start)));
    }

    private void thenTheProxyIsAskedTimes(int times) throws InterruptedException {
        eventually(() -> this.proxy.seen().stream().filter("associate"::equals).count() == times);
    }

    private void thenTheProxySawTheAssociationEnd() throws InterruptedException {
        eventually(() -> this.proxy.seen().contains("ended"));
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waited 10 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }
}
