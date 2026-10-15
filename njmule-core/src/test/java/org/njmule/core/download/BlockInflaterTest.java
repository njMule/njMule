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
package org.njmule.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.protocol.io.DecodeException;

class BlockInflaterTest {

    private byte[] block;
    private byte[] stream;
    private BlockInflater inflater;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final List<Long> positionsBeforeChunks = new ArrayList<>();
    private final List<Integer> outputSizesBeforeChunks = new ArrayList<>();
    private byte[] inflated;
    private Throwable exception;

    @AfterEach
    void closeTheInflater() {
        if (this.inflater != null) {
            this.inflater.close();
        }
    }

    @Test
    void shouldInflateAStreamSplitIntoChunks() throws DecodeException {
        givenARandomBlock(184_320, 7);
        givenTheBlockFilledWith(50_000, 120_000, (byte) 'x');
        givenTheBlockDeflated();
        givenAnInflaterAt(1_000_000, 184_320);

        whenTheStreamIsInflatedInChunksOf(10_240);

        thenThePositionFollowedTheOutputFrom(1_000_000);
        thenTheInflaterIsFinished();
        thenTheOutputIsTheBlock();
    }

    // A long repeat at the end leaves output pending in zlib once all input is read.
    @ParameterizedTest(name = "round {0}")
    @MethodSource("blocksEndingInLongRepeats")
    void shouldGiveEveryByteOfBlocksThatEndInLongRepeats(int round, byte[] repeating) throws DecodeException {
        givenTheBlock(repeating);
        givenTheBlockDeflated();
        givenAnInflaterAt(0, 184_320);

        whenTheStreamIsInflatedInChunksOf(10_240);

        thenTheInflaterIsFinished();
        thenTheOutputIsTheBlock();
    }

    @Test
    void shouldRefuseOutputBeyondTheRequestedBlock() {
        givenTheBlock(new byte[1_000_000]);
        givenTheBlockDeflated();
        givenAnInflaterAt(0, 184_320);

        whenTheStreamIsInflatedAtOnce();

        thenExceptionOccurred(DecodeException.class, "184320");
    }

    @Test
    void shouldRefuseCorruptStreams() {
        givenTheStream(new byte[] {1, 2, 3, 4, 5, 6});
        givenAnInflaterAt(0, 1000);

        whenTheStreamIsInflatedAtOnce();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldTakeAStreamOneByteAtATime() throws DecodeException {
        givenARandomBlock(5000, 9);
        givenTheBlockDeflated();
        givenAnInflaterAt(0, 5000);
        givenItGaveNothingForAnEmptyChunk();

        whenTheStreamIsInflatedInChunksOf(1);

        thenTheInflaterIsFinished();
        thenTheOutputIsTheBlock();
    }

    @Test
    void shouldGiveNothingAfterTheEndOfTheBlock() throws DecodeException {
        givenARandomBlock(5000, 9);
        givenTheBlockDeflated();
        givenAnInflaterAt(0, 5000);
        givenTheWholeStreamInflatedInChunksOf(1);

        whenItInflates(new byte[] {1, 2, 3});

        thenItGaveNothingAfterTheEnd();
    }

    @Test
    void shouldTakeUpToExactlyTheBlock() {
        givenARandomBlock(5000, 9);
        givenTheBlockDeflated();
        givenAnInflaterAt(0, 4999);

        whenTheStreamIsInflatedAtOnce();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseStreamsWithoutTheZlibWrapper() {
        givenARawDeflateStreamOfZeroBytes(1000);
        givenAnInflaterAt(0, 1000);

        whenTheStreamIsInflatedAtOnce();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseStreamsWithADictionary() {
        givenAStreamOfZeroBytesDeflatedWithADictionary(1000);
        givenAnInflaterAt(0, 1000);

        whenTheStreamIsInflatedAtOnce();

        thenExceptionOccurred(DecodeException.class, "dictionary");
    }

    static Stream<Arguments> blocksEndingInLongRepeats() {
        Random random = new Random(3);
        return IntStream.range(0, 200).mapToObj(round -> {
            byte[] block = new byte[184_320];
            int noise = random.nextInt(block.length);
            for (int i = 0; i < noise; i++) {
                block[i] = (byte) random.nextInt();
            }
            Arrays.fill(block, noise, block.length, (byte) random.nextInt());
            return Arguments.of(round, block);
        });
    }

    private void givenARandomBlock(int size, long seed) {
        this.block = new byte[size];
        new Random(seed).nextBytes(this.block);
    }

    private void givenTheBlock(byte[] bytes) {
        this.block = bytes;
    }

    private void givenTheBlockFilledWith(int from, int to, byte value) {
        Arrays.fill(this.block, from, to, value);
    }

    private void givenTheBlockDeflated() {
        this.stream = deflate(this.block);
    }

    private void givenTheStream(byte[] bytes) {
        this.stream = bytes;
    }

    private void givenARawDeflateStreamOfZeroBytes(int length) {
        Deflater raw = new Deflater(1, true);
        raw.setInput(new byte[length]);
        raw.finish();
        byte[] buffer = new byte[2000];
        this.stream = Arrays.copyOf(buffer, raw.deflate(buffer));
        raw.end();
    }

    private void givenAStreamOfZeroBytesDeflatedWithADictionary(int length) {
        Deflater withDictionary = new Deflater(1);
        withDictionary.setDictionary(new byte[] {1, 2, 3, 4});
        withDictionary.setInput(new byte[length]);
        withDictionary.finish();
        byte[] buffer = new byte[2000];
        this.stream = Arrays.copyOf(buffer, withDictionary.deflate(buffer));
        withDictionary.end();
    }

    private void givenAnInflaterAt(long position, int blockLength) {
        this.inflater = new BlockInflater(position, blockLength);
    }

    private void givenItGaveNothingForAnEmptyChunk() throws DecodeException {
        assertThat(this.inflater.inflate(new byte[0])).isEmpty();
    }

    private void givenTheWholeStreamInflatedInChunksOf(int size) throws DecodeException {
        inflateInChunksOf(size);
        assertThat(this.inflater.finished()).isTrue();
    }

    private void whenTheStreamIsInflatedInChunksOf(int size) throws DecodeException {
        inflateInChunksOf(size);
    }

    private void whenTheStreamIsInflatedAtOnce() {
        this.exception = catchThrowable(() -> this.output.writeBytes(this.inflater.inflate(this.stream)));
    }

    private void whenItInflates(byte[] input) throws DecodeException {
        this.inflated = this.inflater.inflate(input);
    }

    private void thenThePositionFollowedTheOutputFrom(long start) {
        assertThat(this.positionsBeforeChunks).isNotEmpty();
        for (int i = 0; i < this.positionsBeforeChunks.size(); i++) {
            assertThat(this.positionsBeforeChunks.get(i)).isEqualTo(start + this.outputSizesBeforeChunks.get(i));
        }
    }

    private void thenTheInflaterIsFinished() {
        assertThat(this.inflater.finished()).isTrue();
    }

    private void thenTheOutputIsTheBlock() {
        assertThat(this.output.toByteArray()).isEqualTo(this.block);
    }

    private void thenItGaveNothingAfterTheEnd() {
        assertThat(this.inflated).as("after the end").isEmpty();
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private void inflateInChunksOf(int size) throws DecodeException {
        for (int at = 0; at < this.stream.length; at += size) {
            this.positionsBeforeChunks.add(this.inflater.position());
            this.outputSizesBeforeChunks.add(this.output.size());
            byte[] chunk = Arrays.copyOfRange(this.stream, at, Math.min(this.stream.length, at + size));
            this.output.writeBytes(this.inflater.inflate(chunk));
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(1);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
