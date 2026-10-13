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
package org.njmule.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Ipv4Test {

    @Test
    void tellsLanAddresses() {
        assertThat(Ipv4.isLan(Ipv4.of(10, 0, 0, 1))).isTrue();
        assertThat(Ipv4.isLan(Ipv4.of(172, 16, 0, 1))).isTrue();
        assertThat(Ipv4.isLan(Ipv4.of(172, 31, 255, 255))).isTrue();
        assertThat(Ipv4.isLan(Ipv4.of(192, 168, 1, 1))).isTrue();
        assertThat(Ipv4.isLan(Ipv4.of(0, 1, 2, 3))).isTrue();
        assertThat(Ipv4.isLan(Ipv4.of(172, 32, 0, 1))).isFalse();
        assertThat(Ipv4.isLan(Ipv4.of(192, 169, 0, 1))).isFalse();
        assertThat(Ipv4.isLan(Ipv4.of(11, 0, 0, 1))).isFalse();
        assertThat(Ipv4.isLan(Ipv4.of(127, 0, 0, 1))).as("loopback is apart").isFalse();
    }

    @Test
    void tellsUsablePeerAddresses() {
        assertThat(Ipv4.isUnicastPublicCandidate(Ipv4.of(93, 184, 216, 34))).isTrue();
        assertThat(Ipv4.isUnicastPublicCandidate(Ipv4.of(127, 0, 0, 1))).isFalse();
        assertThat(Ipv4.isUnicastPublicCandidate(Ipv4.of(224, 0, 0, 1))).isFalse();
        assertThat(Ipv4.isUnicastPublicCandidate(0)).isFalse();
    }
}
