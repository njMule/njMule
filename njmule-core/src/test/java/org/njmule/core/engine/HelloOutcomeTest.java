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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.FreePorts;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.TcpFraming;

/** The answer to a hello of ours when the client ends the identification its own way. */
@Timeout(60)
class HelloOutcomeTest {

    private static final Hello PEER =
            Hello.of(Hash16.parseHex("22".repeat(16)), 0, 4999, "peer", 0, 0, IdentityProof.MISC1, 0, 0, 0);

    @TempDir
    Path dir;

    private EngineLoop loop;
    private Network network;
    private ServerSocket peer;
    private final List<PeerInfo> answers = new ArrayList<>();

    @BeforeEach
    void open() throws Exception {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(dirs.config());
        int port = FreePorts.tcp();
        Preferences preferences = Preferences.defaults().toBuilder()
                .tcpPort(port)
                .kadEnabled(false)
                .build();
        this.loop = EngineLoop.start();
        this.network = this.loop
                .submit(() -> new Network(
                        this.loop,
                        dirs.config(),
                        () -> preferences,
                        event -> {},
                        (level, text) -> {},
                        Hash16.parseHex("0a".repeat(16)),
                        dirs.data().resolve("temp"),
                        () -> dirs.data().resolve("incoming"),
                        Optional.empty(),
                        false))
                .get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        this.loop
                .submit(() -> {
                    this.network.close();
                    return null;
                })
                .get(10, TimeUnit.SECONDS);
        this.loop.close();
        if (this.peer != null) {
            this.peer.close();
        }
    }

    @Test
    void shouldReportAClientThatLeavesRightAfterItsAnswerAtOnce() throws Exception {
        givenAClientListeningWithABacklogOf(5);

        whenTheClientAnswersOurHelloAndLeaves(3);

        thenEveryAnswerComesFrom(PEER.userHash());
        thenEveryIdentificationIs(PeerInfo.Identification.PENDING);
    }

    @Test
    void shouldReportAClientThatCannotSignAsFailed() throws Exception {
        givenAClientListeningWithABacklogOf(1);

        whenTheClientAnswersOurHelloWithAFakeProof();

        thenEveryIdentificationIs(PeerInfo.Identification.FAILED);
    }

    private void givenAClientListeningWithABacklogOf(int backlog) throws IOException {
        this.peer = new ServerSocket(0, backlog, InetAddress.getLoopbackAddress());
    }

    // The connection may close before the answer is handled: the outcome must not depend on it.
    private void whenTheClientAnswersOurHelloAndLeaves(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            CompletableFuture<PeerInfo> answer = this.network.hello(local(this.peer.getLocalPort()));
            try (Socket socket = this.peer.accept()) {
                read(socket);
                socket.getOutputStream().write(TcpFraming.encode(PEER.encode(true)));
            }
            this.answers.add(answer.get(10, TimeUnit.SECONDS));
        }
    }

    private void whenTheClientAnswersOurHelloWithAFakeProof() throws Exception {
        CompletableFuture<PeerInfo> answer = this.network.hello(local(this.peer.getLocalPort()));
        try (Socket socket = this.peer.accept()) {
            read(socket);
            socket.getOutputStream().write(TcpFraming.encode(PEER.encode(true)));
            IdentityProof.fake(socket.getOutputStream());
            this.answers.add(answer.get(10, TimeUnit.SECONDS));
        }
    }

    private void thenEveryAnswerComesFrom(Hash16 userHash) {
        assertThat(this.answers)
                .isNotEmpty()
                .allSatisfy(info -> assertThat(info.userHash()).isEqualTo(userHash));
    }

    private void thenEveryIdentificationIs(PeerInfo.Identification identification) {
        assertThat(this.answers)
                .isNotEmpty()
                .allSatisfy(info -> assertThat(info.identification()).isEqualTo(identification));
    }

    private static InetSocketAddress local(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static void read(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        TcpFraming.read(new BufferedInputStream(socket.getInputStream()), FrameLimit.fixed(1 << 20));
    }
}
