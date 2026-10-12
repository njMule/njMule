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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;

class PacketReaderWriterTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void writesLittleEndian() {
        byte[] out =
                new PacketWriter()
                        .u8(0xE3)
                        .u16(0x1234)
                        .u32(0x89ABCDEFL)
                        .u64(0x0102030405060708L)
                        .toByteArray();

        assertThat(HEX.formatHex(out)).isEqualTo("e33412efcdab890807060504030201");
    }

    @Test
    void readsLittleEndianAndUnsigned() throws DecodeException {
        PacketReader in = PacketReader.of(HEX.parseHex("ff3412efcdab89ffffffffffffffff"));

        assertThat(in.u8()).isEqualTo(0xFF);
        assertThat(in.u16()).isEqualTo(0x1234);
        assertThat(in.u32()).isEqualTo(0x89ABCDEFL);
        assertThat(in.u64()).isEqualTo(-1L);
        assertThat(in.hasRemaining()).isFalse();
    }

    @Test
    void truncatedReadsThrowAndDoNotAllocate() throws DecodeException {
        PacketReader in = PacketReader.of(HEX.parseHex("ffff0102"));
        int length = in.u16();

        assertThatThrownBy(() -> in.bytes(length))
                .isInstanceOf(DecodeException.class)
                .hasMessageContaining("need 65535 bytes");
        assertThatThrownBy(in::u32).isInstanceOf(DecodeException.class);
        assertThat(in.u16()).isEqualTo(0x0201);
    }

    @Test
    void countMustFitTheRemainingData() throws DecodeException {
        PacketReader in = PacketReader.of(new byte[10]);

        assertThat(in.count(5, 2, 100)).isEqualTo(5);
        assertThatThrownBy(() -> in.count(6, 2, 100)).isInstanceOf(DecodeException.class);
        assertThatThrownBy(() -> in.count(101, 0, 100)).isInstanceOf(DecodeException.class);
    }

    @Test
    void sliceAdvancesTheParent() throws DecodeException {
        PacketReader in = PacketReader.of(HEX.parseHex("0102030405"));
        PacketReader part = in.slice(3);

        assertThat(part.rest()).containsExactly(1, 2, 3);
        assertThat(in.u16()).isEqualTo(0x0504);
        assertThatThrownBy(() -> in.slice(1)).isInstanceOf(DecodeException.class);
    }

    @Test
    void patchesCountsWrittenEarlier() {
        PacketWriter out = new PacketWriter();
        out.u32(0);
        out.u16(7);
        out.setU32(0, 3);

        assertThat(HEX.formatHex(out.toByteArray())).isEqualTo("030000000700");
        assertThatThrownBy(() -> out.setU32(3, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void enforcesTheSizeCap() {
        PacketWriter out = new PacketWriter(4);
        out.u32(1);

        assertThatThrownBy(() -> out.u8(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stringsAndHashesRoundTrip() throws DecodeException {
        Hash16 hash = Hash16.parseHex("31D6CFE0D16AE931B73C59D7E0C089C0");
        byte[] out =
                new PacketWriter()
                        .string16("Ünïcødé", StringEncoding.UTF8)
                        .hash16(hash)
                        .toByteArray();

        PacketReader in = PacketReader.of(out);
        assertThat(in.string16()).isEqualTo("Ünïcødé");
        assertThat(in.hash16()).isEqualTo(hash);
        in.expectEnd();
    }

    @Property
    void integersRoundTrip(
            @ForAll byte a, @ForAll short b, @ForAll int c, @ForAll long d, @ForAll float e)
            throws DecodeException {
        PacketReader in =
                PacketReader.of(new PacketWriter().u8(a).u16(b).u32(c).u64(d).f32(e).toByteArray());

        assertThat(in.u8()).isEqualTo(a & 0xFF);
        assertThat(in.u16()).isEqualTo(b & 0xFFFF);
        assertThat(in.i32()).isEqualTo(c);
        assertThat(in.u64()).isEqualTo(d);
        assertThat(Float.floatToRawIntBits(in.f32())).isEqualTo(Float.floatToRawIntBits(e));
    }
}
