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
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.Category;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Friend;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.config.AppDirectories;
import org.njmule.core.config.Preferences;
import org.njmule.core.files.CancelledFiles;
import org.njmule.core.kad.KadNode;
import org.njmule.core.kad.UInt128;
import org.njmule.core.persist.format.IpFilterDat;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.search.SpamFilter;
import org.njmule.core.search.StoredSearches;
import org.njmule.core.server.ScriptedServer;
import org.njmule.core.server.ServerList;
import org.njmule.core.util.Ipv4;

/** The network side of the engine on its own, with the events and the log it reports recorded. */
@Timeout(60)
class NetworkTest {

    private static final Hash16 USER = Hash16.parseHex("0a".repeat(16));
    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("1.2.3.4"));

    /** A line of the front ends' log. */
    private record Logged(LogLine.Level level, String text) {}

    @TempDir
    Path dir;

    private final BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();
    private final List<Logged> log = new CopyOnWriteArrayList<>();
    private volatile Preferences preferences;
    private AppDirectories dirs;
    private EngineLoop loop;
    private Network network;

    @BeforeEach
    void open() throws IOException {
        this.dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), false);
        Files.createDirectories(this.dirs.config());
        this.preferences = Preferences.defaults().toBuilder()
                .tcpPort(freeTcpPort())
                .udpPort(freeUdpPort())
                .kadEnabled(false)
                .build();
        this.loop = EngineLoop.start();
    }

    @AfterEach
    void close() throws Exception {
        if (this.network != null) {
            this.onLoop(() -> {
                this.network.close();
                return null;
            });
        }
        this.loop.close();
    }

    @Test
    void spamMarksStayWhenTheyCannotBeSaved() throws Exception {
        Network network = this.create();
        Hash16 hash = Hash16.parseHex("b1".repeat(16));
        SearchResult result = new SearchResult(hash, "free movie installer.exe", 100, 1, 0, Optional.empty());

        SpamMarks marked = this.onLoop(() -> network.markSpam(List.of(result), true));
        assertThat(marked.spam()).containsExactly(hash);
        assertThat(this.dirs.config().resolve(SpamFilter.FILE_NAME)).exists();

        blockWrites(this.dirs.config().resolve(SpamFilter.FILE_NAME));
        SpamMarks unmarked = this.onLoop(() -> network.markSpam(List.of(result), false));

        assertThat(unmarked.spam()).isEmpty();
        assertThat(unmarked.notSpam()).containsExactly(hash);
        assertThat(this.onLoop(network::spamMarks)).isEqualTo(unmarked);
    }

    @Test
    void sharingNeedsTheNetworkStarted() throws Exception {
        Network network = this.create();
        Path folder = Files.createDirectories(this.dir.resolve("films"));

        assertThatThrownBy(() -> this.onLoop(() -> {
                    network.shareDirectory(folder, false);
                    return null;
                }))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> this.onLoop(() -> {
                    network.unshareDirectory(folder);
                    return null;
                }))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> this.onLoop(() -> network.addKnownFiles(List.of(), List.of())))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(this.onLoop(() -> network.renameSharedFile(USER, "other.iso")))
                .isFalse();
        assertThat(this.onLoop(() -> network.setUploadPriority(USER, UploadPriority.HIGH)))
                .isFalse();
        assertThat(this.onLoop(network::sharedFiles)).isEmpty();
        assertThat(this.onLoop(network::sharedDirectories)).isEmpty();
        assertThat(this.onLoop(network::status).sharedFiles()).isZero();
        assertThat(this.onLoop(network::history).downloaded(USER)).isFalse();
    }

    @Test
    void aStartedNetworkSharesAndUnsharesFolders() throws Exception {
        Network network = this.started();
        Path folder = Files.createDirectories(this.dir.resolve("films"));
        Path tree = Files.createDirectories(this.dir.resolve("music"));

        this.onLoop(() -> {
            network.shareDirectory(folder, false);
            network.shareDirectory(tree, true);
            return null;
        });
        assertThat(this.onLoop(network::sharedDirectories))
                .extracting(d -> d.path().getFileName().toString() + (d.subdirectories() ? "/**" : ""))
                .containsExactlyInAnyOrder("films", "music/**");

        this.onLoop(() -> {
            network.unshareDirectory(folder);
            return null;
        });
        assertThat(this.onLoop(network::sharedDirectories)).hasSize(1);
        assertThat(this.onLoop(network::describe)).startsWith("downloads:").contains("uploads: ");
    }

    @Test
    void anIpFilterIsReplacedByAListButNeverByNothing() throws Exception {
        Network network = this.create();
        int blocked = Ipv4.of(203, 0, 113, 7);

        assertThatThrownBy(() -> this.onLoop(() -> network.setIpFilter(List.of())))
                .hasCauseInstanceOf(IOException.class);
        int ranges = this.onLoop(() -> network.setIpFilter(
                List.of(new IpFilterDat.Range(blocked, blocked, 0, "test"), new IpFilterDat.Range(1, 2, 0, "low"))));

        assertThat(ranges).isEqualTo(2);
        assertThat(this.dirs.config().resolve(Network.IPFILTER_FILE)).exists();
        assertThat(this.log).contains(new Logged(LogLine.Level.INFO, "2 IP filter ranges"));
        assertThatThrownBy(() -> this.onLoop(() -> {
                    network.connect(Optional.of(new ServerList.Address(blocked, 4661)));
                    return null;
                }))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IP filter");
    }

    @Test
    void categoriesNeedTheirOwnIds() throws Exception {
        Network network = this.create();
        Category films = new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false);

        assertThatThrownBy(() -> this.onLoop(() -> {
                    network.setCategories(List.of(films, films));
                    return null;
                }))
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(this.onLoop(network::categories)).isEmpty();
    }

    @Test
    void unreadableFilesAreTakenAsEmpty() throws Exception {
        Files.write(this.dirs.config().resolve(StoredSearches.FILE_NAME), new byte[] {9, 9, 9});
        Files.write(this.dirs.config().resolve(CancelledFiles.FILE_NAME), new byte[] {9, 9, 9});

        Network network = this.create();

        assertThat(this.onLoop(network::savedSearches)).isEmpty();
        assertThat(this.onLoop(network::history).cancelled(USER)).isFalse();
    }

    @Test
    void serversChangeEvenWhenTheirFilesCannotBeSaved() throws Exception {
        this.preferences =
                this.preferences.toBuilder().manualServersHighPriority(true).build();
        Network network = this.create();
        // Documentation addresses: the network is not started, nothing is sent to them.
        ServerList.Address first = new ServerList.Address(Ipv4.of(198, 51, 100, 7), 4661);
        ServerList.Address unknown = new ServerList.Address(Ipv4.of(198, 51, 100, 8), 4661);
        blockWrites(this.dirs.config().resolve(ServerList.FILE_NAME));
        blockWrites(this.dirs.config().resolve(ServerList.STATIC_FILE_NAME));

        assertThat(this.onLoop(() -> network.addServer(first))).isTrue();
        assertThat(this.onLoop(() -> network.addServer(first))).as("known").isFalse();
        assertThat(this.onLoop(network::servers))
                .singleElement()
                .satisfies(s -> assertThat(s.priority()).isEqualTo(ServerInfo.Priority.HIGH));
        assertThat(this.onLoop(() -> network.setStaticServer(first, true))).isTrue();
        assertThat(this.onLoop(() -> network.setServerPriority(first, ServerInfo.Priority.LOW)))
                .isTrue();
        assertThat(this.onLoop(() -> network.setStaticServer(unknown, true))).isFalse();
        assertThat(this.onLoop(() -> network.setServerPriority(unknown, ServerInfo.Priority.LOW)))
                .isFalse();
        assertThat(this.next(EngineEvent.ServerUpdated.class).server().isStatic())
                .isTrue();

        assertThat(this.onLoop(() -> network.removeServer(first))).isTrue();
        assertThat(this.onLoop(network::servers)).isEmpty();
    }

    @Test
    void datagramsThatAreNoAnswerOrCannotBeReadAreDropped() throws Exception {
        Network network = this.create();
        InetSocketAddress from = new InetSocketAddress(InetAddress.getLoopbackAddress(), 4665);

        this.onLoop(() -> {
            network.datagram(from, Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSEARCHREQ.code(), new byte[4]));
            network.datagram(from, Packet.of(Protocol.EDONKEY, Opcodes.ServerUdp.GLOBSEARCHRES.code(), new byte[3]));
            return null;
        });

        assertThat(this.events).isEmpty();
    }

    @Test
    void settingsChangedBeforeTheStartOrAfterTheEndAreIgnored() throws Exception {
        Network network = this.create();
        Preferences was = this.preferences;
        Preferences now = was.toBuilder().tcpPort(freeTcpPort()).build();

        this.onLoop(() -> {
            network.reconfigure(was, now);
            return null;
        });
        this.onLoop(() -> {
            network.close();
            return null;
        });
        this.onLoop(() -> {
            network.reconfigure(was, now);
            return null;
        });

        assertThat(this.log).isEmpty();
    }

    @Test
    void aPortTakenByAnotherProgramLeavesUsUnreachable() throws Exception {
        try (ServerSocket taken = new ServerSocket(this.preferences.tcpPort())) {
            Network network = this.started();

            assertThat(this.log)
                    .anySatisfy(
                            l -> assertThat(l.text()).startsWith("Cannot listen on TCP port " + taken.getLocalPort()));
            assertThat(this.onLoop(network::status).tcpPort()).isEqualTo(taken.getLocalPort());
        }
    }

    @Test
    void kadIsCheckedBootstrappedAndListedOnlyWhileItRuns() throws Exception {
        Network network = this.started();

        assertThat(this.onLoop(network::recheckKadFirewall)).isFalse();
        assertThatThrownBy(() -> this.onLoop(() -> {
                    network.kadBootstrap(Ipv4.of(127, 0, 0, 1), 9);
                    return null;
                }))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(this.onLoop(network::kadContacts)).isEmpty();
        assertThat(this.onLoop(network::kadSearches)).isEmpty();

        this.onLoop(() -> {
            network.connectKad();
            network.connectKad();
            return null;
        });
        assertThat(this.onLoop(network::kadInfo)).isPresent();
        assertThat(this.onLoop(network::recheckKadFirewall)).isTrue();
        assertThat(this.log).contains(new Logged(LogLine.Level.INFO, "Kad: checking the firewall again"));
        this.onLoop(() -> {
            network.kadBootstrap(Ipv4.of(127, 0, 0, 1), freeUdpPort());
            return null;
        });
        // A LAN address the preferences filter: a running node never sends anything beyond this machine.
        NodesDat.Contact lan = new NodesDat.Contact(
                UInt128.parseHex("c3".repeat(16)), Ipv4.of(10, 0, 0, 7), 4672, 4662, 8, 0, NodesDat.UdpKey.NONE, true);
        assertThat(this.onLoop(() -> network.addKadContacts(new NodesDat.Contents(2, false, List.of(lan)))))
                .isZero();
        assertThat(this.onLoop(network::kadContacts)).isEmpty();
        assertThat(this.onLoop(network::kadSearches)).isEmpty();

        this.onLoop(() -> {
            network.disconnectKad();
            network.disconnectKad();
            return null;
        });
        assertThat(this.onLoop(network::kadInfo)).isEmpty();
        assertThat(this.log).filteredOn(l -> l.text().equals("Kad stopped")).hasSize(1);
    }

    @Test
    void kadContactsGoToNodesDatWhileKadIsOff() throws Exception {
        Network network = this.create();
        NodesDat.Contact first = new NodesDat.Contact(
                UInt128.parseHex("c1".repeat(16)),
                Ipv4.of(198, 51, 100, 11),
                4672,
                4662,
                8,
                0,
                NodesDat.UdpKey.NONE,
                true);
        NodesDat.Contact second = new NodesDat.Contact(
                UInt128.parseHex("c2".repeat(16)),
                Ipv4.of(198, 51, 100, 12),
                4672,
                4662,
                8,
                0,
                NodesDat.UdpKey.NONE,
                true);
        Path file = this.dirs.config().resolve(KadNode.NODES_FILE);
        // A bootstrap list keeps nothing of its own.
        Files.write(file, NodesDat.write(List.of(first)));

        assertThat(this.onLoop(() -> network.addKadContacts(new NodesDat.Contents(2, true, List.of(second)))))
                .isZero();
        assertThat(this.onLoop(() -> network.addKadContacts(new NodesDat.Contents(2, false, List.of(first, second)))))
                .isEqualTo(1);

        assertThat(NodesDat.read(Files.readAllBytes(file)).contacts())
                .extracting(NodesDat.Contact::id)
                .containsExactly(first.id(), second.id());
    }

    @Test
    void newSettingsMoveTheServerAndKadAndTurnTheNetworksOff() throws Exception {
        this.preferences = this.preferences.toBuilder().kadEnabled(true).build();
        Network network = this.started();
        try (ScriptedServer server = ScriptedServer.start(HIGH_ID, 0)) {
            this.connect(network, server);
            assertThat(server.next(Opcodes.Server.LOGINREQUEST.code(), Duration.ofSeconds(10)))
                    .isPresent();
            this.next(EngineEvent.ServerConnected.class);
            int kadPort = this.onLoop(network::kadInfo).orElseThrow().udpPort();

            int tcp = freeTcpPort();
            int udp = freeUdpPort();
            this.reconfigure(network, p -> p.tcpPort(tcp).udpPort(udp));

            Packet login = server.next(Opcodes.Server.LOGINREQUEST.code(), Duration.ofSeconds(10))
                    .orElseThrow();
            assertThat(ServerMessages.Login.decode(login).tcpPort())
                    .as("logged in again with the new port")
                    .isEqualTo(tcp);
            assertThat(this.onLoop(network::kadInfo).orElseThrow().udpPort())
                    .as("Kad moved too")
                    .isEqualTo(udp)
                    .isNotEqualTo(kadPort);
            this.next(EngineEvent.ServerConnected.class);

            this.reconfigure(network, p -> p.ed2kEnabled(false).rememberCancelledFiles(false));

            this.next(EngineEvent.ServerDisconnected.class);
            assertThat(this.onLoop(network::status).server()).isEmpty();
        }
    }

    @Test
    void whatTheServerSaysIsLoggedAndTheServersItKnowsAreLearnt() throws Exception {
        this.preferences = this.preferences.toBuilder()
                .addServersFromServer(true)
                .filterLanAddresses(true)
                .build();
        int blocked = Ipv4.of(203, 0, 113, 7);
        Files.write(
                this.dirs.config().resolve(Network.IPFILTER_FILE),
                IpFilterDat.write(List.of(new IpFilterDat.Range(blocked, blocked, 0, "test"))));
        Network network = this.started();
        try (ScriptedServer server = ScriptedServer.start(HIGH_ID, ServerMessages.TCP_RELATED_SEARCH)) {
            this.connect(network, server);
            EngineEvent.ServerConnected connected = this.next(EngineEvent.ServerConnected.class);
            assertThat(connected.clientId()).isEqualTo(HIGH_ID);
            assertThat(this.onLoop(network::status).relatedSearch()).isTrue();
            assertThat(server.next(Opcodes.Server.GETSERVERLIST.code(), Duration.ofSeconds(10)))
                    .isPresent();

            server.send(
                    ServerMessages.message("ERROR: no more room\nWARNING: low id\nWelcome"),
                    ServerMessages.serverList(List.of(
                            // No UDP port above these: they are never pinged.
                            new ServerMessages.Endpoint(Ipv4.of(198, 51, 100, 1), 65_534),
                            new ServerMessages.Endpoint(Ipv4.of(198, 51, 100, 2), 65_533))),
                    callback(Ipv4.of(10, 0, 0, 5)),
                    callback(blocked),
                    callback(Ipv4.of(127, 0, 0, 9)),
                    Packet.empty(Protocol.EDONKEY, Opcodes.Server.REJECT.code()),
                    Packet.of(Protocol.EDONKEY, Opcodes.Server.CALLBACKREQUESTED.code(), new byte[2]),
                    Packet.of(Protocol.EDONKEY, 0xEE, new byte[2]),
                    new ServerMessages.ServerStatus(500, 600).encode());

            assertThat(this.next(EngineEvent.ServerUpdated.class).server().users())
                    .isEqualTo(500);
            assertThat(this.log)
                    .contains(
                            new Logged(LogLine.Level.ERROR, "ERROR: no more room"),
                            new Logged(LogLine.Level.WARNING, "WARNING: low id"),
                            new Logged(LogLine.Level.INFO, "2 new servers from 127.0.0.1:" + server.port()));
            assertThat(this.onLoop(network::servers)).hasSize(2);

            server.hangUp();

            EngineEvent.ServerDisconnected gone = this.next(EngineEvent.ServerDisconnected.class);
            assertThat(gone.server()).isPresent();
            assertThat(this.onLoop(network::status).clientId()).isZero();
        }
    }

    @Test
    void largeFilesAreAskedForOnlyFromServersThatTakeThem() throws Exception {
        this.preferences = this.preferences.toBuilder().addDownloadsPaused(true).build();
        Network network = this.started();
        Ed2kLink.FileLink large = Ed2kLink.FileLink.of("large.iso", 5_000_000_000L, Hash16.parseHex("d1".repeat(16)));
        Ed2kLink.FileLink small = Ed2kLink.FileLink.of("small.iso", 5000, Hash16.parseHex("d2".repeat(16)));
        assertThat(this.onLoop(() -> network.addDownload(large)).status()).isEqualTo(DownloadInfo.Status.PAUSED);
        assertThat(this.onLoop(() -> network.addDownload(large)).status())
                .as("added again: left as it is")
                .isEqualTo(DownloadInfo.Status.PAUSED);
        this.onLoop(() -> network.addDownload(small));
        try (ScriptedServer server = ScriptedServer.start(HIGH_ID, ServerMessages.TCP_OBFUSCATION)) {
            this.connect(network, server);
            this.next(EngineEvent.ServerConnected.class);

            this.onLoop(() -> network.resumeDownload(large.hash()) && network.resumeDownload(small.hash()));

            Packet asked = server.next(Opcodes.Server.GETSOURCES_OBFU.code(), Duration.ofSeconds(10))
                    .orElseThrow();
            assertThat(asked.reader().hash16()).isEqualTo(small.hash());
            assertThat(server.next(Opcodes.Server.GETSOURCES_OBFU.code(), Duration.ofSeconds(3)))
                    .as("never for the large one")
                    .isEmpty();
        }
    }

    @Test
    void theFriendSlotGoesWithTheFriend() throws Exception {
        Network network = this.create();
        Hash16 pal = Hash16.parseHex("e1".repeat(16));
        this.onLoop(() -> {
            network.addFriend(new Friend(pal, "pal", Optional.empty()));
            return null;
        });
        assertThat(this.onLoop(() -> network.setFriendSlot(Optional.of(pal)))).isTrue();

        assertThat(this.onLoop(() -> network.removeFriend(pal))).isTrue();

        assertThat(this.onLoop(network::friendSlot)).isEmpty();
        assertThat(this.onLoop(network::friends)).isEmpty();
    }

    @Test
    void downloadsCanBeStoppedPrioritisedAndPreviewed() throws Exception {
        this.preferences =
                this.preferences.toBuilder().rememberCancelledFiles(false).build();
        Network network = this.create();
        Ed2kLink.FileLink film = Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f1".repeat(16)));
        this.onLoop(() -> network.addDownload(film));

        assertThat(this.onLoop(() -> network.setDownloadPriority(film.hash(), DownloadPriority.HIGH)))
                .isTrue();
        assertThat(this.onLoop(() -> network.setDownloadPreview(film.hash(), true)))
                .isTrue();
        assertThat(this.onLoop(() -> network.downloadData(film.hash())))
                .as("nothing to preview yet")
                .isEmpty();
        assertThat(this.onLoop(() -> network.stopDownload(film.hash()))).isTrue();
        assertThat(this.onLoop(network::downloads)).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo(DownloadInfo.Status.STOPPED);
            assertThat(d.priority()).isEqualTo(DownloadPriority.HIGH);
        });

        assertThat(this.onLoop(() -> network.cancelDownload(film.hash()))).isTrue();
        assertThat(this.onLoop(network::history).cancelled(film.hash()))
                .as("not remembered")
                .isFalse();
    }

    @Test
    void aCancelledDownloadThatCannotBeRememberedIsStillCancelled() throws Exception {
        Network network = this.create();
        Ed2kLink.FileLink film = Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("f2".repeat(16)));
        this.onLoop(() -> network.addDownload(film));
        blockWrites(this.dirs.config().resolve(CancelledFiles.FILE_NAME));

        assertThat(this.onLoop(() -> network.cancelDownload(film.hash()))).isTrue();

        assertThat(this.onLoop(network::downloads)).isEmpty();
    }

    @Test
    void aSchedulerRuleLeavesDownloadsOfOtherCategoriesAndRunningOnesAlone() throws Exception {
        Network network = this.create();
        Ed2kLink.FileLink film = Ed2kLink.FileLink.of("film.mkv", 5000, Hash16.parseHex("a3".repeat(16)));
        Ed2kLink.FileLink song = Ed2kLink.FileLink.of("song.mp3", 5000, Hash16.parseHex("a4".repeat(16)));
        this.onLoop(() -> {
            network.setCategories(List.of(
                    new Category(1, "Films", Optional.empty(), "", 1, DownloadPriority.AUTO, "", false),
                    new Category(2, "Music", Optional.empty(), "", 2, DownloadPriority.AUTO, "", false)));
            network.addDownload(film);
            network.addDownload(song);
            network.setDownloadCategory(film.hash(), 1);
            network.setDownloadCategory(song.hash(), 2);
            return null;
        });

        this.onLoop(() -> {
            network.scheduleCategories(OptionalInt.empty(), OptionalInt.of(2));
            network.scheduleCategories(OptionalInt.of(3), OptionalInt.empty());
            return null;
        });

        assertThat(this.onLoop(network::downloads))
                .extracting(DownloadInfo::status)
                .containsOnly(DownloadInfo.Status.DOWNLOADING);
        assertThat(this.log)
                .contains(
                        new Logged(LogLine.Level.INFO, "Scheduler: downloads of category 2 resumed"),
                        new Logged(LogLine.Level.INFO, "Scheduler: downloads of category 3 stopped"));
    }

    @Test
    void aHelloToNobodyOrToAClientThatHangsUpFails() throws Exception {
        Network network = this.create();
        int closed = freeTcpPort();

        assertThatThrownBy(() -> network.hello(local(closed)).get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IOException.class);

        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<PeerInfo> answer = network.hello(local(peer.getLocalPort()));
            try (Socket socket = peer.accept()) {
                assertThat(read(socket).opcode()).isEqualTo(Opcodes.Client.HELLO.code());
            }
            assertThatThrownBy(() -> answer.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
        }

        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<PeerInfo> answer = network.hello(local(peer.getLocalPort()));
            try (Socket socket = peer.accept()) {
                read(socket);
                socket.getOutputStream()
                        .write(TcpFraming.encode(
                                Packet.of(Protocol.EDONKEY, Opcodes.Client.HELLOANSWER.code(), new byte[3])));
                assertThatThrownBy(() -> answer.get(20, TimeUnit.SECONDS))
                        .as("an answer that cannot be read")
                        .hasCauseInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void aClientWithoutTheIdentificationIsTold() throws Exception {
        Network network = this.create();
        Hash16 peerHash = Hash16.parseHex("21".repeat(16));
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<PeerInfo> answer = network.hello(local(peer.getLocalPort()));
            try (Socket socket = peer.accept()) {
                Hello ours = Hello.decode(read(socket));
                assertThat(ours.userHash()).isEqualTo(USER);
                Hello theirs = Hello.of(peerHash, 0, 4999, "peer", 0, 0, 0, 0, 0, 0);
                socket.getOutputStream().write(TcpFraming.encode(theirs.encode(true)));

                PeerInfo info = answer.get(20, TimeUnit.SECONDS);
                assertThat(info.userHash()).isEqualTo(peerHash);
                assertThat(info.nick()).contains("peer");
                assertThat(info.identification()).isEqualTo(PeerInfo.Identification.NOT_SUPPORTED);

                // A hello of its own afterwards is answered.
                socket.getOutputStream().write(TcpFraming.encode(theirs.encode(false)));
                assertThat(read(socket).opcode()).isEqualTo(Opcodes.Client.HELLOANSWER.code());
            }
        }
    }

    @Test
    void theServerOfAClientIsLearntWhenThePreferencesAskForIt() throws Exception {
        this.preferences =
                this.preferences.toBuilder().addServersFromClients(true).build();
        this.started();
        // A documentation address with no UDP port above it: never pinged, never connected to.
        Hello hello = Hello.of(
                Hash16.parseHex("23".repeat(16)), 0, 4999, "peer", 0, 0, 0, 0, Ipv4.of(198, 51, 100, 3), 65_534);

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), this.preferences.tcpPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(TcpFraming.encode(hello.encode(false)));

            assertThat(this.next(EngineEvent.ServersChanged.class).count()).isEqualTo(1);
        }
        assertThat(this.log).contains(new Logged(LogLine.Level.INFO, "1 new server from a client"));
    }

    private Network create() throws Exception {
        this.network = this.onLoop(() -> new Network(
                this.loop,
                this.dirs.config(),
                () -> this.preferences,
                this.events::add,
                (level, text) -> this.log.add(new Logged(level, text)),
                USER,
                this.dirs.data().resolve("temp"),
                () -> this.dirs.data().resolve("incoming"),
                Optional.empty()));
        return this.network;
    }

    private Network started() throws Exception {
        Network created = this.create();
        this.onLoop(() -> {
            created.start();
            return null;
        });
        return created;
    }

    private void connect(Network network, ScriptedServer server) throws Exception {
        this.onLoop(() -> {
            network.connect(Optional.of(new ServerList.Address(server.ip(), server.port())));
            return null;
        });
    }

    private void reconfigure(Network network, UnaryOperator<Preferences.Builder> change) throws Exception {
        Preferences was = this.preferences;
        Preferences now = change.apply(was.toBuilder()).build();
        this.preferences = now;
        this.onLoop(() -> {
            network.reconfigure(was, now);
            return null;
        });
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(20, TimeUnit.SECONDS);
    }

    private <E extends EngineEvent> E next(Class<E> type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            EngineEvent event = this.events.poll(100, TimeUnit.MILLISECONDS);
            if (type.isInstance(event)) {
                return type.cast(event);
            }
        }
        throw new AssertionError("no " + type.getSimpleName());
    }

    private static InetSocketAddress local(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static Packet read(Socket socket) throws IOException {
        socket.setSoTimeout(10_000);
        return TcpFraming.read(new BufferedInputStream(socket.getInputStream()), FrameLimit.fixed(1 << 20));
    }

    // A request of the server that we call a client back.
    private static Packet callback(int ip) {
        return Packet.of(
                Protocol.EDONKEY,
                Opcodes.Server.CALLBACKREQUESTED.code(),
                new PacketWriter().u32(Ipv4.toEd2k(ip)).u16(4662));
    }

    // A directory where the file's next version is written first: every save of the file fails.
    private static void blockWrites(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("keep"), "x");
    }

    private static int freeTcpPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static int freeUdpPort() throws IOException {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
