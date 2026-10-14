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
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class Ed2kStringsTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void validUtf8IsDecodedAsUtf8() {
        assertThat(Ed2kStrings.decode("città €".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("città €");
    }

    @Test
    void bomMarksUtf8AndIsDropped() {
        assertThat(Ed2kStrings.decode(HEX.parseHex("efbbbf63c3a0"))).isEqualTo("cà");
    }

    @Test
    void invalidUtf8FallsBackToWindows1252() {
        // "Caf\xE9 \x80" is ANSI from an old client: é and the euro sign.
        assertThat(Ed2kStrings.decode(HEX.parseHex("436166e92080"))).isEqualTo("Café €");
    }

    @Test
    void invalidUtf8AfterABomFallsBackToWindows1252Too() {
        assertThat(Ed2kStrings.decode(HEX.parseHex("efbbbf80e9"))).isEqualTo("€é");
        assertThat(Ed2kStrings.decode(HEX.parseHex("efbbbf"))).isEmpty();
    }

    @Test
    void malformedUtf8IsWindows1252() {
        assertThat(Ed2kStrings.decode(HEX.parseHex("c0af"))).as("overlong /").isEqualTo("À¯");
        assertThat(Ed2kStrings.decode(HEX.parseHex("eda080")))
                .as("an encoded surrogate")
                .isEqualTo("í" + Character.toString(0xA0) + "€");
        assertThat(Ed2kStrings.decode(HEX.parseHex("efbb"))).as("a cut BOM").isEqualTo("ï»");
        assertThat(Ed2kStrings.decode(HEX.parseHex("ef"))).isEqualTo("ï");
        assertThat(Ed2kStrings.decode(new byte[0])).isEmpty();
    }

    @Property
    void textWithoutLoneSurrogatesRoundTripsInBothEncodings(@ForAll String text) {
        Assume.that(text.codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF));
        Assume.that(!text.startsWith("﻿"));

        for (StringEncoding encoding : StringEncoding.values()) {
            assertThat(Ed2kStrings.decode(Ed2kStrings.encode(text, encoding))).isEqualTo(text);
        }
    }

    @Property
    void anyBytesDecodeToSomeText(@ForAll byte[] raw) {
        assertThat(Ed2kStrings.decode(raw)).isNotNull();
    }

    @Test
    void asciiIsWrittenWithoutBomAndUnicodeWithIt() {
        assertThat(Ed2kStrings.encode("plain", StringEncoding.UTF8_BOM_IF_NEEDED))
                .isEqualTo("plain".getBytes(StandardCharsets.US_ASCII));
        assertThat(HEX.formatHex(Ed2kStrings.encode("à", StringEncoding.UTF8_BOM_IF_NEEDED)))
                .isEqualTo("efbbbfc3a0");
        assertThat(HEX.formatHex(Ed2kStrings.encode("à", StringEncoding.UTF8))).isEqualTo("c3a0");
    }
}
