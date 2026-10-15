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

    private Packet[] answers;
    private List<SearchResult> files;
    private Exception exception;

    @AfterEach
    void stop() {
        this.loop.close();
    }

    @Test
    void shouldNotTakeAnExtendedPacketWithTheOpcodeOfARefusalForOne() {
        givenAPeerAnswering(refusal(Protocol.EMULE), filesAnswer(list(FILE, "film.avi", 700)));

        whenItsFilesAreBrowsed();

        thenTheOnlyFileIs(FILE, "film.avi", 700);
    }

    @Test
    void shouldFailTheBrowseOnARefusal() {
        givenAPeerAnswering(refusal(Protocol.EDONKEY));

        whenItsFilesAreBrowsed();

        thenTheBrowseFailedBecauseOf(IOException.class, "does not show its files");
    }

    @Test
    void shouldFailTheBrowseOnAListWithMoreEntriesThanItHolds() {
        givenAPeerAnswering(filesAnswer(claimingEntries(5, list(FILE, "one.bin", 1))));

        whenItsFilesAreBrowsed();

        thenTheBrowseFailedBecauseOf(DecodeException.class);
    }

    @Test
    void shouldLeaveOutEntriesWithoutANameOrASize() {
        givenAPeerAnswering(
                filesAnswer(entries(entry(size(10)), entry(name("no size")), entry(name("empty"), size(0)))));

        whenItsFilesAreBrowsed();

        thenNoFileIsListed();
    }

    private void givenAPeerAnswering(Packet... packets) {
        this.answers = packets;
    }

    private void whenItsFilesAreBrowsed() {
        try {
            this.files = browse(this.answers);
        } catch (Exception e) {
            this.exception = e;
        }
    }

    private void thenTheOnlyFileIs(Hash16 hash, String name, long size) {
        assertThat(this.exception).isNull();
        assertThat(this.files).singleElement().satisfies(f -> {
            assertThat(f.hash()).isEqualTo(hash);
            assertThat(f.name()).isEqualTo(name);
            assertThat(f.size()).isEqualTo(size);
        });
    }

    private void thenNoFileIsListed() {
        assertThat(this.exception).isNull();
        assertThat(this.files).isEmpty();
    }

    private void thenTheBrowseFailedBecauseOf(Class<? extends Exception> cause) {
        assertThat(this.exception).hasCauseInstanceOf(cause);
    }

    private void thenTheBrowseFailedBecauseOf(Class<? extends Exception> cause, String messagePart) {
        assertThat(this.exception).hasCauseInstanceOf(cause).hasMessageContaining(messagePart);
    }

    // A client that takes our hello, answers it, then sends {@code answers} to our request.
    private List<SearchResult> browse(Packet... answers) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<List<SearchResult>> listed =
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
                return listed.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static Packet refusal(int protocol) {
        return Packet.empty(protocol, Opcodes.Client.ASKSHAREDDENIEDANS.code());
    }

    private static Packet filesAnswer(PacketWriter list) {
        return Packet.of(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILESANSWER.code(), list);
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

    // The entries of a list under another count.
    private static PacketWriter claimingEntries(int count, PacketWriter list) {
        byte[] bytes = list.toByteArray();
        return new PacketWriter().u32(count).bytes(bytes, 4, bytes.length - 4);
    }

    private static PacketWriter entries(FileEntry... entries) {
        PacketWriter out = new PacketWriter().u32(entries.length);
        for (FileEntry entry : entries) {
            entry.write(out);
        }
        return out;
    }

    private static FileEntry entry(Tag... tags) {
        return new FileEntry(FILE, 0, 0, List.of(tags));
    }

    private static Tag name(String name) {
        return new Tag.StringTag(TagName.id(FileTag.FILENAME), name);
    }

    private static Tag size(long size) {
        return new Tag.IntTag(TagName.id(FileTag.FILESIZE), size);
    }
}
