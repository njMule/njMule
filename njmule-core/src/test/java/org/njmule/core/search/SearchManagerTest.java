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
package org.njmule.core.search;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.Preferences;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.TcpFraming;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.ServerTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerSession;
import org.njmule.core.server.ServerUdp;
import org.njmule.core.util.Ipv4;

/** Searches on a scripted server on the loopback address and on a Kad the test plays. */
@Timeout(60)
class SearchManagerTest {

    private static final int LOOPBACK = Ipv4.parse("127.0.0.1");
    private static final long HIGH_ID = Ipv4.toEd2k(Ipv4.parse("198.51.100.7"));

    private final EngineLoop loop = EngineLoop.start(1024, "test-engine");
    private final ServerList servers = new ServerList();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<SearchResult> reported = new CopyOnWriteArrayList<>();
    private Consumer<List<SearchResult>> kadAnswers;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private volatile Optional<ServerUdp> udp = Optional.empty();
    private volatile Optional<SearchManager.KadSearches> kad = Optional.empty();
    private final ServerSession session = new ServerSession(
            this.loop,
            this.servers,
            Preferences::defaults,
            Hash16.parseHex("01".repeat(16)),
            () -> 4662,
            new Session(),
            new SecureRandom());
    private final SearchManager manager =
            new SearchManager(this.loop, this.session, this.servers, () -> this.udp, () -> this.kad, new Events());

    @AfterEach
    void stop() throws Exception {
        this.loop.close();
        for (AutoCloseable resource : this.resources) {
            resource.close();
        }
    }

    @Test
    void kadSearchesKeepTheLargestCountsUpToTheLimitAndEndWhenStopped() throws Exception {
        assertThatThrownBy(() -> this.onLoop(() -> this.manager.start(kad("linux"))))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Kad is not running");
        List<String> keywords = new ArrayList<>();
        AtomicReference<Consumer<List<SearchResult>>> found = new AtomicReference<>();
        List<String> stopped = new ArrayList<>();
        this.kad = Optional.of((keyword, expression, results) -> {
            keywords.add(keyword);
            if (keyword.equals("never")) {
                return Optional.empty();
            }
            found.set(results);
            return Optional.of(() -> stopped.add(keyword));
        });
        assertThatThrownBy(() -> this.onLoop(() -> this.manager.start(kad("never"))))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        SearchId id = this.onLoop(() -> this.manager.start(kad("rare linux distro")));
        assertThat(keywords).last().as("the longest word").isEqualTo("distro");
        Hash16 iso = hash(1);
        this.onLoop(() -> {
            found.get()
                    .accept(List.of(
                            new SearchResult(iso, "distro.iso", 1000, 5, 1, Optional.empty()),
                            new SearchResult(hash(2), " ", 1000, 1, 1, Optional.empty()),
                            new SearchResult(hash(3), "empty.iso", 0, 1, 1, Optional.empty())));
            found.get()
                    .accept(List.of(
                            new SearchResult(iso, "distro.iso", 1000, 3, 4, Optional.of("Iso")),
                            new SearchResult(iso, "distro.iso", 2000, 9, 9, Optional.empty())));
            return null;
        });
        assertThat(this.next()).isEqualTo("results " + id.value() + " distro.iso 5/1");
        assertThat(this.next())
                .as("Kad nodes report the same sources: the largest count is kept")
                .isEqualTo("results " + id.value() + " distro.iso 5/4");

        List<SearchResult> many = new ArrayList<>();
        for (int i = 0; i < SearchManager.MAX_RESULTS + 1; i++) {
            many.add(new SearchResult(hash(1000 + i), "file" + i + ".bin", 10, 1, 0, Optional.empty()));
        }
        this.onLoop(() -> {
            found.get().accept(many);
            return null;
        });
        assertThat(this.next()).startsWith("results " + id.value() + " file0.bin");
        this.onLoop(() -> {
            this.manager.serverLost();
            this.manager.stop(id);
            this.manager.stop(id);
            found.get().accept(List.of(new SearchResult(hash(5), "late.iso", 10, 1, 0, Optional.empty())));
            return null;
        });

        assertThat(this.next())
                .as("a Kad search outlives the server; once stopped it takes nothing more")
                .isEqualTo("ended " + id.value() + " " + SearchManager.MAX_RESULTS);
        assertThat(stopped).containsExactly("distro");
        assertThat(this.events.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void shouldFillTheMediaDetailsAFirstAnswerLackedFromLaterOnes() throws Exception {
        this.givenAKadSearchFor("opening song");
        this.givenKadAnswered(song(new SearchResult.Media("Some Band", "", "", 0, 192, "")));

        this.whenKadAnswers(song(new SearchResult.Media("Other Band", "First Album", "", 205, 128, "mp3")));

        this.thenTheLastResultHasTheMedia(new SearchResult.Media("Some Band", "First Album", "", 205, 192, "mp3"));
    }

    private void givenAKadSearchFor(String words) throws Exception {
        this.kad = Optional.of((keyword, expression, results) -> {
            this.kadAnswers = results;
            return Optional.of(() -> {});
        });
        this.onLoop(() -> this.manager.start(kad(words)));
    }

    private void givenKadAnswered(SearchResult result) throws Exception {
        this.whenKadAnswers(result);
    }

    private void whenKadAnswers(SearchResult result) throws Exception {
        this.onLoop(() -> {
            this.kadAnswers.accept(List.of(result));
            return null;
        });
        this.next();
    }

    private void thenTheLastResultHasTheMedia(SearchResult.Media expected) {
        assertThat(this.reported.getLast().media()).isEqualTo(expected);
    }

    private static SearchResult song(SearchResult.Media media) {
        return new SearchResult(hash(9), "opening song.mp3", 4_000_000, 1, 0, Optional.of("Audio"), media);
    }

    @Test
    void kadLooksUpTheLongestWordEveryMatchHas() {
        assertThat(SearchManager.kadKeyword(SearchParser.parse("@type=video linux")))
                .isEqualTo("linux");
        assertThat(SearchManager.kadKeyword(SearchParser.parse("ubuntu -windows")))
                .isEqualTo("ubuntu");
        assertThat(SearchManager.kadKeyword(SearchParser.parse("\"free software\" go")))
                .as("words in quotes when there are no others")
                .isEqualTo("software");
        assertThatThrownBy(() -> SearchManager.kadKeyword(SearchParser.parse("debian OR ubuntu")))
                .hasMessageContaining("OR");
        assertThatThrownBy(() -> SearchManager.kadKeyword(SearchParser.parse("go to")))
                .hasMessageContaining("three letters");
    }

    @Test
    void aServerSearchTakesMoreResultsAndEndsWhenTheServerIsLost() throws Exception {
        assertThatThrownBy(() -> this.onLoop(() -> this.manager.start(server("linux"))))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        this.onLoop(() -> {
            this.manager.serverResults(
                    new ServerMet.Server(LOOPBACK, 4661, List.of()),
                    new ServerMessages.SearchResults(List.of(), false));
            this.manager.datagramResults(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 4665), List.of(entry(1, "x.iso", 10, 1)));
            return null;
        });
        assertThat(this.events.poll(200, TimeUnit.MILLISECONDS))
                .as("answers no search asked for")
                .isNull();
        ScriptedServer server = this.connect();

        SearchId id = this.onLoop(() -> this.manager.start(server("linux")));
        assertThat(server.next().opcode()).isEqualTo(Opcodes.Server.SEARCHREQUEST.code());
        server.send(new ServerMessages.SearchResults(
                        List.of(
                                entry(1, "linux.iso", 1000, 2),
                                new FileEntry(hash(2), 0, 0, List.of()),
                                new FileEntry(
                                        hash(4),
                                        0,
                                        0,
                                        List.of(new Tag.StringTag(TagName.id(FileTag.FILENAME), "nosize.iso"))),
                                entry(1, "linux.iso", 2000, 7)),
                        true)
                .encode());
        assertThat(this.next()).isEqualTo("results " + id.value() + " linux.iso 2/2");
        assertThat(this.onLoop(() -> this.manager.more(id))).isTrue();
        assertThat(server.next().opcode()).isEqualTo(Opcodes.Server.QUERY_MORE_RESULT.code());
        assertThat(this.onLoop(() -> this.manager.more(id)))
                .as("one request at a time")
                .isFalse();
        server.send(new ServerMessages.SearchResults(List.of(entry(1, "linux.iso", 1000, 3)), false).encode());
        assertThat(this.next())
                .as("servers count their own sources: they add up")
                .isEqualTo("results " + id.value() + " linux.iso 5/5");
        assertThat(this.next()).isEqualTo("ended " + id.value() + " 1");
        assertThat(this.onLoop(() -> this.manager.more(id))).isFalse();

        SearchId lost = this.onLoop(() -> this.manager.start(server("lost")));
        server.next();
        this.onLoop(() -> {
            this.manager.serverLost();
            return null;
        });
        assertThat(this.next()).isEqualTo("ended " + lost.value() + " 0");
        server.send(new ServerMessages.SearchResults(List.of(entry(3, "late.iso", 10, 1)), false).encode());
        assertThat(this.events.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aGlobalSearchEndsWhereNoServerCanBeAskedOverUdp() throws Exception {
        ServerMet.Server wide = new ServerMet.Server(Ipv4.of(192, 0, 2, 1), 4661, List.of())
                .with(intTag(ServerTag.UDPFLAGS, ServerDatagrams.UDP_LARGEFILES | ServerDatagrams.UDP_EXT_GETFILES));
        ServerMet.Server narrow = new ServerMet.Server(Ipv4.of(192, 0, 2, 2), 4661, List.of());
        ServerMet.Server noUdpPort = new ServerMet.Server(Ipv4.of(192, 0, 2, 3), 65_533, List.of());
        this.onLoop(() -> {
            this.servers.add(wide);
            this.servers.add(narrow);
            this.servers.add(noUdpPort);
            this.servers.setPriority(ServerList.addressOf(wide), ServerInfo.Priority.HIGH);
            return null;
        });
        ScriptedServer server = this.connect();

        // Without the UDP socket the search ends with the server's answer.
        SearchId alone = this.onLoop(() -> this.manager.start(global("alone")));
        server.next();
        server.send(new ServerMessages.SearchResults(List.of(), false).encode());
        assertThat(this.next()).isEqualTo("ended " + alone.value() + " 0");

        // A query that cannot be sent, and one that cannot be made for a server without 64-bit sizes: the search
        // still ends, once the time for answers is over.
        ServerUdp closed = ServerUdp.open(0, (from, packet) -> {});
        closed.close();
        this.udp = Optional.of(closed);
        SearchId sized = this.onLoop(() -> this.manager.start(global("@size>5g linux")));
        server.next();
        server.send(new ServerMessages.SearchResults(List.of(), false).encode());
        assertThat(this.next(SearchManager.ANSWER_GRACE.plusSeconds(10).toMillis()))
                .isEqualTo("ended " + sized.value() + " 0");
    }

    private ScriptedServer connect() throws Exception {
        ServerSocket listening = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.resources.add(listening);
        this.onLoop(() -> {
            this.session.connect(Optional.of(new ServerMet.Server(LOOPBACK, listening.getLocalPort(), List.of())));
            return null;
        });
        listening.setSoTimeout(10_000);
        ScriptedServer server = new ScriptedServer(listening.accept());
        this.resources.add(server);
        assertThat(server.next().opcode()).isEqualTo(Opcodes.Server.LOGINREQUEST.code());
        server.send(new ServerMessages.IdChange(HIGH_ID, ServerMessages.TCP_LARGEFILES, 0).encode());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!this.onLoop(this.session::isConnected)) {
            assertThat(System.nanoTime()).as("connected in time").isLessThan(deadline);
            Thread.sleep(20);
        }
        return server;
    }

    private static SearchRequest kad(String expression) {
        return new SearchRequest(expression, SearchRequest.Scope.KAD);
    }

    private static SearchRequest server(String expression) {
        return new SearchRequest(expression, SearchRequest.Scope.SERVER);
    }

    private static SearchRequest global(String expression) {
        return new SearchRequest(expression, SearchRequest.Scope.GLOBAL);
    }

    private static FileEntry entry(int hash, String name, long size, int sources) {
        return new FileEntry(
                hash(hash),
                0,
                0,
                List.of(
                        new Tag.StringTag(TagName.id(FileTag.FILENAME), name),
                        new Tag.IntTag(TagName.id(FileTag.FILESIZE), size),
                        new Tag.IntTag(TagName.id(FileTag.SOURCES), sources),
                        new Tag.IntTag(TagName.id(FileTag.COMPLETE_SOURCES), sources)));
    }

    private static Tag intTag(int id, long value) {
        return new Tag.IntTag(TagName.id(id), value);
    }

    private static Hash16 hash(int seed) {
        return Hash16.parseHex(String.format("%032x", seed));
    }

    private String next() throws InterruptedException {
        return this.next(10_000);
    }

    private String next(long millis) throws InterruptedException {
        String event = this.events.poll(millis, TimeUnit.MILLISECONDS);
        assertThat(event).as("an event").isNotNull();
        return event;
    }

    private <T> T onLoop(Callable<T> task) throws Exception {
        return this.loop.submit(task).get(10, TimeUnit.SECONDS);
    }

    /** The server's side of the connection. */
    private static final class ScriptedServer implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        ScriptedServer(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setSoTimeout(10_000);
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        Packet next() throws IOException {
            return TcpFraming.read(this.in, FrameLimits.SERVER);
        }

        void send(Packet packet) throws IOException {
            this.out.write(TcpFraming.encode(packet));
            this.out.flush();
        }

        @Override
        public void close() throws IOException {
            this.socket.close();
        }
    }

    private final class Events implements SearchManager.Listener {
        @Override
        public void results(SearchId search, List<SearchResult> changed) {
            SearchManagerTest.this.reported.addAll(changed);
            SearchResult first = changed.getFirst();
            SearchManagerTest.this.events.add("results " + search.value() + " " + first.name() + " " + first.sources()
                    + "/" + first.completeSources());
        }

        @Override
        public void ended(SearchId search, int results) {
            SearchManagerTest.this.events.add("ended " + search.value() + " " + results);
        }
    }

    private final class Session implements ServerSession.Listener {
        @Override
        public void connecting(ServerMet.Server server) {}

        @Override
        public void connected(ServerMet.Server server, ServerMessages.IdChange id) {}

        @Override
        public void disconnected(Optional<ServerMet.Server> server, String reason) {}

        @Override
        public void message(ServerMet.Server server, String line) {}

        @Override
        public void updated(ServerMet.Server server) {}

        @Override
        public void searchResults(ServerMet.Server server, ServerMessages.SearchResults results) {
            SearchManagerTest.this.manager.serverResults(server, results);
        }

        @Override
        public void callbackRequested(ServerMessages.CallbackRequested request) {}

        @Override
        public void foundSources(ServerMessages.FoundSources sources) {}

        @Override
        public void serverList(ServerMet.Server server, List<ServerMessages.Endpoint> servers) {}
    }
}
