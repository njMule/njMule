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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;

@Timeout(60)
class UploadQueueTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void givesTheSlotsTheLimitAllowsQueuesTheRestAndBansClientsThatAskTooOften() throws Exception {
        NjMuleEngine engine = this.engine("limits.uploadKiBps=1\n");
        SharedFileInfo file = awaitShared(engine);
        int port = engine.preferences().tcpPort();

        Client first = this.client(port, 1);
        Client second = this.client(port, 2);
        Client third = this.client(port, 3);
        for (Client c : List.of(first, second, third)) {
            c.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file.hash()));
            assertThat(c.next().opcode()).isEqualTo(Opcodes.Client.FILESTATUS.code());
        }

        assertThat(first.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(second.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        Packet rank = third.ask(file.hash());
        assertThat(rank.protocol()).isEqualTo(Protocol.EMULE);
        assertThat(rank.opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
        assertThat(ClientMessages.decodeQueueRank(rank)).isEqualTo(1);
        // Front ends see the slots, then the queue.
        assertThat(engine.uploads().get(5, TimeUnit.SECONDS))
                .extracting(UploadInfo::state)
                .containsExactly(UploadInfo.State.UPLOADING, UploadInfo.State.UPLOADING, UploadInfo.State.QUEUED);

        // Asking again at once: three times are tolerated, the fourth bans the client and drops it.
        for (int i = 0; i < 3; i++) {
            assertThat(third.ask(file.hash()).opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
        }
        third.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file.hash()));
        assertThatThrownBy(third::next).isInstanceOf(IOException.class);

        // A slot asks for a block and gets it, within the limit.
        first.send(new ClientMessages.RequestParts(
                        file.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));
        Packet data = first.next();
        assertThat(data.opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code());
        assertThat(ClientMessages.SendingPart.decode(data).end()).isEqualTo(1000);

        // The banned client left the queue.
        List<UploadInfo> uploads = engine.uploads().get(5, TimeUnit.SECONDS);
        assertThat(uploads)
                .extracting(UploadInfo::state)
                .containsExactly(UploadInfo.State.UPLOADING, UploadInfo.State.UPLOADING);
        assertThat(uploads).allMatch(u -> u.file().equals(file.name()));
        assertThat(uploads.stream().mapToLong(UploadInfo::transferred).sum()).isEqualTo(1000);
        EngineStatus status = engine.status().get(5, TimeUnit.SECONDS);
        assertThat(status.uploads()).isEqualTo(2);
        assertThat(status.queued()).isZero();
        assertThat(status.uploaded()).isEqualTo(1000);
        assertThat(status.sharedFiles()).isEqualTo(1);
        assertThat(status.server()).isEmpty();
    }

    @Test
    void answersTheOtherRequestsOfADownloader() throws Exception {
        NjMuleEngine engine = this.engine("");
        SharedFileInfo file = awaitShared(engine);
        Client client = this.client(engine.preferences().tcpPort(), 4);

        client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, file.hash()));
        assertThat(ClientMessages.FileName.decode(client.next()).name()).isEqualTo("queued.bin");

        Hash16 unknown = Hash16.parseHex("77".repeat(16));
        client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, unknown));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.FILEREQANSNOFIL.code());

        client.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILES.code()));
        Packet files = client.next();
        assertThat(files.opcode()).isEqualTo(Opcodes.Client.ASKSHAREDFILESANSWER.code());
        assertThat(files.payload()).containsExactly(0, 0, 0, 0);
        client.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDDIRS.code()));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.ASKSHAREDDENIEDANS.code());

        client.send(Packet.empty(Protocol.EMULE, Opcodes.Extended.PUBLICIP_REQ.code()));
        Packet ip = client.next();
        assertThat(ip.opcode()).isEqualTo(Opcodes.Extended.PUBLICIP_ANSWER.code());
        assertThat(ip.payload()).containsExactly(127, 0, 0, 1);

        client.send(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.AICHREQUEST.code(),
                new PacketWriter().hash16(file.hash()).u16(0).bytes(new byte[20])));
        Packet aich = client.next();
        assertThat(aich.opcode()).isEqualTo(Opcodes.Extended.AICHANSWER.code());
        assertThat(aich.length()).isEqualTo(16);

        // Nobody else has the file, so a request for sources gets no answer; a slot still comes.
        client.send(SourceExchange.request(file.hash()));
        assertThat(client.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        client.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.CANCELTRANSFER.code()));
    }

    @Test
    void callsAWaitingClientBackWhenASlotFrees() throws Exception {
        NjMuleEngine engine = this.engine("limits.uploadKiBps=1\n");
        SharedFileInfo file = awaitShared(engine);
        int port = engine.preferences().tcpPort();
        Client first = this.client(port, 5);
        Client second = this.client(port, 6);
        assertThat(first.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(second.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        try (ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Client waiting = this.client(port, 7, listening.getLocalPort());
            assertThat(waiting.ask(file.hash()).opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());
            waiting.socket.close();

            first.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.CANCELTRANSFER.code()));
            listening.setSoTimeout((int) WAIT.toMillis());
            try (Socket back = listening.accept()) {
                back.setSoTimeout((int) WAIT.toMillis());
                Client called = new Client(back);
                assertThat(called.next().opcode()).isEqualTo(Opcodes.Client.HELLO.code());
                called.send(hello(7, listening.getLocalPort()).encode(true));
                assertThat(called.next().opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
            }
        }
    }

    @Test
    void anotherAddressClaimingAUserHashGetsNeitherItsSlotNorItsPlace() throws Exception {
        NjMuleEngine engine = this.engine("limits.uploadKiBps=1\n");
        SharedFileInfo file = awaitShared(engine);
        int port = engine.preferences().tcpPort();

        Client victim = this.client(port, 7);
        victim.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file.hash()));
        assertThat(victim.next().opcode()).isEqualTo(Opcodes.Client.FILESTATUS.code());
        assertThat(victim.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());

        Client impostor = this.clientFrom(InetAddress.getByName("127.0.0.2"), port, 7);
        impostor.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file.hash()));
        assertThat(impostor.poll(Duration.ofSeconds(1))).isEmpty();

        // The slot still serves the client that holds it.
        victim.send(new ClientMessages.RequestParts(
                        file.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));
        assertThat(victim.next().opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code());
        assertThat(engine.uploads().get(5, TimeUnit.SECONDS)).hasSize(1);
    }

    @Test
    void aClientChangingItsUserHashIsDroppedAndBanned() throws Exception {
        NjMuleEngine engine = this.engine("");
        int port = engine.preferences().tcpPort();

        Client client = this.client(port, 8);
        client.send(hello(9, 5009).encode(false));
        assertThatThrownBy(client::next).isInstanceOf(IOException.class);

        // The banned address cannot even connect again.
        Socket again = new Socket(InetAddress.getLoopbackAddress(), port);
        this.resources.add(again);
        again.setSoTimeout((int) WAIT.toMillis());
        assertThat(again.getInputStream().read()).isEqualTo(-1);
        assertThat(engine.uploads().get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void aSlotLeftIdleGoesToTheNextClientInTheQueue() throws Exception {
        NjMuleEngine engine = this.engine("limits.uploadKiBps=1\n");
        SharedFileInfo file = awaitShared(engine);
        int port = engine.preferences().tcpPort();
        Client busy = this.client(port, 11);
        Client idle = this.client(port, 12);
        assertThat(busy.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(idle.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        Client waiting = this.client(port, 13);
        assertThat(waiting.ask(file.hash()).opcode()).isEqualTo(Opcodes.Extended.QUEUERANKING.code());

        busy.send(new ClientMessages.RequestParts(
                        file.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));
        assertThat(busy.next().opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code());

        // The client that asks for nothing loses its slot to the one waiting.
        Duration grace = Duration.ofSeconds(25);
        assertThat(idle.poll(grace))
                .hasValueSatisfying(p -> assertThat(p.opcode()).isEqualTo(Opcodes.Client.OUTOFPARTREQS.code()));
        assertThat(waiting.poll(grace))
                .hasValueSatisfying(p -> assertThat(p.opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code()));
    }

    @Test
    void aSlotSendsTheFileItsClientSwitchedTo() throws Exception {
        NjMuleEngine engine = this.engine("", 2);
        List<SharedFileInfo> files = awaitShared(engine, 2);
        int port = engine.preferences().tcpPort();
        Client client = this.client(port, 14);
        assertThat(client.ask(files.get(0).hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());

        Hash16 other = files.get(1).hash();
        client.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, other));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.FILESTATUS.code());
        client.send(new ClientMessages.RequestParts(
                        other,
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));

        ClientMessages.SendingPart part = ClientMessages.SendingPart.decode(client.next());
        assertThat(part.file()).isEqualTo(other);
        byte[] expected = Files.readAllBytes(
                this.dir.resolve("njmule/incoming").resolve(files.get(1).name()));
        assertThat(part.data()).isEqualTo(Arrays.copyOf(expected, 1000));
    }

    private Client clientFrom(InetAddress local, int port, int seed) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port, local, 0);
        socket.setSoTimeout((int) WAIT.toMillis());
        Client client = new Client(socket);
        this.resources.add(socket);
        client.send(hello(seed, 5000 + seed).encode(false));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        return client;
    }

    private Client client(int port, int seed) throws IOException {
        return this.client(port, seed, 5000 + seed);
    }

    private Client client(int port, int seed, int listenPort) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        Client client = new Client(socket);
        this.resources.add(socket);
        client.send(hello(seed, listenPort).encode(false));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        return client;
    }

    private static Hello hello(int seed, int listenPort) {
        byte[] hash = new byte[16];
        new Random(seed).nextBytes(hash);
        return Hello.of(Hash16.of(hash), 0, listenPort, "client " + seed, 0, 0, 0, 0, 0, 0);
    }

    private static SharedFileInfo awaitShared(NjMuleEngine engine) throws Exception {
        return awaitShared(engine, 1).getFirst();
    }

    private static List<SharedFileInfo> awaitShared(NjMuleEngine engine, int count) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<SharedFileInfo> files = engine.sharedFiles().get(10, TimeUnit.SECONDS);
            if (files.size() >= count) {
                return files;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("not shared");
    }

    private NjMuleEngine engine(String extra) throws IOException {
        return this.engine(extra, 1);
    }

    private NjMuleEngine engine(String extra, int files) throws IOException {
        Path root = this.dir.resolve("njmule");
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        Path incoming = Files.createDirectories(root.resolve("incoming"));
        for (int i = 0; i < files; i++) {
            byte[] data = new byte[300_000];
            new Random(5 + i).nextBytes(data);
            Files.write(incoming.resolve(i == 0 ? "queued.bin" : "other" + i + ".bin"), data);
        }
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(dirs.config().resolve("njmule.properties"), "net.tcpPort=" + port + "\n" + extra);
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    /** A downloader speaking the protocol by hand. */
    private static final class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void send(Packet packet) throws IOException {
            this.out.write(TcpFraming.encode(packet));
            this.out.flush();
        }

        Packet ask(Hash16 file) throws IOException {
            this.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, file));
            return this.next();
        }

        Packet next() throws IOException {
            return TcpFraming.read(this.in, FrameLimit.fixed(1 << 20));
        }

        Optional<Packet> poll(Duration wait) throws IOException {
            int old = this.socket.getSoTimeout();
            this.socket.setSoTimeout((int) wait.toMillis());
            try {
                return Optional.of(this.next());
            } catch (SocketTimeoutException e) {
                return Optional.empty();
            } finally {
                this.socket.setSoTimeout(old);
            }
        }
    }
}
