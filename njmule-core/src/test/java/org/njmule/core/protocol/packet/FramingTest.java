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
package org.njmule.core.protocol.packet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;

class FramingTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void tcpLengthCountsTheOpcode() {
        byte[] frame = TcpFraming.encode(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {0x10}));

        assertThat(HEX.formatHex(frame)).isEqualTo("e3020000000110");
    }

    @Test
    void readsConsecutiveFramesAndStopsCleanlyAtTheEnd() throws IOException {
        InputStream in = new ByteArrayInputStream(HEX.parseHex("e3020000000110" + "c5010000004c"));

        assertThat(TcpFraming.read(in, FrameLimit.fixed(10)))
                .isEqualTo(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {0x10}));
        assertThat(TcpFraming.read(in, FrameLimit.fixed(10)))
                .isEqualTo(Packet.empty(Protocol.EMULE, 0x4C));
        assertThatThrownBy(() -> TcpFraming.read(in, FrameLimit.fixed(10)))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void rejectsOversizedFramesBeforeReadingThem() {
        // Announces 2^32-1 bytes: rejected from the header alone.
        InputStream in = new ByteArrayInputStream(HEX.parseHex("e3ffffffff01"));

        assertThatThrownBy(() -> TcpFraming.read(in, FrameLimit.fixed(4096)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("exceeds 4096");
    }

    @Test
    void rejectsUnknownProtocolsZeroLengthsAndDisallowedOpcodes() {
        assertThatThrownBy(() -> read("e4020000000110", FrameLimit.fixed(10)))
                .isInstanceOf(DecodeException.class);
        assertThatThrownBy(() -> read("e30000000001", FrameLimit.fixed(10)))
                .isInstanceOf(DecodeException.class);
        assertThatThrownBy(
                        () ->
                                read(
                                        "e3020000004c10",
                                        (p, op) -> op == 0x01 ? 10 : FrameLimit.REJECT))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> read("e305000000011011", FrameLimit.fixed(10)))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("inside a frame");
    }

    @Test
    void udpNeedsAtLeastAHeader() throws DecodeException {
        assertThatThrownBy(() -> UdpFraming.decode(new byte[] {(byte) 0xE3}, 0, 1))
                .isInstanceOf(DecodeException.class);
        assertThat(UdpFraming.decode(HEX.parseHex("e396"), 0, 2))
                .isEqualTo(Packet.empty(Protocol.EDONKEY, 0x96));
    }

    @Test
    void packsOnlyWhenSmallerAndUnpacksToTheEmuleProtocol() throws DecodeException {
        Packet big = Packet.of(Protocol.EMULE, 0x40, new byte[4000]);
        Packet packed = Packing.pack(big);
        Packet tiny = Packet.of(Protocol.EMULE, 0x40, new byte[] {1, 2, 3});

        assertThat(packed.protocol()).isEqualTo(Protocol.PACKED);
        assertThat(packed.length()).isLessThan(100);
        assertThat(Packing.unpack(packed, 4000)).isEqualTo(big);
        assertThat(Packing.pack(tiny)).isSameAs(tiny);
        assertThat(
                        Packing.unpack(
                                        Packing.pack(Packet.of(Protocol.KAD, 0x3B, new byte[500])),
                                        500)
                                .protocol())
                .isEqualTo(Protocol.KAD);
    }

    @Test
    void packedServerFramesCarryEdonkeyMessages() throws DecodeException {
        Packet results = Packet.of(Protocol.EDONKEY, 0x33, new byte[2000]);
        Packet packed = Packing.pack(results);

        assertThat(packed.protocol()).isEqualTo(Protocol.PACKED);
        assertThat(Packing.unpackFromServer(packed)).isEqualTo(results);
        assertThat(Packing.unpackFromServer(results)).isSameAs(results);
    }

    @Test
    void inflatingStopsAtTheCap() {
        // 1 MiB of zeros packs to about 1 KiB: a decompression bomb for a 50 kB cap.
        Packet bomb = Packing.pack(Packet.of(Protocol.EMULE, 0x40, new byte[1 << 20]));

        assertThatThrownBy(() -> Packing.unpack(bomb, Packing.MAX_CLIENT_UNPACKED))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("exceeds");
        assertThatThrownBy(() -> Packing.inflate(new byte[] {0x78, (byte) 0x9C, 1, 2}, 100))
                .isInstanceOf(DecodeException.class);
    }

    @Property
    void tcpRoundTrips(
            @ForAll @IntRange(min = 0, max = 2) int which,
            @ForAll @IntRange(max = 255) int opcode,
            @ForAll @Size(max = 300) byte[] payload)
            throws IOException {
        int protocol = new int[] {Protocol.EDONKEY, Protocol.EMULE, Protocol.PACKED}[which];
        Packet packet = Packet.of(protocol, opcode, payload);

        assertThat(read(HEX.formatHex(TcpFraming.encode(packet)), FrameLimit.fixed(300)))
                .isEqualTo(packet);
    }

    @Property
    void garbageStreamsOnlyFailWithIoExceptions(@ForAll @Size(max = 40) byte[] data) {
        InputStream in = new ByteArrayInputStream(data);
        try {
            for (int i = 0; i < 10; i++) {
                TcpFraming.read(in, FrameLimit.fixed(16));
            }
        } catch (IOException expected) {
            // DecodeException or EOFException
        }
    }

    @Property
    void garbageNeverInflatesPastTheCap(@ForAll @Size(max = 64) byte[] data) {
        try {
            assertThat(Packing.inflate(data, 1000).length).isLessThanOrEqualTo(1000);
        } catch (DecodeException expected) {
            // corrupt input
        }
    }

    private static Packet read(String hex, FrameLimit limit) throws IOException {
        return TcpFraming.read(new ByteArrayInputStream(HEX.parseHex(hex)), limit);
    }
}
