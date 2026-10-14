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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.njmule.core.api.SearchResult;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;

/**
 * Asks a client for the list of the files it shares, over a connection of our own: a hello, then the request, then the
 * list or the refusal. Any thread may browse.
 */
public final class SharedFilesBrowser {

    /** A client answers within this time or not at all. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    // Entries a list may hold; more than any client shares.
    private static final int MAX_ENTRIES = 100_000;

    private final EngineLoop loop;
    private final Supplier<Hello> ourHello;
    private final SecureRandom random;

    public SharedFilesBrowser(EngineLoop loop, Supplier<Hello> ourHello, SecureRandom random) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** The files the client listening at {@code to} shares; fails when it refuses, cannot be reached or is silent. */
    public CompletableFuture<List<SearchResult>> browse(InetSocketAddress to) {
        CompletableFuture<List<SearchResult>> files = new CompletableFuture<>();
        Thread.ofVirtual().name("njmule-browse").start(() -> {
            try {
                TcpConnection connection = TcpConnector.connectToClient(to, Optional.empty(), TIMEOUT, this.random);
                if (!this.loop.postOutcome(() -> this.connected(connection, files))) {
                    connection.close();
                    files.completeExceptionally(new IOException("njMule is stopping"));
                }
            } catch (IOException e) {
                files.completeExceptionally(e);
            }
        });
        return files;
    }

    private void connected(TcpConnection connection, CompletableFuture<List<SearchResult>> files) {
        try {
            connection.setReadTimeout(TIMEOUT);
        } catch (SocketException e) {
            connection.close();
            files.completeExceptionally(e);
            return;
        }
        this.loop.schedule("browse timeout", TIMEOUT, () -> {
            if (files.completeExceptionally(new IOException("the client did not answer"))) {
                connection.close();
            }
        });
        connection.start(new Handler(files), SharedFilesBrowser::unpack);
        connection.send(this.ourHello.get().encode(false));
    }

    /** The files of a shared files answer: {@code <u32 count>} then the entries. */
    static List<SearchResult> files(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        int count = in.count(in.u32(), FileEntry.MIN_SIZE, MAX_ENTRIES);
        List<SearchResult> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            FileEntry entry = FileEntry.read(in);
            if (entry.name().isPresent()
                    && entry.size().isPresent()
                    && SearchResult.isDownloadable(
                            entry.name().get(), entry.size().getAsLong())) {
                files.add(new SearchResult(
                        entry.hash(), entry.name().get(), entry.size().getAsLong(), 1, 0, entry.type()));
            }
        }
        return files;
    }

    private static Packet unpack(Packet frame) throws DecodeException {
        return Packing.unpack(frame, FrameLimits.SHARED_FILES_MAX);
    }

    // The hello answer, then the list or the refusal; the connection closes once one comes.
    private final class Handler implements TcpConnection.Handler {
        private final CompletableFuture<List<SearchResult>> files;

        Handler(CompletableFuture<List<SearchResult>> files) {
            this.files = files;
        }

        @Override
        public void onPacket(TcpConnection connection, Packet packet) {
            // Extended packets reuse the opcode of the refusal (a file comment); a packed list comes unpacked as one.
            boolean basic = packet.protocol() == Protocol.EDONKEY;
            if (basic && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                connection.setFrameLimit(FrameLimits.CLIENT);
                connection.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILES.code()));
                return;
            }
            if ((basic || packet.protocol() == Protocol.EMULE)
                    && packet.opcode() == Opcodes.Client.ASKSHAREDFILESANSWER.code()) {
                try {
                    this.files.complete(files(packet));
                } catch (DecodeException e) {
                    this.files.completeExceptionally(e);
                }
                connection.close();
            } else if (basic && packet.opcode() == Opcodes.Client.ASKSHAREDDENIEDANS.code()) {
                this.files.completeExceptionally(new IOException("the client does not show its files"));
                connection.close();
            }
        }

        @Override
        public void onClosed(TcpConnection connection, Optional<IOException> cause) {
            this.files.completeExceptionally(cause.orElseGet(() -> new IOException("the connection closed")));
        }
    }
}
