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
package org.njmule.core.peer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

@Timeout(30)
class SharedFilesBrowserTest {

    private static final Hash16 FILE = Hash16.parseHex("0F".repeat(16));
    private static final Hello US = Hello.of(Hash16.parseHex("01".repeat(16)), 0, 4662, "us", 0, 0, 0, 0, 0, 0);
    private static final Hello THEM = Hello.of(Hash16.parseHex("02".repeat(16)), 0, 4662, "them", 0, 0, 0, 0, 0, 0);

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final SharedFilesBrowser browser = new SharedFilesBrowser(this.loop, () -> US, new SecureRandom());

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void anExtendedPacketWithTheOpcodeOfARefusalIsNotOne() throws Exception {
        List<SearchResult> files = browse(
                Packet.empty(Protocol.EMULE, Opcodes.Client.ASKSHAREDDENIEDANS.code()),
                Packet.of(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), list(FILE, "film.avi", 700)));

        assertThat(files).singleElement().satisfies(f -> {
            assertThat(f.hash()).isEqualTo(FILE);
            assertThat(f.name()).isEqualTo("film.avi");
            assertThat(f.size()).isEqualTo(700);
        });
    }

    @Test
    void aRefusalFailsTheBrowse() {
        assertThatThrownBy(() -> browse(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDDENIEDANS.code())))
                .hasCauseInstanceOf(IOException.class)
                .hasMessageContaining("does not show its files");
    }

    @Test
    void aListWithMoreEntriesThanItHoldsFailsTheBrowse() {
        byte[] one = list(FILE, "one.bin", 1).toByteArray();
        PacketWriter list = new PacketWriter().u32(5).bytes(one, 4, one.length - 4);

        assertThatThrownBy(() -> browse(Packet.of(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), list)))
                .hasCauseInstanceOf(DecodeException.class);
    }

    @Test
    void entriesWithoutANameOrASizeAreLeftOut() throws Exception {
        PacketWriter list = new PacketWriter().u32(3);
        new FileEntry(FILE, 0, 0, List.of(new Tag.IntTag(TagName.id(FileTag.FILESIZE), 10))).write(list);
        new FileEntry(FILE, 0, 0, List.of(new Tag.StringTag(TagName.id(FileTag.FILENAME), "no size"))).write(list);
        new FileEntry(
                        FILE,
                        0,
                        0,
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), "empty"),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), 0)))
                .write(list);

        assertThat(browse(Packet.of(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), list)))
                .isEmpty();
    }

    // A client that takes our hello, answers it, then sends {@code answers} to our request.
    private List<SearchResult> browse(Packet... answers) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<List<SearchResult>> files =
                    this.browser.browse(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
            try (Socket client = server.accept()) {
                client.setSoTimeout(10_000);
                InputStream in = new BufferedInputStream(client.getInputStream());
                OutputStream out = client.getOutputStream();
                assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 16)).opcode())
                        .isEqualTo(Opcodes.Client.HELLO.code());
                out.write(TcpFraming.encode(THEM.encode(true)));
                assertThat(TcpFraming.read(in, FrameLimit.fixed(1 << 16)).opcode())
                        .isEqualTo(Opcodes.Client.ASKSHAREDFILES.code());
                for (Packet answer : answers) {
                    out.write(TcpFraming.encode(answer));
                }
                out.flush();
                return files.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static PacketWriter list(Hash16 hash, String name, long size) {
        PacketWriter out = new PacketWriter().u32(1);
        new FileEntry(
                        hash,
                        0,
                        0,
                        List.of(
                                new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                                new Tag.IntTag(TagName.id(FileTag.FILESIZE), size)))
                .write(out);
        return out;
    }
}
