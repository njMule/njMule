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
import org.njmule.core.api.BanInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.SharedFilesVisibility;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
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

        // The ban shows with its time left, and can be lifted.
        List<BanInfo> bans = engine.bans().get(5, TimeUnit.SECONDS);
        assertThat(bans)
                .singleElement()
                .satisfies(b -> assertThat(b.secondsLeft()).isPositive());
        assertThat(engine.unban(bans.getFirst().address()).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(engine.bans().get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(engine.unban("192.0.2.1").get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void uploadsGoOnAfterSettingALimitWhereThereWasNone() throws Exception {
        NjMuleEngine engine = this.engine("");
        SharedFileInfo file = awaitShared(engine);
        Client client = this.client(engine.preferences().tcpPort(), 15);
        assertThat(client.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        Thread.sleep(300);

        engine.updatePreferences(p -> p.toBuilder().maxUploadKiBps(10).build()).get(10, TimeUnit.SECONDS);
        Thread.sleep(300);
        client.send(new ClientMessages.RequestParts(
                        file.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));

        assertThat(client.poll(Duration.ofSeconds(5)))
                .hasValueSatisfying(p -> assertThat(p.opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code()));
    }

    @Test
    void aFileRenamedDuringItsUploadKeepsBeingSent() throws Exception {
        NjMuleEngine engine = this.engine("");
        SharedFileInfo file = awaitShared(engine);
        Client client = this.client(engine.preferences().tcpPort(), 20);
        assertThat(client.ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        client.send(new ClientMessages.RequestParts(
                        file.hash(),
                        List.of(
                                new ClientMessages.Range(0, 1000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));
        assertThat(client.next().opcode()).isEqualTo(Opcodes.Client.SENDINGPART.code());

        assertThat(engine.renameSharedFile(file.hash(), "renamed.bin").get(10, TimeUnit.SECONDS))
                .isTrue();
        client.send(new ClientMessages.RequestParts(
                        file.hash(),
                        List.of(
                                new ClientMessages.Range(1000, 2000),
                                new ClientMessages.Range(0, 0),
                                new ClientMessages.Range(0, 0)))
                .encode(false));

        ClientMessages.SendingPart part = ClientMessages.SendingPart.decode(client.next());
        byte[] expected = Files.readAllBytes(this.dir.resolve("njmule/incoming/renamed.bin"));
        assertThat(part.data()).isEqualTo(Arrays.copyOfRange(expected, 1000, 2000));
    }

    @Test
    void theFriendWithTheFriendSlotGoesBeforeTheQueue() throws Exception {
        NjMuleEngine engine = this.engine("limits.uploadKiBps=1\n");
        SharedFileInfo file = awaitShared(engine);
        int port = engine.preferences().tcpPort();
        Hash16 pal = hello(4, 5004).userHash();
        engine.addFriend(new Friend(pal, "pal", Optional.empty())).get(5, TimeUnit.SECONDS);
        assertThat(engine.setFriendSlot(Optional.of(hello(9, 5009).userHash())).get(5, TimeUnit.SECONDS))
                .as("not a friend")
                .isFalse();
        assertThat(engine.setFriendSlot(Optional.of(pal)).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(engine.friendSlot().get(5, TimeUnit.SECONDS)).contains(pal);

        List<Client> clients = new ArrayList<>();
        for (int seed = 1; seed <= 4; seed++) {
            Client c = this.client(port, seed);
            c.send(ClientMessages.forFile(Opcodes.Client.SETREQFILEID, file.hash()));
            assertThat(c.next().opcode()).isEqualTo(Opcodes.Client.FILESTATUS.code());
            clients.add(c);
        }
        assertThat(clients.get(0).ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(clients.get(1).ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(ClientMessages.decodeQueueRank(clients.get(2).ask(file.hash())))
                .isEqualTo(1);

        assertThat(ClientMessages.decodeQueueRank(clients.get(3).ask(file.hash())))
                .as("the friend comes first")
                .isEqualTo(1);
    }

    @Test
    void theFriendSlotWaitsForTheFriendToProveItsKey() throws Exception {
        NjMuleEngine engine = this.engine("limits.uploadKiBps=1\n");
        SharedFileInfo file = awaitShared(engine);
        int port = engine.preferences().tcpPort();
        Hash16 pal = hello(16, 5016).userHash();
        engine.addFriend(new Friend(pal, "pal", Optional.empty())).get(5, TimeUnit.SECONDS);
        assertThat(engine.setFriendSlot(Optional.of(pal)).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(this.client(port, 1).ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(this.client(port, 2).ask(file.hash()).opcode()).isEqualTo(Opcodes.Client.ACCEPTUPLOADREQ.code());
        assertThat(ClientMessages.decodeQueueRank(this.client(port, 3).ask(file.hash())))
                .isEqualTo(1);

        // Anyone can send the friend's user hash: it counts once the key behind it is proven.
        Client claimed = this.identifying(port, 16);
        assertThat(ClientMessages.decodeQueueRank(claimed.ask(file.hash())))
                .as("not proven yet")
                .isEqualTo(2);
        Client proven = this.identifying(port, 16);
        proven.prove();
        assertThat(ClientMessages.decodeQueueRank(proven.ask(file.hash())))
                .as("proven")
                .isEqualTo(1);
    }

    @Test
    void filesSharedWithFriendsOnlyNeedTheFriendProven() throws Exception {
        NjMuleEngine engine = this.engine("share.visibleTo=" + SharedFilesVisibility.FRIENDS + "\n");
        awaitShared(engine);
        int port = engine.preferences().tcpPort();
        Hash16 pal = hello(17, 5017).userHash();
        engine.addFriend(new Friend(pal, "pal", Optional.empty())).get(5, TimeUnit.SECONDS);
        Packet ask = Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILES.code());

        // A client claiming the friend's hash waits for the proof, and failing it sees nothing.
        Client impostor = this.identifying(port, 17);
        impostor.send(ask);
        assertThat(impostor.poll(Duration.ofSeconds(1))).isEmpty();
        IdentityProof.fake(impostor.out);
        assertThat(impostor.next().payload()).containsExactly(0, 0, 0, 0);

        // The friend not taking part in the identification, or proving its key, sees the file.
        Client unsecured = this.client(port, 17);
        unsecured.send(ask);
        assertThat(unsecured.next().payload()).startsWith(1, 0, 0, 0);
        Client proven = this.identifying(port, 17);
        proven.send(ask);
        proven.prove();
        Packet files = proven.next();
        assertThat(files.opcode()).isEqualTo(Opcodes.Client.ASKSHAREDFILESANSWER.code());
        assertThat(files.payload()).startsWith(1, 0, 0, 0);
    }

    @Test
    void clientsBrowsingByDirectoryGetOurFilesInOnePseudoDirectoryOnceAConnection() throws Exception {
        NjMuleEngine engine = this.engine("share.visibleTo=" + SharedFilesVisibility.EVERYBODY + "\n");
        SharedFileInfo file = awaitShared(engine);
        Client client = this.client(engine.preferences().tcpPort(), 18);

        client.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDDIRS.code()));
        Packet dirs = client.next();
        assertThat(dirs.opcode()).isEqualTo(Opcodes.Client.ASKSHAREDDIRSANS.code());
        PacketReader in = dirs.reader();
        assertThat(in.u32()).isEqualTo(1);
        assertThat(in.string16()).isEqualTo("!Other");

        client.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.ASKSHAREDFILESDIR.code(),
                new PacketWriter().string16("!Other", StringEncoding.UTF8)));
        Packet files = client.next();
        assertThat(files.opcode()).isEqualTo(Opcodes.Client.ASKSHAREDFILESDIRANS.code());
        PacketReader list = files.reader();
        assertThat(list.string16()).isEqualTo("!Other");
        assertThat(list.u32()).isEqualTo(1);
        assertThat(FileEntry.read(list).hash()).isEqualTo(file.hash());

        // A list can take a megabyte: one a connection.
        client.send(Packet.empty(Protocol.EDONKEY, Opcodes.Client.ASKSHAREDFILES.code()));
        assertThat(client.poll(Duration.ofSeconds(1))).isEmpty();
    }

    @Test
    void theAichHashOfADownloadIsToldToClientsThatAskForIt() throws Exception {
        NjMuleEngine engine = this.engine("");
        Hash16 hash = Hash16.parseHex("5A".repeat(16));
        Hash20 aich = Hash20.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
        engine.addDownload(new Ed2kLink.FileLink(
                        "partial.bin", 1_000_000, hash, Optional.of(aich), List.of(), List.of(), List.of()))
                .get(10, TimeUnit.SECONDS);
        Client client = this.client(engine.preferences().tcpPort(), 19);

        client.send(
                Packet.of(Protocol.EMULE, Opcodes.Extended.AICHFILEHASHREQ.code(), new PacketWriter().hash16(hash)));
        Optional<Packet> answer = client.poll(Duration.ofSeconds(3));
        assertThat(answer).isPresent();
        assertThat(answer.get().opcode()).isEqualTo(Opcodes.Extended.AICHFILEHASHANS.code());
        PacketReader in = answer.get().reader();
        assertThat(in.hash16()).isEqualTo(hash);
        assertThat(in.hash20()).isEqualTo(aich);
    }

    @Test
    void aSharedFileKeepsTheUploadPriorityItIsGiven() throws Exception {
        NjMuleEngine engine = this.engine("");
        SharedFileInfo file = awaitShared(engine);
        assertThat(file.priority()).isEqualTo(UploadPriority.AUTO);

        assertThat(engine.setUploadPriority(file.hash(), UploadPriority.HIGH).get(5, TimeUnit.SECONDS))
                .isTrue();
        assertThat(engine.sharedFiles().get(5, TimeUnit.SECONDS))
                .singleElement()
                .extracting(SharedFileInfo::priority)
                .isEqualTo(UploadPriority.HIGH);
        assertThat(engine.setUploadPriority(Hash16.parseHex("00".repeat(16)), UploadPriority.LOW)
                        .get(5, TimeUnit.SECONDS))
                .isFalse();
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
        client.send(Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.ASKSHAREDFILESDIR.code(),
                new PacketWriter().string16("!Other", StringEncoding.UTF8)));
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
        return this.client(port, seed, listenPort, 0);
    }

    // A client taking part in the secure identification, which it leaves to the test.
    private Client identifying(int port, int seed) throws IOException {
        return this.client(port, seed, 5000 + seed, IdentityProof.MISC1);
    }

    private Client client(int port, int seed, int listenPort, int miscOptions1) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        Client client = new Client(socket);
        this.resources.add(socket);
        client.send(hello(seed, listenPort, miscOptions1).encode(false));
        Packet answer = client.next();
        assertThat(answer.opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
        client.engine = Hello.decode(answer);
        return client;
    }

    private static Hello hello(int seed, int listenPort) {
        return hello(seed, listenPort, 0);
    }

    private static Hello hello(int seed, int listenPort, int miscOptions1) {
        byte[] hash = new byte[16];
        new Random(seed).nextBytes(hash);
        return Hello.of(Hash16.of(hash), 0, listenPort, "client " + seed, 0, 0, miscOptions1, 0, 0, 0);
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
        private Hello engine;

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

        // The engine's side of the identification is left unanswered unless the test proves a key.
        Packet next() throws IOException {
            while (true) {
                Packet packet = TcpFraming.read(this.in, FrameLimit.fixed(1 << 20));
                if (!IdentityProof.isIdentification(packet)) {
                    return packet;
                }
            }
        }

        void prove() throws IOException {
            IdentityProof.prove(this.engine, this.in, this.out);
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
