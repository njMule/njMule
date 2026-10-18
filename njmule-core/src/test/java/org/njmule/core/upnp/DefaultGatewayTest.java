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
import java.util.List;
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

    private static final List<String> PROC_NET_IPV6_ROUTE = List.of(
            ipv6Route("00000000000000000000000000000000 00", "fe800000000000000000000000000001", "00000400", "eth0"),
            ipv6Route("00000000000000000000000000000000 00", "fe800000000000000000000000000002", "00000064", "wlan0"),
            ipv6Route("fe800000000000000000000000000000 40", "00000000000000000000000000000000", "00000100", "eth0"));

    private static final String ROUTE_PRINT_6 = """
            IPv6 Route Table
            ===========================================================================
            Active Routes:
             If Metric Network Destination      Gateway
             11    281 ::/0                     fe80::1
             15    100 ::/0                     fe80::aa:bb
              1    331 ::1/128                  On-link
            ===========================================================================
            Persistent Routes:
              None
            """;

    private Optional<InetAddress> gateway;
    private Optional<String> scopedGateway;

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

    @Test
    void shouldReadTheIpv6GatewayOfTheLowestMetricOnLinuxWithItsInterface() {
        whenTheLinuxIpv6TableIsRead(PROC_NET_IPV6_ROUTE);

        thenTheIpv6GatewayIs("fe80::2%wlan0");
    }

    @Test
    void shouldReadTheIpv6GatewayOfTheLowestMetricOnWindowsWithItsInterface() {
        whenTheWindowsIpv6TableIsRead(ROUTE_PRINT_6);

        thenTheIpv6GatewayIs("fe80::aa:bb%15");
    }

    @Test
    void shouldReadTheIpv6GatewayOnMacosWithItsInterface() {
        whenTheMacosIpv6AnswerIsRead("   route to: default\n    gateway: fe80::1%en0\n  interface: en0\n");

        thenTheIpv6GatewayIs("fe80::1%en0");
    }

    @Test
    void shouldFindNoIpv6GatewayWithoutADefaultRoute() {
        whenTheWindowsIpv6TableIsRead("IPv6 Route Table\nActive Routes:\n  1    331 ::1/128   On-link\n");

        thenThereIsNoIpv6Gateway();
    }

    private void whenTheLinuxIpv6TableIsRead(List<String> table) {
        this.scopedGateway = DefaultGateway.fromProcNetIpv6Route(table);
    }

    // A line of /proc/net/ipv6_route: destination and its length, source and its length, next hop, metric, use counts,
    // flags and interface.
    private static String ipv6Route(String destination, String hop, String metric, String iface) {
        return destination + " 00000000000000000000000000000000 00 " + hop + " " + metric + " 00000001 00000000 "
                + "00000003 " + iface;
    }

    private void whenTheWindowsIpv6TableIsRead(String table) {
        this.scopedGateway = DefaultGateway.fromRoutePrintIpv6(table);
    }

    private void whenTheMacosIpv6AnswerIsRead(String answer) {
        this.scopedGateway = DefaultGateway.fromRouteGet(answer, "gateway:");
    }

    private void thenTheIpv6GatewayIs(String scoped) {
        assertThat(this.scopedGateway).hasValue(scoped);
    }

    private void thenThereIsNoIpv6Gateway() {
        assertThat(this.scopedGateway).isEmpty();
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
