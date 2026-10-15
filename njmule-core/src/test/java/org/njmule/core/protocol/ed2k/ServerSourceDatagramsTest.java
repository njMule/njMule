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

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

/** The UDP source requests to servers and their answers, byte by byte. */
class ServerSourceDatagramsTest {

    private static final Hash16 FIRST = Hash16.parseHex("00112233445566778899aabbccddeeff");
    private static final Hash16 SECOND = Hash16.parseHex("ffeeddccbbaa99887766554433221100");
    private static final int SIZED = ServerDatagrams.UDP_EXT_GETSOURCES | ServerDatagrams.UDP_EXT_GETSOURCES2;
    private static final long FIVE_GB = 5L << 30;

    private List<ServerDatagrams.SourceQuery> files = List.of();
    private List<Packet> requests;
    private Packet answer;
    private List<ServerMessages.FoundSources> found;

    @Test
    void shouldAskForSeveralFilesWithTheirSizesInOneRequestWhereTheServerTakesIt() {
        givenTheFiles(query(FIRST, 1000), query(SECOND, 0x0102_0304L));

        whenTheRequestsAreMadeFor(SIZED);

        thenTheRequestsAre(
                Opcodes.ServerUdp.GLOBGETSOURCES2,
                "00112233445566778899aabbccddeeff" + "e8030000" + "ffeeddccbbaa99887766554433221100" + "04030201");
    }

    @Test
    void shouldAskForOneFileAndNoSizePerRequestWhereTheServerTakesNoMore() {
        givenTheFiles(query(FIRST, 1000), query(SECOND, 2000));

        whenTheRequestsAreMadeFor(0);

        thenTheRequestsAre(
                Opcodes.ServerUdp.GLOBGETSOURCES,
                "00112233445566778899aabbccddeeff",
                "ffeeddccbbaa99887766554433221100");
    }

    @Test
    void shouldGiveTheSizeOfAFileAbove4GbIn64BitsToAServerTakingLargeFiles() {
        givenTheFiles(query(FIRST, FIVE_GB));

        whenTheRequestsAreMadeFor(SIZED | ServerDatagrams.UDP_LARGEFILES);

        thenTheRequestsAre(
                Opcodes.ServerUdp.GLOBGETSOURCES2,
                "00112233445566778899aabbccddeeff" + "00000000" + "0000004001000000");
    }

    @Test
    void shouldLeaveAFileAbove4GbOutForAServerNotTakingLargeFiles() {
        givenTheFiles(query(FIRST, FIVE_GB), query(SECOND, 1000));

        whenTheRequestsAreMadeFor(SIZED);

        thenTheRequestsAre(Opcodes.ServerUdp.GLOBGETSOURCES2, "ffeeddccbbaa99887766554433221100" + "e8030000");
    }

    @Test
    void shouldStartAnotherRequestBeyond510BytesOfFileIds() {
        givenManyFiles(30);

        whenTheRequestsAreMadeFor(SIZED);

        thenTheRequestsHoldFiles(25, 5);
    }

    @Test
    void shouldReadTheSourcesOfEveryFileInOneAnswer() throws Exception {
        givenAnAnswerFor(sources(FIRST, 0x0403_0201L, 4662), sources(SECOND, 0x0000_1234L, 4663));

        whenTheAnswerIsRead();

        thenTheSourcesFoundAre(sources(FIRST, 0x0403_0201L, 4662), sources(SECOND, 0x0000_1234L, 4663));
    }

    @Test
    void shouldDropAFileCutShortAtTheEndOfAnAnswer() throws Exception {
        givenAnAnswerCutShort(sources(FIRST, 0x0403_0201L, 4662), sources(SECOND, 0x0807_0605L, 4663));

        whenTheAnswerIsRead();

        thenTheSourcesFoundAre(sources(FIRST, 0x0403_0201L, 4662));
    }

    private void givenTheFiles(ServerDatagrams.SourceQuery... queries) {
        this.files = List.of(queries);
    }

    private void givenManyFiles(int count) {
        this.files = IntStream.range(0, count)
                .mapToObj(i -> query(Hash16.parseHex("%032x".formatted(i)), 1000))
                .toList();
    }

    private void givenAnAnswerFor(ServerMessages.FoundSources... files) {
        this.answer = ServerDatagrams.foundSources(List.of(files));
    }

    private void givenAnAnswerCutShort(ServerMessages.FoundSources... files) {
        byte[] whole = ServerDatagrams.foundSources(List.of(files)).payload();
        this.answer = Packet.of(
                Protocol.EDONKEY, Opcodes.ServerUdp.GLOBFOUNDSOURCES.code(), Arrays.copyOf(whole, whole.length - 1));
    }

    private void whenTheRequestsAreMadeFor(int udpFlags) {
        this.requests = ServerDatagrams.sourceRequests(this.files, udpFlags);
    }

    private void whenTheAnswerIsRead() throws Exception {
        this.found = ServerDatagrams.decodeFoundSources(this.answer);
    }

    private void thenTheRequestsAre(Opcodes.ServerUdp opcode, String... payloads) {
        assertThat(this.requests).allSatisfy(r -> {
            assertThat(r.protocol()).isEqualTo(Protocol.EDONKEY);
            assertThat(r.opcode()).isEqualTo(opcode.code());
        });
        assertThat(this.requests)
                .extracting(r -> HexFormat.of().formatHex(r.payload()))
                .containsExactly(payloads);
    }

    private void thenTheRequestsHoldFiles(Integer... counts) {
        assertThat(this.requests).extracting(r -> r.payload().length / 20).containsExactly(counts);
    }

    private void thenTheSourcesFoundAre(ServerMessages.FoundSources... files) {
        assertThat(this.found).containsExactly(files);
    }

    private static ServerDatagrams.SourceQuery query(Hash16 file, long size) {
        return new ServerDatagrams.SourceQuery(file, size);
    }

    private static ServerMessages.FoundSources sources(Hash16 file, long clientId, int port) {
        return new ServerMessages.FoundSources(
                file, List.of(new ServerMessages.Source(clientId, port, 0, Optional.empty())));
    }
}
