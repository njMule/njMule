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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IpFilterUpdateTest {

    @Test
    void theFormOfAListFromTheWebIsFoundWhateverItsName() {
        byte[] emule = "001.002.003.000 - 001.002.003.255 , 000 , somebody\n".getBytes(StandardCharsets.US_ASCII);
        byte[] guardian = "somebody:1.2.4.0-1.2.4.255\n".getBytes(StandardCharsets.US_ASCII);

        assertThat(Engine.ipRanges(emule, URI.create("https://example.org/ipfilter.zip")))
                .hasSize(1);
        assertThat(Engine.ipRanges(guardian, URI.create("https://example.org/guarding.p2p.gz")))
                .hasSize(1);
        assertThat(Engine.ipRanges(guardian, URI.create("https://example.org/download?list=level1")))
                .as("an address that does not name the form")
                .hasSize(1);
        assertThat(Engine.ipRanges(
                        "<html>nothing</html>".getBytes(StandardCharsets.US_ASCII), URI.create("https://x.org/")))
                .isEmpty();
    }
}
