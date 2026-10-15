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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;

class FramingTest {

    private static final HexFormat HEX = HexFormat.of();

    private Packet packet;
    private byte[] frame;
    private InputStream in;
    private FrameLimit limit;
    private Packet read;
    private byte[] datagram;
    private Packet packed;
    private Packet unpacked;
    private byte[] deflated;
    private byte[] inflated;
    private Throwable exception;

    @Test
    void shouldCountTheOpcodeInTheTcpLength() {
        givenThePacket(Protocol.EDONKEY, 0x01, new byte[] {0x10});

        whenItIsFramedForTcp();

        thenTheFrameIs("e3020000000110");
    }

    @Test
    void shouldReadConsecutiveFramesAndStopCleanlyAtTheEnd() throws IOException {
        givenAStreamOf("e3020000000110" + "c5010000004c");
        givenTheFrameLimit(FrameLimit.fixed(10));
        givenTheNextFrameReadIs(Packet.of(Protocol.EDONKEY, 0x01, new byte[] {0x10}));
        givenTheNextFrameReadIs(Packet.empty(Protocol.EMULE, 0x4C));

        whenTheNextFrameIsRead();

        thenExceptionOccurred(EOFException.class);
    }

    @Test
    void shouldRejectAnOversizedFrameBeforeReadingIt() {
        // Announces 2^32-1 bytes: rejected from the header alone.
        givenAStreamOf("e3ffffffff01");
        givenTheFrameLimit(FrameLimit.fixed(4096));

        whenTheNextFrameIsRead();

        thenExceptionOccurred(DecodeException.class, "exceeds 4096");
    }

    @Test
    void shouldRejectAnUnknownProtocol() {
        givenAStreamOf("e4020000000110");
        givenTheFrameLimit(FrameLimit.fixed(10));

        whenTheNextFrameIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRejectAZeroLength() {
        givenAStreamOf("e30000000001");
        givenTheFrameLimit(FrameLimit.fixed(10));

        whenTheNextFrameIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRejectADisallowedOpcode() {
        givenAStreamOf("e3020000004c10");
        givenAFrameLimitAllowingOnlyTheOpcode(0x01);

        whenTheNextFrameIsRead();

        thenExceptionOccurred(DecodeException.class, "not allowed");
    }

    @Test
    void shouldRejectAStreamEndingInsideAFrame() {
        givenAStreamOf("e305000000011011");
        givenTheFrameLimit(FrameLimit.fixed(10));

        whenTheNextFrameIsRead();

        thenExceptionOccurred(DecodeException.class, "inside a frame");
    }

    @Test
    void shouldRejectAUdpDatagramShorterThanAHeader() {
        givenTheDatagram("e3");

        whenTheDatagramIsDecodedFrom(0, 1);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldDecodeAUdpDatagramOfAHeaderAlone() {
        givenTheDatagram("e396");

        whenTheDatagramIsDecodedFrom(0, 2);

        thenThePacketReadIs(Packet.empty(Protocol.EDONKEY, 0x96));
    }

    @Test
    void shouldDecodeAUdpDatagramInsideTheBuffer() {
        givenTheDatagram("00e39601");

        whenTheDatagramIsDecodedFrom(1, 3);

        thenThePacketReadIs(Packet.of(Protocol.EDONKEY, 0x96, new byte[] {1}));
    }

    @Test
    void shouldNotReadAUdpDatagramPastTheBuffer() {
        givenTheDatagram("00e39601");

        whenTheDatagramIsDecodedFrom(2, 4);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldNotReadAUdpDatagramBeforeTheBuffer() {
        givenTheDatagram("00e39601");

        whenTheDatagramIsDecodedFrom(-1, 3);

        thenExceptionOccurred(IndexOutOfBoundsException.class);
    }

    @Test
    void shouldPackALargePacketIntoASmallerOne() {
        givenThePacket(Protocol.EMULE, 0x40, new byte[4000]);

        whenItIsPacked();

        thenThePackedProtocolIs(Protocol.PACKED);
        thenThePackedLengthIsBelow(100);
    }

    @Test
    void shouldUnpackToTheEmuleProtocol() {
        givenThePacket(Protocol.EMULE, 0x40, new byte[4000]);
        givenItIsPacked();

        whenItIsUnpackedWithTheCap(4000);

        thenTheUnpackedPacketIsTheOriginal();
    }

    @Test
    void shouldNotPackASmallPacket() {
        givenThePacket(Protocol.EMULE, 0x40, new byte[] {1, 2, 3});

        whenItIsPacked();

        thenThePackedPacketIsTheOriginalItself();
    }

    @Test
    void shouldUnpackAKadPacketToTheKadProtocol() {
        givenThePacket(Protocol.KAD, 0x3B, new byte[500]);
        givenItIsPacked();

        whenItIsUnpackedWithTheCap(500);

        thenTheUnpackedProtocolIs(Protocol.KAD);
    }

    @Test
    void shouldPackAServerFrame() {
        givenThePacket(Protocol.EDONKEY, 0x33, new byte[2000]);

        whenItIsPacked();

        thenThePackedProtocolIs(Protocol.PACKED);
    }

    @Test
    void shouldUnpackAPackedServerFrameToItsEdonkeyMessage() throws DecodeException {
        givenThePacket(Protocol.EDONKEY, 0x33, new byte[2000]);
        givenItIsPacked();

        whenItIsUnpackedFromAServer();

        thenTheUnpackedPacketIsTheOriginal();
    }

    @Test
    void shouldLeaveAServerFrameThatIsNotPackedAsItIs() throws DecodeException {
        givenThePacket(Protocol.EDONKEY, 0x33, new byte[2000]);
        givenItIsSentUnpacked();

        whenItIsUnpackedFromAServer();

        thenTheUnpackedPacketIsTheOriginalItself();
    }

    @Test
    void shouldStopUnpackingADecompressionBombAtTheCap() {
        // 1 MiB of zeros packs to about 1 KiB: a decompression bomb for a 50 kB cap.
        givenThePacket(Protocol.EMULE, 0x40, new byte[1 << 20]);
        givenItIsPacked();

        whenItIsUnpackedWithTheCap(Packing.MAX_CLIENT_UNPACKED);

        thenExceptionOccurred(DecodeException.class, "exceeds");
    }

    @Test
    void shouldRefuseToInflateCorruptData() {
        givenTheDeflatedData("789c0102");

        whenItIsInflatedWithTheCap(100);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldInflateExactlyTheCap() {
        givenDeflatedZeros(1000);

        whenItIsInflatedWithTheCap(1000);

        thenTheInflatedDataHasSize(1000);
    }

    @Test
    void shouldRefuseToInflatePastTheCap() {
        givenDeflatedZeros(1000);

        whenItIsInflatedWithTheCap(999);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldInflateNothingWithinANullCap() {
        givenDeflatedZeros(0);

        whenItIsInflatedWithTheCap(0);

        thenTheInflatedDataIsEmpty();
    }

    @Test
    void shouldRefuseToInflateNoData() {
        givenTheDeflatedData("");

        whenItIsInflatedWithTheCap(100);

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseToInflateDataCutBeforeItsChecksum() {
        givenDeflatedZeros(1000);
        givenItsLastBytesAreCut(4);

        whenItIsInflatedWithTheCap(1000);

        thenExceptionOccurredBecause(DecodeException.class, "cut before its checksum");
    }

    @Test
    void shouldRefuseAPresetDictionary() {
        // A preset dictionary (FDICT) cannot be supplied.
        givenTheDeflatedData("78bb00000001");

        whenItIsInflatedWithTheCap(100);

        thenExceptionOccurred(DecodeException.class);
    }

    @Property
    void shouldRoundTripTcpFrames(
            @ForAll @IntRange(min = 0, max = 2) int which,
            @ForAll @IntRange(max = 255) int opcode,
            @ForAll @Size(max = 300) byte[] payload) {
        givenAPacketOfTheTcpProtocol(which, opcode, payload);
        givenAStreamOfItsTcpFrame();
        givenTheFrameLimit(FrameLimit.fixed(300));

        whenTheNextFrameIsRead();

        thenTheFrameReadIsThePacket();
    }

    @Property
    void shouldOnlyFailWithIoExceptionsOnGarbageStreams(@ForAll @Size(max = 40) byte[] data) {
        givenAStreamOfTheBytes(data);
        givenTheFrameLimit(FrameLimit.fixed(16));

        whenUpToTenFramesAreRead();

        thenOnlyAnIoExceptionOccurred();
    }

    @Property
    void shouldNeverInflateGarbagePastTheCap(@ForAll @Size(max = 64) byte[] data) {
        givenTheDeflatedBytes(data);

        whenItIsInflatedWithTheCap(1000);

        thenItIsInflatedWithinTheCapOrRefusedAsCorrupt(1000);
    }

    private void givenThePacket(int protocol, int opcode, byte[] payload) {
        this.packet = Packet.of(protocol, opcode, payload);
    }

    private void givenAPacketOfTheTcpProtocol(int which, int opcode, byte[] payload) {
        int protocol = new int[] {Protocol.EDONKEY, Protocol.EMULE, Protocol.PACKED}[which];
        this.packet = Packet.of(protocol, opcode, payload);
    }

    private void givenAStreamOf(String hex) {
        givenAStreamOfTheBytes(HEX.parseHex(hex));
    }

    private void givenAStreamOfTheBytes(byte[] data) {
        this.in = new ByteArrayInputStream(data);
    }

    private void givenAStreamOfItsTcpFrame() {
        givenAStreamOfTheBytes(TcpFraming.encode(this.packet));
    }

    private void givenTheFrameLimit(FrameLimit frameLimit) {
        this.limit = frameLimit;
    }

    private void givenAFrameLimitAllowingOnlyTheOpcode(int allowed) {
        this.limit = (p, op) -> op == allowed ? 10 : FrameLimit.REJECT;
    }

    private void givenTheNextFrameReadIs(Packet expected) throws IOException {
        assertThat(TcpFraming.read(this.in, this.limit)).isEqualTo(expected);
    }

    private void givenTheDatagram(String hex) {
        this.datagram = HEX.parseHex(hex);
    }

    private void givenItIsPacked() {
        this.packed = Packing.pack(this.packet);
    }

    private void givenItIsSentUnpacked() {
        this.packed = this.packet;
    }

    private void givenTheDeflatedData(String hex) {
        this.deflated = HEX.parseHex(hex);
    }

    private void givenTheDeflatedBytes(byte[] data) {
        this.deflated = data;
    }

    private void givenDeflatedZeros(int count) {
        this.deflated = Packing.deflate(new byte[count], 9);
    }

    private void givenItsLastBytesAreCut(int count) {
        this.deflated = Arrays.copyOf(this.deflated, this.deflated.length - count);
    }

    private void whenItIsFramedForTcp() {
        this.frame = TcpFraming.encode(this.packet);
    }

    private void whenTheNextFrameIsRead() {
        this.exception = catchThrowable(() -> this.read = TcpFraming.read(this.in, this.limit));
    }

    private void whenUpToTenFramesAreRead() {
        this.exception = catchThrowable(() -> {
            for (int i = 0; i < 10; i++) {
                TcpFraming.read(this.in, this.limit);
            }
        });
    }

    private void whenTheDatagramIsDecodedFrom(int offset, int length) {
        this.exception = catchThrowable(() -> this.read = UdpFraming.decode(this.datagram, offset, length));
    }

    private void whenItIsPacked() {
        this.packed = Packing.pack(this.packet);
    }

    private void whenItIsUnpackedWithTheCap(int maxUnpacked) {
        this.exception = catchThrowable(() -> this.unpacked = Packing.unpack(this.packed, maxUnpacked));
    }

    private void whenItIsUnpackedFromAServer() throws DecodeException {
        this.unpacked = Packing.unpackFromServer(this.packed);
    }

    private void whenItIsInflatedWithTheCap(int maxSize) {
        byte[] data = this.deflated;
        this.exception = catchThrowable(() -> this.inflated = Packing.inflate(data, maxSize));
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private void thenExceptionOccurredBecause(Class<? extends Exception> type, String description) {
        assertThat(this.exception).as(description).isInstanceOf(type);
    }

    private void thenTheFrameIs(String hex) {
        assertThat(HEX.formatHex(this.frame)).isEqualTo(hex);
    }

    private void thenThePacketReadIs(Packet expected) {
        assertThat(this.exception).isNull();
        assertThat(this.read).isEqualTo(expected);
    }

    private void thenTheFrameReadIsThePacket() {
        thenThePacketReadIs(this.packet);
    }

    private void thenThePackedProtocolIs(int protocol) {
        assertThat(this.packed.protocol()).isEqualTo(protocol);
    }

    private void thenThePackedLengthIsBelow(int length) {
        assertThat(this.packed.length()).isLessThan(length);
    }

    private void thenThePackedPacketIsTheOriginalItself() {
        assertThat(this.packed).isSameAs(this.packet);
    }

    private void thenTheUnpackedPacketIsTheOriginal() {
        assertThat(this.exception).isNull();
        assertThat(this.unpacked).isEqualTo(this.packet);
    }

    private void thenTheUnpackedPacketIsTheOriginalItself() {
        assertThat(this.unpacked).isSameAs(this.packet);
    }

    private void thenTheUnpackedProtocolIs(int protocol) {
        assertThat(this.exception).isNull();
        assertThat(this.unpacked.protocol()).isEqualTo(protocol);
    }

    private void thenTheInflatedDataHasSize(int size) {
        assertThat(this.exception).isNull();
        assertThat(this.inflated).hasSize(size);
    }

    private void thenTheInflatedDataIsEmpty() {
        assertThat(this.exception).isNull();
        assertThat(this.inflated).isEmpty();
    }

    private void thenOnlyAnIoExceptionOccurred() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(IOException.class);
        }
    }

    private void thenItIsInflatedWithinTheCapOrRefusedAsCorrupt(int maxSize) {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        } else {
            assertThat(this.inflated.length).isLessThanOrEqualTo(maxSize);
        }
    }
}
