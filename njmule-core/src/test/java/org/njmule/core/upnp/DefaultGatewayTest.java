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
package org.njmule.core.upnp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultGatewayTest {

    private static final String PROC_NET_ROUTE = """
            Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT
            eth0\t0000000A\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0
            eth0\t00000000\t0101A8C0\t0003\t0\t0\t100\t00000000\t0\t0\t0
            """;

    // As an Italian Windows writes it: only the rows count, not the headings.
    private static final String ROUTE_PRINT_IT = """
            ===========================================================================
            Elenco interfacce
              4...00 11 22 33 44 55 ......Ethernet Controller
              1...........................Software Loopback Interface 1
            ===========================================================================

            IPv4 Tabella route
            ===========================================================================
            Route attive:
                 Indirizzo rete             Mask          Gateway     Interfaccia Metrica
                      0.0.0.0          0.0.0.0         10.0.0.1         10.0.0.20     35
                      0.0.0.0          0.0.0.0      192.168.1.1     192.168.1.20     25
                      0.0.0.0          0.0.0.0 Collegamento diretto     10.8.0.2      5
            ===========================================================================
            Route permanenti:
              Indirizzo rete            Mask  Indirizzo gateway Metrica
                      0.0.0.0          0.0.0.0      172.16.0.1  Predefinito
            ===========================================================================
            """;

    private static final String ROUTE_GET = """
               route to: default
            destination: default
                   mask: default
                gateway: 192.168.2.1
              interface: en0
                  flags: <UP,GATEWAY,DONE,STATIC,PRCLONING,GLOBAL>
            """;

    private Optional<InetAddress> gateway;

    @Test
    void shouldReadTheGatewayOfTheDefaultRouteOnLinux() {
        whenTheLinuxTableIsRead(PROC_NET_ROUTE);

        thenTheGatewayIs("192.168.1.1");
    }

    @Test
    void shouldFindNoGatewayInALinuxTableWithoutADefaultRoute() {
        whenTheLinuxTableIsRead(PROC_NET_ROUTE.lines().limit(2).reduce("", (a, b) -> a + b + "\n"));

        thenThereIsNoGateway();
    }

    @Test
    void shouldTakeTheDefaultRouteOfTheLowestMetricOnWindows() {
        whenTheWindowsTableIsRead(ROUTE_PRINT_IT);

        thenTheGatewayIs("192.168.1.1");
    }

    @Test
    void shouldFindNoGatewayInAWindowsTableWithoutADefaultRoute() {
        whenTheWindowsTableIsRead("IPv4 Route Table\nActive Routes:\nNone\n");

        thenThereIsNoGateway();
    }

    @Test
    void shouldReadTheGatewayLineOnMacos() {
        whenTheMacosAnswerIsRead(ROUTE_GET);

        thenTheGatewayIs("192.168.2.1");
    }

    @Test
    void shouldFindNoGatewayInAMacosAnswerWithoutOne() {
        whenTheMacosAnswerIsRead("route: writing to routing socket: not in table\n");

        thenThereIsNoGateway();
    }

    private void whenTheLinuxTableIsRead(String table) {
        this.gateway = DefaultGateway.fromProcNetRoute(table.lines().toList());
    }

    private void whenTheWindowsTableIsRead(String table) {
        this.gateway = DefaultGateway.fromRoutePrint(table);
    }

    private void whenTheMacosAnswerIsRead(String answer) {
        this.gateway = DefaultGateway.fromRouteGet(answer);
    }

    private void thenTheGatewayIs(String address) {
        assertThat(this.gateway).hasValue(InetAddress.ofLiteral(address));
    }

    private void thenThereIsNoGateway() {
        assertThat(this.gateway).isEmpty();
    }
}
