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
package org.njmule.core.protocol.ed2k;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.util.Ipv4;

class SourceExchangeTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 FILE = Hash16.parseHex("ab".repeat(16));
    private static final Hash16 USER = Hash16.parseHex("cd".repeat(16));
    private static final int IP = Ipv4.of(1, 2, 3, 4);

    @Test
    void idsAreEd2kUpToVersionTwoAndHostOrderFromThree() {
        SourceExchange.Source high =
                new SourceExchange.Source(IP, 0, 4662, Ipv4.of(9, 9, 9, 9), 4661, Optional.of(USER), 3);

        String v2 = HEX.formatHex(
                new SourceExchange.Answer(2, FILE, List.of(high)).encode().payload());
        String v4 = HEX.formatHex(
                new SourceExchange.Answer(4, FILE, List.of(high)).encode().payload());

        assertThat(v2).startsWith("02" + "ab".repeat(16) + "0100" + "01020304" + "3612");
        assertThat(v4).startsWith("04" + "ab".repeat(16) + "0100" + "04030201" + "3612");
        assertThat(v4).endsWith("cd".repeat(16) + "03");
    }

    @Test
    void answersRoundTripInEveryVersion() throws DecodeException {
        SourceExchange.Source high =
                new SourceExchange.Source(IP, 0, 4662, Ipv4.of(9, 9, 9, 9), 4661, Optional.of(USER), 1);
        SourceExchange.Source low = new SourceExchange.Source(0, 1234, 4663, 0, 0, Optional.of(USER), 0);
        for (int version = 1; version <= SourceExchange.VERSION; version++) {
            SourceExchange.Answer back =
                    SourceExchange.Answer.decode(new SourceExchange.Answer(version, FILE, List.of(high, low)).encode());
            assertThat(back.version()).isEqualTo(version);
            assertThat(back.sources().get(0).ip()).isEqualTo(IP);
            assertThat(back.sources().get(0).port()).isEqualTo(4662);
            assertThat(back.sources().get(0).serverIp()).isEqualTo(Ipv4.of(9, 9, 9, 9));
            assertThat(back.sources().get(1).isLowId()).isTrue();
            assertThat(back.sources().get(1).lowId()).isEqualTo(1234);
            assertThat(back.sources().get(0).userHash()).isEqualTo(version >= 2 ? Optional.of(USER) : Optional.empty());
            assertThat(back.sources().get(0).cryptOptions()).isEqualTo(version >= 4 ? 1 : 0);
        }
    }

    @Test
    void requestsCarryVersionOptionsThenTheFile() throws DecodeException {
        Packet request = SourceExchange.request(FILE);
        assertThat(HEX.formatHex(request.payload())).isEqualTo("04" + "0000" + "ab".repeat(16));
        assertThat(SourceExchange.Request.decode(request)).isEqualTo(new SourceExchange.Request(4, FILE));
    }

    @Test
    void refusesAnswersWhoseSizeDoesNotMatch() {
        byte[] payload = HEX.parseHex("04" + "ab".repeat(16) + "0200" + "00".repeat(29));
        assertThatThrownBy(() -> SourceExchange.Answer.decode(Packet.of(Protocol.EMULE, 0x84, payload)))
                .isInstanceOf(DecodeException.class);
        byte[] version = HEX.parseHex("07" + "ab".repeat(16) + "0000");
        assertThatThrownBy(() -> SourceExchange.Answer.decode(Packet.of(Protocol.EMULE, 0x84, version)))
                .isInstanceOf(DecodeException.class);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 200) byte[] data) {
        Packet packet = Packet.of(Protocol.EMULE, 0x84, data);
        try {
            SourceExchange.Answer.decode(packet);
        } catch (DecodeException expected) {
            // malformed
        }
        try {
            SourceExchange.Request.decode(packet);
        } catch (DecodeException expected) {
            // malformed
        }
    }
}
