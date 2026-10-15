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
package org.njmule.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet4Address;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BindInterfaceTest {

    private List<BindInterface.Candidate> computer;
    private BindInterface.Binding binding;

    @ParameterizedTest(name = "{0} binds to {1}")
    @CsvSource({
        "'', any",
        "' ', any",
        "wg0, 10.8.0.2",
        "WG0, 10.8.0.2",
        "WireGuard Tunnel, 10.8.0.2",
        "10.8.0.2, 10.8.0.2",
        "192.168.1.10, 192.168.1.10",
        "eth1, missing",
        "tun0, missing",
        "203.0.113.7, missing",
        "169.254.7.7, 169.254.7.7"
    })
    void shouldBindAChoiceToAnAddressOfAnInterfaceThatIsThere(String choice, String expected) {
        givenAComputerWithALanAVpnAndAnInterfaceWaitingForItsAddress();
        whenTheChoiceIsResolved(choice);
        thenItBindsTo(expected);
    }

    private void givenAComputerWithALanAVpnAndAnInterfaceWaitingForItsAddress() {
        this.computer = List.of(
                new BindInterface.Candidate("lo", "Loopback", true, List.of(address("127.0.0.1"))),
                new BindInterface.Candidate("eth0", "Ethernet", false, List.of(address("192.168.1.10"))),
                new BindInterface.Candidate(
                        "wg0", "WireGuard Tunnel", false, List.of(address("169.254.3.3"), address("10.8.0.2"))),
                // Only the address an interface takes while it waits for its own.
                new BindInterface.Candidate("eth1", "USB Ethernet", false, List.of(address("169.254.7.7"))));
    }

    private void whenTheChoiceIsResolved(String choice) {
        this.binding = BindInterface.resolve(choice, this.computer);
    }

    private void thenItBindsTo(String expected) {
        switch (expected) {
            case "any" -> assertThat(this.binding).isInstanceOf(BindInterface.Binding.Any.class);
            case "missing" -> assertThat(this.binding).isInstanceOf(BindInterface.Binding.Missing.class);
            default -> assertThat(this.binding).isEqualTo(new BindInterface.Binding.Bound(address(expected)));
        }
    }

    private static Inet4Address address(String text) {
        return Inet4Address.ofLiteral(text);
    }
}
