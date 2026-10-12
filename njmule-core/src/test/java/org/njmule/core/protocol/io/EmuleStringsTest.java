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
package org.njmule.core.protocol.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class EmuleStringsTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void validUtf8IsDecodedAsUtf8() {
        assertThat(EmuleStrings.decode("città €".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("città €");
    }

    @Test
    void bomMarksUtf8AndIsDropped() {
        assertThat(EmuleStrings.decode(HEX.parseHex("efbbbf63c3a0"))).isEqualTo("cà");
    }

    @Test
    void invalidUtf8FallsBackToWindows1252() {
        // "Caf\xE9 \x80" is ANSI from an old client: é and the euro sign.
        assertThat(EmuleStrings.decode(HEX.parseHex("436166e92080"))).isEqualTo("Café €");
    }

    @Test
    void asciiIsWrittenWithoutBomAndUnicodeWithIt() {
        assertThat(EmuleStrings.encode("plain", StringEncoding.UTF8_BOM_IF_NEEDED))
                .isEqualTo("plain".getBytes(StandardCharsets.US_ASCII));
        assertThat(HEX.formatHex(EmuleStrings.encode("à", StringEncoding.UTF8_BOM_IF_NEEDED)))
                .isEqualTo("efbbbfc3a0");
        assertThat(HEX.formatHex(EmuleStrings.encode("à", StringEncoding.UTF8))).isEqualTo("c3a0");
    }
}
