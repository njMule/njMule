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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.BitSet;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class ClientMessagesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final Hash16 FILE = Hash16.parseHex("ab".repeat(16));
    private static final long FOUR_GIB = 1L << 32;

    private Packet packet;
    private ClientMessages.RequestParts request;
    private ClientMessages.RequestParts sentRequest;
    private ClientMessages.FileStatus status;
    private ClientMessages.FileStatus decodedStatus;
    private boolean fits;
    private ClientMessages.SendingPart part;
    private ClientMessages.SendingPart decodedPart;
    private ClientMessages.CompressedPart chunk;
    private ClientMessages.CompressedPart decodedChunk;
    private ClientMessages.FileName fileName;
    private ClientMessages.FileName decodedFileName;
    private ClientMessages.HashSet hashSet;
    private ClientMessages.HashSet decodedHashSet;
    private long queueRank;
    private Hash16 file;
    private byte[] garbage;
    private Throwable exception;

    @Test
    void shouldEncodeTheFileStatusBitsFromTheLowestBit() {
        givenAFileStatusOfParts(10, 0, 2, 9);

        whenTheFileStatusIsEncoded();

        thenNoExceptionOccurred();
        thenThePayloadIs("ab".repeat(16) + "0a00" + "05" + "02");
    }

    @Test
    void shouldDecodeTheFileStatusBitsFromTheLowestBit() throws DecodeException {
        givenAFileStatusOfParts(10, 0, 2, 9);
        givenTheFileStatusIsEncoded();

        whenTheFileStatusIsDecoded();

        thenTheDecodedStatusHasPart(2);
        thenTheDecodedStatusLacksPart(1);
        thenTheDecodedStatusHasPart(9);
    }

    @Property
    void shouldDecodeTheFileStatusItEncodes(@ForAll @IntRange(min = 1, max = 400) int partCount, @ForAll long seed)
            throws DecodeException {
        givenAFileStatusOfRandomParts(partCount, seed);
        givenTheFileStatusIsEncoded();

        whenTheFileStatusIsDecoded();

        thenTheDecodedStatusIsTheEncodedOne();
    }

    @Test
    void shouldIgnoreThePaddingBitsOfTheLastByte() throws DecodeException {
        givenAFileStatusOfParts(10, 0, 2, 9);
        givenTheFileStatusIsEncoded();
        givenThePaddingBitsOfItsLastByteAreSet();

        whenTheFileStatusIsDecoded();

        thenTheDecodedStatusIsTheEncodedOne();
    }

    @Test
    void shouldDecodeAFileStatusWithoutPartsAsComplete() throws DecodeException {
        givenAFileStatusOfParts(0);
        givenTheFileStatusIsEncoded();

        whenTheFileStatusIsDecoded();

        thenTheDecodedStatusHasPart(123);
        thenTheDecodedStatusHasDataPartsForTheSize(3 * 9_728_000L, 3);
    }

    @Test
    void shouldFitAnySizeWithAFileStatusWithoutParts() {
        givenAFileStatusOfParts(0);

        whenItIsCheckedAgainstTheSize(123);

        thenItFits();
    }

    @Test
    void shouldFitASizeOfItsPartCount() {
        givenAFileStatusOfParts(3);

        whenItIsCheckedAgainstTheSize(2 * Ed2k.PARTSIZE + 1);

        thenItFits();
    }

    @Test
    void shouldFitASizeWhoseEmptyLastPartCounts() {
        givenAFileStatusOfParts(3);

        whenItIsCheckedAgainstTheSize(2 * Ed2k.PARTSIZE);

        thenItFitsAs("the empty last part counts");
    }

    @Test
    void shouldNotFitASizeOfFewerParts() {
        givenAFileStatusOfParts(3);

        whenItIsCheckedAgainstTheSize(Ed2k.PARTSIZE);

        thenItDoesNotFit();
    }

    @Test
    void shouldNotFitASizeOfMoreParts() {
        givenAFileStatusOfParts(3);

        whenItIsCheckedAgainstTheSize(3 * Ed2k.PARTSIZE);

        thenItDoesNotFit();
    }

    @Test
    void shouldEncodeANarrowPartRequest() {
        givenAPartRequest(range(0, 184_320), range(184_320, 368_640), range(0, 0));

        whenTheRequestIsEncoded(false);

        thenNoExceptionOccurred();
        thenThePacketOpcodeIs(Opcodes.Client.REQUESTPARTS.code());
        thenThePacketLengthIs(16 + 6 * 4);
    }

    @Test
    void shouldEncodeAWidePartRequest() {
        givenAPartRequest(range(0, 184_320), range(184_320, 368_640), range(0, 0));

        whenTheRequestIsEncoded(true);

        thenNoExceptionOccurred();
        thenThePacketProtocolIs(Protocol.EMULE);
        thenThePacketLengthIs(16 + 6 * 8);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRoundTripAPartRequestInBothWidths(boolean wide) throws DecodeException {
        givenAPartRequest(range(0, 184_320), range(184_320, 368_640), range(0, 0));
        givenTheRequestIsEncoded(wide);

        whenTheRequestIsDecoded();

        thenTheDecodedRequestIsTheSentOne();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRoundTripAPartOfDataInBothWidths(boolean wide) {
        givenAPartOfData(10, 13, new byte[] {1, 2, 3});
        givenThePartIsEncoded(wide);

        whenThePartIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedPartSpans(10, 13);
        thenTheDecodedPartCarries(1, 2, 3);
    }

    @Test
    void shouldRefuseAPartOfDataShorterThanItsRange() {
        givenThePacket(Protocol.EDONKEY, 0x46, "ab".repeat(16) + "0a000000" + "0d000000" + "01");

        whenThePartIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldEncodeANarrowCompressedChunk() {
        givenACompressedChunk(184_320, 5, new byte[] {9, 8, 7});

        whenTheChunkIsEncoded(false);

        thenNoExceptionOccurred();
        thenThePacketOpcodeIs(Opcodes.Extended.COMPRESSEDPART.code());
        thenThePayloadIs("ab".repeat(16) + "00d00200" + "05000000" + "090807");
    }

    @Test
    void shouldEncodeAWideCompressedChunk() {
        givenACompressedChunk(184_320, 5, new byte[] {9, 8, 7});

        whenTheChunkIsEncoded(true);

        thenNoExceptionOccurred();
        thenThePacketOpcodeIs(Opcodes.Extended.COMPRESSEDPART_I64.code());
        thenThePacketLengthIs(16 + 8 + 4 + 3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRoundTripACompressedChunkInBothWidths(boolean wide) {
        givenACompressedChunk(184_320, 5, new byte[] {9, 8, 7});
        givenTheChunkIsEncoded(wide);

        whenTheChunkIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedChunkStartsAt(184_320);
        thenTheDecodedCompressedSizeIs(5);
        thenTheDecodedChunkCarries(9, 8, 7);
    }

    @Test
    void shouldRefuseACompressedChunkLargerThanTheCompressedSize() {
        givenACompressedChunk(0, 2, new byte[] {1, 2, 3});
        givenTheChunkIsEncoded(false);

        whenTheChunkIsDecoded();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseANarrowPartRequestBeyondFourGigabytes() {
        givenAPartRequest(range(FOUR_GIB - 10, FOUR_GIB + 10), range(0, 0), range(0, 0));

        whenTheRequestIsEncoded(false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseANarrowPartOfDataBeyondFourGigabytes() {
        givenAPartOfData(FOUR_GIB, FOUR_GIB + 1, new byte[] {1});

        whenThePartIsEncoded(false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseANarrowCompressedChunkBeyondFourGigabytes() {
        givenACompressedChunk(FOUR_GIB, 5, new byte[] {1});

        whenTheChunkIsEncoded(false);

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldCarryAPartRequestBeyondFourGigabytesInTheWideForm() throws DecodeException {
        givenAPartRequest(range(FOUR_GIB - 10, FOUR_GIB + 10), range(0, 0), range(0, 0));
        givenTheRequestIsEncoded(true);

        whenTheRequestIsDecoded();

        thenTheDecodedRequestIsTheSentOne();
    }

    @Test
    void shouldCarryAPartOfDataBeyondFourGigabytesInTheWideForm() {
        givenAPartOfData(FOUR_GIB, FOUR_GIB + 1, new byte[] {1});
        givenThePartIsEncoded(true);

        whenThePartIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedPartIsTheSentOne();
    }

    @Test
    void shouldCarryACompressedChunkBeyondFourGigabytesInTheWideForm() {
        givenACompressedChunk(FOUR_GIB, 5, new byte[] {1});
        givenTheChunkIsEncoded(true);

        whenTheChunkIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedChunkIsTheSentOne();
    }

    @Test
    void shouldCarryAPartOfDataEndingAtTheLastNarrowOffsetInTheNarrowForm() {
        givenAPartOfData(0xFFFF_FFFEL, 0xFFFF_FFFFL, new byte[] {1});
        givenThePartIsEncoded(false);

        whenThePartIsDecoded();

        thenNoExceptionOccurred();
        thenTheDecodedPartIsTheSentOne();
    }

    @Test
    void shouldReadANarrowRangeWhoseEndWrappedToZeroAsEndingAtFourGigabytes() throws DecodeException {
        givenANarrowRequestOfTheRange(FOUR_GIB - Ed2k.EMBLOCKSIZE, 0);

        whenTheRequestIsDecoded();

        thenTheRequestedRangesAre(new ClientMessages.Range(FOUR_GIB - Ed2k.EMBLOCKSIZE, FOUR_GIB));
    }

    @Test
    void shouldReadANarrowRangeEndingBeforeItsStartAsNothing() throws DecodeException {
        givenANarrowRequestOfTheRange(5000, 4000);

        whenTheRequestIsDecoded();

        thenTheRequestedRangesAre();
    }

    @Test
    void shouldRefuseAFileStatusWhosePartCountDoesNotFitSixteenBits() {
        givenAFileStatusOfParts(0x10000);

        whenTheFileStatusIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseAHashSetWhosePartCountDoesNotFitSixteenBits() {
        givenAHashSetOf(Collections.nCopies(0x10000, FILE));

        whenTheHashSetIsEncoded();

        thenExceptionOccurred(IllegalArgumentException.class);
    }

    @Test
    void shouldRoundTripAFileName() throws DecodeException {
        givenTheFileName("ubuntu.iso");
        givenTheFileNameIsEncoded();

        whenTheFileNameIsDecoded();

        thenTheDecodedFileNameIsTheSentOne();
    }

    @Test
    void shouldRoundTripAHashSet() throws DecodeException {
        givenAHashSetOf(List.of(FILE, Hash16.ZERO));
        givenTheHashSetIsEncoded();

        whenTheHashSetIsDecoded();

        thenTheDecodedHashSetIsTheSentOne();
    }

    @Test
    void shouldDecodeAQueueRank() throws DecodeException {
        givenThePacket(Protocol.EDONKEY, 0x5C, "2a000000");

        whenTheQueueRankIsDecoded();

        thenTheQueueRankIs(42);
    }

    @Test
    void shouldDecodeAQueueRankingOfTheExtendedProtocol() throws DecodeException {
        givenThePacket(Protocol.EMULE, 0x60, "0700" + "00".repeat(10));

        whenTheQueueRankIsDecoded();

        thenTheQueueRankIs(7);
    }

    @Test
    void shouldReadTheFileOfAFileRequest() throws DecodeException {
        givenARequestForTheFile(Opcodes.Client.SETREQFILEID, FILE);

        whenTheFileIsRead();

        thenTheFileIs(FILE);
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbage(@ForAll @Size(max = 120) byte[] data) {
        givenTheGarbage(data);

        whenEveryDecoderReadsItInBothProtocols();

        thenOnlyDecodeExceptionsOccurred();
    }

    private static ClientMessages.Range range(long start, long end) {
        return new ClientMessages.Range(start, end);
    }

    private void givenAFileStatusOfParts(int partCount, int... parts) {
        BitSet bits = new BitSet();
        for (int part : parts) {
            bits.set(part);
        }
        this.status = new ClientMessages.FileStatus(FILE, partCount, bits);
    }

    private void givenAFileStatusOfRandomParts(int partCount, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        BitSet bits = new BitSet();
        for (int part = 0; part < partCount; part++) {
            if (random.nextBoolean()) {
                bits.set(part);
            }
        }
        this.status = new ClientMessages.FileStatus(FILE, partCount, bits);
    }

    private void givenThePaddingBitsOfItsLastByteAreSet() {
        byte[] payload = this.packet.payload();
        payload[payload.length - 1] |= (byte) 0xFC;
        this.packet = Packet.of(this.packet.protocol(), this.packet.opcode(), payload);
    }

    private void givenTheFileStatusIsEncoded() {
        this.packet = this.status.encode();
    }

    private void givenAPartRequest(ClientMessages.Range... ranges) {
        this.sentRequest = new ClientMessages.RequestParts(FILE, List.of(ranges));
    }

    private void givenTheRequestIsEncoded(boolean wide) {
        this.packet = this.sentRequest.encode(wide);
    }

    private void givenANarrowRequestOfTheRange(long start, long end) {
        this.packet = Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.REQUESTPARTS.code(),
                new PacketWriter()
                        .hash16(FILE)
                        .u32(start)
                        .u32(0)
                        .u32(0)
                        .u32(end)
                        .u32(0)
                        .u32(0));
    }

    private void givenAPartOfData(long start, long end, byte[] data) {
        this.part = new ClientMessages.SendingPart(FILE, start, end, data);
    }

    private void givenThePartIsEncoded(boolean wide) {
        this.packet = this.part.encode(wide);
    }

    private void givenACompressedChunk(long blockStart, long compressedSize, byte[] data) {
        this.chunk = new ClientMessages.CompressedPart(FILE, blockStart, compressedSize, data);
    }

    private void givenTheChunkIsEncoded(boolean wide) {
        this.packet = this.chunk.encode(wide);
    }

    private void givenThePacket(int protocol, int opcode, String payloadHex) {
        this.packet = Packet.of(protocol, opcode, HEX.parseHex(payloadHex));
    }

    private void givenTheFileName(String name) {
        this.fileName = new ClientMessages.FileName(FILE, name);
    }

    private void givenTheFileNameIsEncoded() {
        this.packet = this.fileName.encode();
    }

    private void givenAHashSetOf(List<Hash16> parts) {
        this.hashSet = new ClientMessages.HashSet(FILE, parts);
    }

    private void givenTheHashSetIsEncoded() {
        this.packet = this.hashSet.encode();
    }

    private void givenARequestForTheFile(Opcodes.Client opcode, Hash16 requested) {
        this.packet = ClientMessages.forFile(opcode, requested);
    }

    private void givenTheGarbage(byte[] data) {
        this.garbage = data;
    }

    private void whenTheFileStatusIsEncoded() {
        this.exception = catchThrowable(() -> this.packet = this.status.encode());
    }

    private void whenTheFileStatusIsDecoded() throws DecodeException {
        this.decodedStatus = ClientMessages.FileStatus.decode(this.packet);
    }

    private void whenItIsCheckedAgainstTheSize(long size) {
        this.fits = this.status.fits(size);
    }

    private void whenTheRequestIsEncoded(boolean wide) {
        this.exception = catchThrowable(() -> this.packet = this.sentRequest.encode(wide));
    }

    private void whenTheRequestIsDecoded() throws DecodeException {
        this.request = ClientMessages.RequestParts.decode(this.packet);
    }

    private void whenThePartIsEncoded(boolean wide) {
        this.exception = catchThrowable(() -> this.packet = this.part.encode(wide));
    }

    private void whenThePartIsDecoded() {
        this.exception = catchThrowable(() -> this.decodedPart = ClientMessages.SendingPart.decode(this.packet));
    }

    private void whenTheChunkIsEncoded(boolean wide) {
        this.exception = catchThrowable(() -> this.packet = this.chunk.encode(wide));
    }

    private void whenTheChunkIsDecoded() {
        this.exception = catchThrowable(() -> this.decodedChunk = ClientMessages.CompressedPart.decode(this.packet));
    }

    private void whenTheFileNameIsDecoded() throws DecodeException {
        this.decodedFileName = ClientMessages.FileName.decode(this.packet);
    }

    private void whenTheHashSetIsEncoded() {
        this.exception = catchThrowable(() -> this.packet = this.hashSet.encode());
    }

    private void whenTheHashSetIsDecoded() throws DecodeException {
        this.decodedHashSet = ClientMessages.HashSet.decode(this.packet);
    }

    private void whenTheQueueRankIsDecoded() throws DecodeException {
        this.queueRank = ClientMessages.decodeQueueRank(this.packet);
    }

    private void whenTheFileIsRead() throws DecodeException {
        this.file = ClientMessages.fileOf(this.packet);
    }

    private void whenEveryDecoderReadsItInBothProtocols() {
        byte[] data = this.garbage;
        this.exception = catchThrowable(() -> {
            for (int protocol : new int[] {Protocol.EDONKEY, Protocol.EMULE}) {
                Packet received = Packet.of(protocol, 0x50, data);
                quietly(() -> ClientMessages.FileStatus.decode(received));
                quietly(() -> ClientMessages.HashSet.decode(received));
                quietly(() -> ClientMessages.RequestParts.decode(received));
                quietly(() -> ClientMessages.SendingPart.decode(received));
                quietly(() -> ClientMessages.CompressedPart.decode(received));
                quietly(() -> ClientMessages.FileName.decode(received));
                quietly(() -> ClientMessages.decodeQueueRank(received));
            }
        });
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenThePayloadIs(String hex) {
        assertThat(HEX.formatHex(this.packet.payload())).isEqualTo(hex);
    }

    private void thenThePacketOpcodeIs(int opcode) {
        assertThat(this.packet.opcode()).isEqualTo(opcode);
    }

    private void thenThePacketProtocolIs(int protocol) {
        assertThat(this.packet.protocol()).isEqualTo(protocol);
    }

    private void thenThePacketLengthIs(int length) {
        assertThat(this.packet.length()).isEqualTo(length);
    }

    private void thenTheDecodedStatusIsTheEncodedOne() {
        assertThat(this.decodedStatus.partCount()).isEqualTo(this.status.partCount());
        assertThat(this.decodedStatus.parts()).isEqualTo(this.status.parts());
    }

    private void thenTheDecodedStatusHasPart(int index) {
        assertThat(this.decodedStatus.has(index)).isTrue();
    }

    private void thenTheDecodedStatusLacksPart(int index) {
        assertThat(this.decodedStatus.has(index)).isFalse();
    }

    private void thenTheDecodedStatusHasDataPartsForTheSize(long size, int count) {
        assertThat(this.decodedStatus.dataParts(size).cardinality()).isEqualTo(count);
    }

    private void thenItFits() {
        assertThat(this.fits).isTrue();
    }

    private void thenItFitsAs(String description) {
        assertThat(this.fits).as(description).isTrue();
    }

    private void thenItDoesNotFit() {
        assertThat(this.fits).isFalse();
    }

    private void thenTheDecodedRequestIsTheSentOne() {
        assertThat(this.request).isEqualTo(this.sentRequest);
    }

    private void thenTheRequestedRangesAre(ClientMessages.Range... expected) {
        assertThat(this.request.ranges().stream().filter(r -> !r.isEmpty())).containsExactly(expected);
    }

    private void thenTheDecodedPartSpans(long start, long end) {
        assertThat(this.decodedPart.start()).isEqualTo(start);
        assertThat(this.decodedPart.end()).isEqualTo(end);
    }

    private void thenTheDecodedPartCarries(int... data) {
        assertThat(this.decodedPart.data()).containsExactly(data);
    }

    private void thenTheDecodedPartIsTheSentOne() {
        assertThat(this.decodedPart).isEqualTo(this.part);
    }

    private void thenTheDecodedChunkStartsAt(long blockStart) {
        assertThat(this.decodedChunk.blockStart()).isEqualTo(blockStart);
    }

    private void thenTheDecodedCompressedSizeIs(long compressedSize) {
        assertThat(this.decodedChunk.compressedSize()).isEqualTo(compressedSize);
    }

    private void thenTheDecodedChunkCarries(int... data) {
        assertThat(this.decodedChunk.chunk()).containsExactly(data);
    }

    private void thenTheDecodedChunkIsTheSentOne() {
        assertThat(this.decodedChunk).isEqualTo(this.chunk);
    }

    private void thenTheDecodedFileNameIsTheSentOne() {
        assertThat(this.decodedFileName).isEqualTo(this.fileName);
    }

    private void thenTheDecodedHashSetIsTheSentOne() {
        assertThat(this.decodedHashSet).isEqualTo(this.hashSet);
    }

    private void thenTheQueueRankIs(long rank) {
        assertThat(this.queueRank).isEqualTo(rank);
    }

    private void thenTheFileIs(Hash16 expected) {
        assertThat(this.file).isEqualTo(expected);
    }

    private void thenOnlyDecodeExceptionsOccurred() {
        assertThat(this.exception).isNull();
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
