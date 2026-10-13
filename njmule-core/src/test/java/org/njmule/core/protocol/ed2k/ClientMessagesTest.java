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

import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class ClientMessagesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 FILE = Hash16.parseHex("ab".repeat(16));

    @Test
    void fileStatusBitsStartWithTheLowestBit() throws DecodeException {
        BitSet parts = new BitSet();
        parts.set(0);
        parts.set(2);
        parts.set(9);
        ClientMessages.FileStatus status = new ClientMessages.FileStatus(FILE, 10, parts);

        assertThat(HEX.formatHex(status.encode().payload())).isEqualTo("ab".repeat(16) + "0a00" + "05" + "02");
        ClientMessages.FileStatus decoded = ClientMessages.FileStatus.decode(status.encode());
        assertThat(decoded.has(2)).isTrue();
        assertThat(decoded.has(1)).isFalse();
        assertThat(decoded.has(9)).isTrue();

        ClientMessages.FileStatus complete =
                ClientMessages.FileStatus.decode(new ClientMessages.FileStatus(FILE, 0, new BitSet()).encode());
        assertThat(complete.has(123)).isTrue();
        assertThat(complete.dataParts(3 * 9_728_000L).cardinality()).isEqualTo(3);
    }

    @Test
    void aFileStatusFitsOnlyTheSizesOfItsPartCount() {
        ClientMessages.FileStatus three = new ClientMessages.FileStatus(FILE, 3, new BitSet());

        assertThat(new ClientMessages.FileStatus(FILE, 0, new BitSet()).fits(123))
                .isTrue();
        assertThat(three.fits(2 * Ed2k.PARTSIZE + 1)).isTrue();
        assertThat(three.fits(2 * Ed2k.PARTSIZE))
                .as("the empty last part counts")
                .isTrue();
        assertThat(three.fits(Ed2k.PARTSIZE)).isFalse();
        assertThat(three.fits(3 * Ed2k.PARTSIZE)).isFalse();
    }

    @Test
    void requestsAndDataRoundTripInBothWidths() throws DecodeException {
        ClientMessages.RequestParts request = new ClientMessages.RequestParts(
                FILE,
                List.of(
                        new ClientMessages.Range(0, 184_320),
                        new ClientMessages.Range(184_320, 368_640),
                        new ClientMessages.Range(0, 0)));
        Packet narrow = request.encode(false);
        Packet wide = request.encode(true);

        assertThat(narrow.opcode()).isEqualTo(Opcodes.Client.REQUESTPARTS.code());
        assertThat(narrow.length()).isEqualTo(16 + 6 * 4);
        assertThat(wide.protocol()).isEqualTo(Protocol.EMULE);
        assertThat(wide.length()).isEqualTo(16 + 6 * 8);
        assertThat(ClientMessages.RequestParts.decode(narrow)).isEqualTo(request);
        assertThat(ClientMessages.RequestParts.decode(wide)).isEqualTo(request);

        ClientMessages.SendingPart data = new ClientMessages.SendingPart(FILE, 10, 13, new byte[] {1, 2, 3});
        for (boolean w : new boolean[] {false, true}) {
            ClientMessages.SendingPart back = ClientMessages.SendingPart.decode(data.encode(w));
            assertThat(back.start()).isEqualTo(10);
            assertThat(back.end()).isEqualTo(13);
            assertThat(back.data()).containsExactly(1, 2, 3);
        }
        assertThatThrownBy(() -> ClientMessages.SendingPart.decode(Packet.of(
                        Protocol.EDONKEY, 0x46, HEX.parseHex("ab".repeat(16) + "0a000000" + "0d000000" + "01"))))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void compressedChunksRoundTripInBothWidths() throws DecodeException {
        ClientMessages.CompressedPart chunk = new ClientMessages.CompressedPart(FILE, 184_320, 5, new byte[] {9, 8, 7});
        Packet narrow = chunk.encode(false);
        Packet wide = chunk.encode(true);

        assertThat(narrow.opcode()).isEqualTo(Opcodes.Extended.COMPRESSEDPART.code());
        assertThat(HEX.formatHex(narrow.payload())).isEqualTo("ab".repeat(16) + "00d00200" + "05000000" + "090807");
        assertThat(wide.opcode()).isEqualTo(Opcodes.Extended.COMPRESSEDPART_I64.code());
        assertThat(wide.length()).isEqualTo(16 + 8 + 4 + 3);
        for (Packet packet : List.of(narrow, wide)) {
            ClientMessages.CompressedPart back = ClientMessages.CompressedPart.decode(packet);
            assertThat(back.blockStart()).isEqualTo(184_320);
            assertThat(back.compressedSize()).isEqualTo(5);
            assertThat(back.chunk()).containsExactly(9, 8, 7);
        }
        assertThatThrownBy(() -> ClientMessages.CompressedPart.decode(
                        new ClientMessages.CompressedPart(FILE, 0, 2, new byte[] {1, 2, 3}).encode(false)))
                .isInstanceOf(DecodeException.class);
    }

    @Test
    void namesHashSetsAndQueueRanks() throws DecodeException {
        ClientMessages.FileName name = new ClientMessages.FileName(FILE, "ubuntu.iso");
        assertThat(ClientMessages.FileName.decode(name.encode())).isEqualTo(name);

        ClientMessages.HashSet set = new ClientMessages.HashSet(FILE, List.of(FILE, Hash16.ZERO));
        assertThat(ClientMessages.HashSet.decode(set.encode())).isEqualTo(set);

        assertThat(ClientMessages.decodeQueueRank(Packet.of(Protocol.EDONKEY, 0x5C, HEX.parseHex("2a000000"))))
                .isEqualTo(42);
        assertThat(ClientMessages.decodeQueueRank(
                        Packet.of(Protocol.EMULE, 0x60, HEX.parseHex("0700" + "00".repeat(10)))))
                .isEqualTo(7);
        assertThat(ClientMessages.fileOf(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, FILE)))
                .isEqualTo(FILE);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 120) byte[] data) {
        for (int protocol : new int[] {Protocol.EDONKEY, Protocol.EMULE}) {
            Packet packet = Packet.of(protocol, 0x50, data);
            quietly(() -> ClientMessages.FileStatus.decode(packet));
            quietly(() -> ClientMessages.HashSet.decode(packet));
            quietly(() -> ClientMessages.RequestParts.decode(packet));
            quietly(() -> ClientMessages.SendingPart.decode(packet));
            quietly(() -> ClientMessages.CompressedPart.decode(packet));
            quietly(() -> ClientMessages.FileName.decode(packet));
            quietly(() -> ClientMessages.decodeQueueRank(packet));
        }
    }

    private interface Decoder {
        void decode() throws DecodeException;
    }

    private static void quietly(Decoder decoder) {
        try {
            decoder.decode();
        } catch (DecodeException expected) {
            // malformed
        }
    }
}
