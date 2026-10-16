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
package org.njmule.core.upload;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.njmule.core.api.Hash16;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;

/** Reading requested blocks from disk and cutting them into data packets. */
final class Blocks {

    /** Bytes of file data per packet. */
    static final int CHUNK = 10_240;

    // A shorter rest is sent in one packet rather than as a small last one.
    private static final int LAST_CHUNK = 13_000;
    private static final int ZLIB_LEVEL = 1;
    private static final ExecutorService DISK = Executors.newVirtualThreadPerTaskExecutor();

    private Blocks() {}

    /** Reads {@code [start, start + length)} of {@code file}, off the calling thread. */
    static CompletableFuture<byte[]> read(Path file, long start, int length) {
        return CompletableFuture.supplyAsync(
                () -> {
                    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                        ByteBuffer buffer = ByteBuffer.allocate(length);
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffer, start + buffer.position()) < 0) {
                                throw new EOFException("file shorter than the block " + start);
                            }
                        }
                        return buffer.array();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                DISK);
    }

    /**
     * Reads {@code [start, start + length)} of {@code data} and cuts it into the packets of {@code file}, off the
     * caller.
     */
    static CompletableFuture<List<Packet>> readPackets(
            Path data, Hash16 file, long start, int length, boolean compress) {
        return read(data, start, length).thenApplyAsync(bytes -> packets(file, start, bytes, compress), DISK);
    }

    /**
     * The packets carrying {@code data} of {@code file} from {@code start}: zlib-compressed when asked and smaller,
     * 64-bit offsets when the block ends above 4 GB.
     */
    static List<Packet> packets(Hash16 file, long start, byte[] data, boolean compress) {
        boolean wide = start + data.length > 0xFFFF_FFFFL;
        List<Packet> packets = new ArrayList<>(data.length / CHUNK + 1);
        if (compress && Packing.shrinks(data, ZLIB_LEVEL)) {
            byte[] stream = Packing.deflate(data, ZLIB_LEVEL);
            if (stream.length < data.length) {
                for (int at = 0; at < stream.length; at = next(at, stream.length)) {
                    byte[] chunk = Arrays.copyOfRange(stream, at, next(at, stream.length));
                    packets.add(new ClientMessages.CompressedPart(file, start, stream.length, chunk).encode(wide));
                }
                return packets;
            }
        }
        for (int at = 0; at < data.length; at = next(at, data.length)) {
            int end = next(at, data.length);
            packets.add(new ClientMessages.SendingPart(file, start + at, start + end, Arrays.copyOfRange(data, at, end))
                    .encode(wide));
        }
        return packets;
    }

    private static int next(int at, int length) {
        return length - at < LAST_CHUNK ? length : at + CHUNK;
    }
}
