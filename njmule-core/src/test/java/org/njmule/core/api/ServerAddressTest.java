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
package org.njmule.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ServerAddressTest {

    @Test
    void parsesHostsWithTheirPort() {
        assertThat(ServerAddress.parse(" 192.168.1.10:4661 ")).isEqualTo(new ServerAddress("192.168.1.10", 4661));
        assertThat(ServerAddress.parse("[2001:db8::1]:4661").host()).isEqualTo("2001:db8::1");
        assertThatThrownBy(() -> ServerAddress.parse("server.example.org"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServerAddress.parse("host:70000")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesAddressesThatParseBack() {
        for (ServerAddress address : new ServerAddress[] {
            new ServerAddress("2001:db8::1", 4661),
            new ServerAddress("::1", 1),
            new ServerAddress("192.168.1.10", 65535),
            new ServerAddress("server.example.org", 4242)
        }) {
            assertThat(ServerAddress.parse(address.toString())).isEqualTo(address);
        }
        assertThat(new ServerAddress("2001:db8::1", 4661)).hasToString("[2001:db8::1]:4661");
    }

    @Test
    void tellsWellFormedHosts() {
        assertThat(ServerAddress.isValidHost("server.example.org")).isTrue();
        assertThat(ServerAddress.isValidHost("localhost")).isTrue();
        assertThat(ServerAddress.isValidHost("10.0.0.1")).isTrue();
        assertThat(ServerAddress.isValidHost("2001:db8::1")).isTrue();
        assertThat(ServerAddress.isValidHost("bad..host")).isFalse();
        assertThat(ServerAddress.isValidHost("no spaces")).isFalse();
        assertThat(ServerAddress.isValidHost("")).isFalse();
    }

    @Test
    void searchExpressionsAreChecked() {
        SearchRequest.checkExpression("ubuntu iso");
        assertThatThrownBy(() -> SearchRequest.checkExpression("(ubuntu")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SearchRequest.checkExpression("x".repeat(SearchRequest.MAX_EXPRESSION_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
