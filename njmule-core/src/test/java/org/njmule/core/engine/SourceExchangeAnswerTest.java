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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.njmule.core.FreePorts;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.download.DownloadManager;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.util.Ipv4;

/** The sources we pass on to a client that asks for them. */
@Timeout(60)
class SourceExchangeAnswerTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    private final List<AutoCloseable> resources = new ArrayList<>();

    private Optional<InetAddress> lan;
    private NjMuleEngine engine;
    private SharedFileInfo file;
    private Optional<SourceExchange.Answer> answer;
    private int offered;
    private InetAddress askingFrom;
    private boolean passedOn;

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(this.resources);
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void shouldPassASourceOnThisMachineOnlyToClientsOnThisMachine() throws Exception {
        givenALanAddressToAskFrom();
        givenAnEngineSharingAFile();
        givenADownloaderOnThisMachineQueuedForTheFile();
        givenAClientAtTheLanAddressGetsNoSources();

        whenAClientOnThisMachineAsksForSources();

        thenTheOnlySourceIs("127.0.0.1");
    }

    @ParameterizedTest
    @CsvSource({
        "203.0.113.9, 198.51.100.7, true",
        "203.0.113.9, 192.168.1.5,  true",
        "10.1.2.3,    198.51.100.7, false",
        "10.1.2.3,    192.168.1.5,  true",
        "10.1.2.3,    127.0.0.1,    true",
        "127.0.0.1,   198.51.100.7, false",
        "127.0.0.1,   192.168.1.5,  false",
        "127.0.0.1,   127.0.0.1,    true",
        "224.0.0.1,   127.0.0.1,    false",
        "0.1.2.3,     127.0.0.1,    false"
    })
    void shouldKeepAddressesOutsideThePublicNetworkWithinTheirOwn(String source, String client, boolean passedOn) {
        givenASourceAt(source);
        givenAClientAt(client);

        whenTheSourceIsOfferedToTheClient();

        thenWhetherTheSourceIsPassedOnIs(passedOn);
    }

    private void givenALanAddressToAskFrom() throws IOException {
        this.lan = lanAddress();
        assumeTrue(this.lan.isPresent(), "a LAN address to ask from");
    }

    private void givenAnEngineSharingAFile() throws Exception {
        this.engine = engine();
        this.file = awaitShared(this.engine);
    }

    private void givenADownloaderOnThisMachineQueuedForTheFile() throws IOException {
        Client downloader = client(InetAddress.getLoopbackAddress(), this.engine, 1);
        downloader.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, this.file.hash()));
        downloader.send(ClientMessages.forFile(Opcodes.Client.STARTUPLOADREQ, this.file.hash()));
        downloader.poll(Duration.ofSeconds(1));
    }

    private void givenAClientAtTheLanAddressGetsNoSources() throws Exception {
        Client fromLan = client(this.lan.orElseThrow(), this.engine, 2);
        askForSources(fromLan);
        assertThat(fromLan.answer(Duration.ofSeconds(2)))
                .as("a loopback address means nothing to another machine")
                .isEmpty();
    }

    private void givenASourceAt(String address) {
        this.offered = Ipv4.parse(address);
    }

    private void givenAClientAt(String address) {
        this.askingFrom = InetAddress.ofLiteral(address);
    }

    private void whenAClientOnThisMachineAsksForSources() throws Exception {
        Client local = client(InetAddress.getLoopbackAddress(), this.engine, 3);
        askForSources(local);
        this.answer = local.answer(WAIT);
    }

    private void whenTheSourceIsOfferedToTheClient() {
        this.passedOn = DownloadManager.passesOn(this.offered, this.askingFrom);
    }

    private void thenTheOnlySourceIs(String address) {
        assertThat(this.answer.orElseThrow().sources())
                .singleElement()
                .satisfies(s -> assertThat(Ipv4.format(s.ip())).isEqualTo(address));
    }

    private void thenWhetherTheSourceIsPassedOnIs(boolean expected) {
        assertThat(this.passedOn).isEqualTo(expected);
    }

    // Clients ask for the file before they ask for its sources.
    private void askForSources(Client client) throws IOException {
        client.send(ClientMessages.forFile(Opcodes.Client.REQUESTFILENAME, this.file.hash()));
        client.send(SourceExchange.request(this.file.hash()));
    }

    private static Optional<InetAddress> lanAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                    return Optional.of(address);
                }
            }
        }
        return Optional.empty();
    }

    private Client client(InetAddress to, NjMuleEngine engine, int seed) throws IOException {
        Socket socket = new Socket(to, engine.preferences().tcpPort());
        socket.setSoTimeout((int) WAIT.toMillis());
        this.resources.add(socket);
        Client client = new Client(socket);
        byte[] hash = new byte[16];
        new Random(seed).nextBytes(hash);
        client.send(
                Hello.of(Hash16.of(hash), 0, 5000 + seed, "client " + seed, 0, 0, 0, Hello.MISC2_SOURCE_EXCHANGE2, 0, 0)
                        .encode(false));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        return client;
    }

    private static SharedFileInfo awaitShared(NjMuleEngine engine) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            List<SharedFileInfo> files = engine.sharedFiles().get(10, TimeUnit.SECONDS);
            if (!files.isEmpty()) {
                return files.getFirst();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("not shared");
    }

    private NjMuleEngine engine() throws IOException {
        Path root = this.dir.resolve("njmule");
        AppDirectories dirs = AppDirectories.underRoot(root, true);
        Files.createDirectories(dirs.config());
        byte[] data = new byte[300_000];
        new Random(5).nextBytes(data);
        Files.write(Files.createDirectories(root.resolve("incoming")).resolve("asked.bin"), data);
        int port = FreePorts.tcp();
        // Slow, so the first client stays in the queue; clients at LAN addresses are let in.
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + port + "\nlimits.uploadKiBps=1\nsecurity.filterLan=false\n");
        NjMuleEngine engine = NjMuleEngine.start(new EngineConfig(dirs));
        this.resources.add(engine);
        return engine;
    }

    /** A client speaking the protocol by hand. */
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

        Packet next() throws IOException {
            return TcpFraming.read(this.in, FrameLimit.fixed(1 << 20));
        }

        Optional<Packet> poll(Duration wait) throws IOException {
            int old = this.socket.getSoTimeout();
            this.socket.setSoTimeout((int) wait.toMillis());
            try {
                return Optional.of(next());
            } catch (SocketTimeoutException e) {
                return Optional.empty();
            } finally {
                this.socket.setSoTimeout(old);
            }
        }

        // The answer to a request for sources, if one comes within {@code wait}.
        Optional<SourceExchange.Answer> answer(Duration wait) throws Exception {
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                Optional<Packet> packet = poll(Duration.ofMillis(200));
                if (packet.isEmpty()) {
                    continue;
                }
                Packet answer = Packing.unpack(packet.get(), 1 << 20);
                if (answer.protocol() == Protocol.EMULE && answer.opcode() == Opcodes.Extended.ANSWERSOURCES2.code()) {
                    return Optional.of(SourceExchange.Answer.decode(answer));
                }
            }
            return Optional.empty();
        }
    }
}
