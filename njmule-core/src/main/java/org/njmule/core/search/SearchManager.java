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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.hash.Ed2k;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerSession;
import org.njmule.core.server.ServerUdp;
import org.njmule.core.util.Ipv4;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Searches on the eD2K servers: the connected server first over TCP, then, for global searches, the other known servers
 * over UDP, one every {@link #GLOBAL_INTERVAL}. Results with the same hash are merged and their sources add up. UDP
 * answers count only from servers this search asked. Used on the engine thread only.
 */
public final class SearchManager {

    /** What searches report, on the engine thread. */
    public interface Listener {
        void results(SearchId search, List<SearchResult> changed);

        void ended(SearchId search, int results);
    }

    /** How long to wait for the connected server before giving up on it. */
    public static final Duration SERVER_WAIT = Duration.ofSeconds(50);

    /** Pace of the UDP queries of a global search. */
    public static final Duration GLOBAL_INTERVAL = Duration.ofMillis(750);

    /** Time left for UDP answers after the last query. */
    public static final Duration ANSWER_GRACE = Duration.ofSeconds(5);

    /** A global search stops querying servers once it has this many results. */
    public static final int MAX_RESULTS = 300;

    /** Requests for more results of one search, as servers allow. */
    public static final int MAX_MORE_REQUESTS = 5;

    /** A Kad search ends after this long. */
    public static final Duration KAD_WAIT = Duration.ofSeconds(50);

    /** Kad, when it runs. */
    public interface KadSearches {
        /**
         * Looks for files under {@code keyword}, filtered by {@code expression}; returns {@code false} when Kad cannot
         * search for it now.
         */
        boolean search(String keyword, SearchExpr expression, Consumer<List<SearchResult>> results);
    }

    private static final Logger logger = LoggerFactory.getLogger(SearchManager.class);

    private final EngineLoop loop;
    private final ServerSession session;
    private final ServerList servers;
    private final Supplier<Optional<ServerUdp>> udp;
    private final Supplier<Optional<KadSearches>> kad;
    private final Listener listener;
    private final Map<SearchId, Search> searches = new HashMap<>();
    private long nextId = 1;
    // The search the connected server is answering.
    private Search awaitingServer;

    public SearchManager(
            EngineLoop loop,
            ServerSession session,
            ServerList servers,
            Supplier<Optional<ServerUdp>> udp,
            Supplier<Optional<KadSearches>> kad,
            Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.session = Objects.requireNonNull(session, "session");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.udp = Objects.requireNonNull(udp, "udp");
        this.kad = Objects.requireNonNull(kad, "kad");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Starts a search on the connected server; a search still waiting for it ends.
     *
     * @throws SearchSyntaxException for an invalid expression
     * @throws IllegalStateException when no server is connected
     */
    public SearchId start(SearchRequest request) {
        SearchExpr expression = SearchParser.parse(request.expression());
        if (request.scope() == SearchRequest.Scope.KAD) {
            return this.startKad(request, expression);
        }
        ServerMessages.IdChange id =
                this.session.id().orElseThrow(() -> new IllegalStateException("not connected to a server"));
        if (this.awaitingServer != null) {
            this.end(this.awaitingServer);
        }
        Search search = new Search(new SearchId(this.nextId++), expression, request.scope());
        this.searches.put(search.id, search);
        this.session.send(ServerMessages.searchRequest(expression, id.has(ServerMessages.TCP_LARGEFILES)));
        this.waitForServer(search);
        return search.id;
    }

    // The longest word of the expression is the keyword looked up, as short common words land on
    // overloaded nodes; the whole expression filters.
    private SearchId startKad(SearchRequest request, SearchExpr expression) {
        KadSearches searches = this.kad.get().orElseThrow(() -> new IllegalStateException("Kad is not running"));
        String keyword = kadKeyword(request.expression());
        Search search = new Search(new SearchId(this.nextId++), expression, request.scope());
        boolean started = searches.search(keyword, expression, results -> {
            if (!search.ended) {
                this.mergeResults(search, results, false);
            }
        });
        if (!started) {
            throw new IllegalStateException("Kad cannot search for \"" + keyword + "\" now");
        }
        this.searches.put(search.id, search);
        search.serverTimer = this.loop.schedule("Kad search", KAD_WAIT, () -> this.end(search));
        return search.id;
    }

    /** The longest word of {@code expression}, leaving out operators and attributes. */
    static String kadKeyword(String expression) {
        String best = "";
        for (String token : expression.strip().split("\\s+", -1)) {
            String word = token.replace("\"", "").replace("(", "").replace(")", "");
            boolean operator =
                    word.equalsIgnoreCase("AND") || word.equalsIgnoreCase("OR") || word.equalsIgnoreCase("NOT");
            if (operator || word.startsWith("@") || word.matches(".*[=<>].*")) {
                continue;
            }
            if (word.codePointCount(0, word.length()) > best.codePointCount(0, best.length())) {
                best = word;
            }
        }
        return best;
    }

    /** Asks the server for more results; {@code false} if it has none or the limit is reached. */
    public boolean more(SearchId id) {
        Search search = this.searches.get(id);
        if (search == null
                || search.ended
                || !search.moreAvailable
                || search.moreRequests >= MAX_MORE_REQUESTS
                || !this.session.send(ServerMessages.queryMoreResults())) {
            return false;
        }
        search.moreRequests++;
        search.moreAvailable = false;
        this.waitForServer(search);
        return true;
    }

    public void stop(SearchId id) {
        Search search = this.searches.get(id);
        if (search != null && !search.ended) {
            this.end(search);
        }
    }

    /** Results from the connected server, for the search it is answering. */
    public void serverResults(ServerMet.Server server, ServerMessages.SearchResults results) {
        Search search = this.awaitingServer;
        if (search == null || search.ended) {
            return;
        }
        this.awaitingServer = null;
        search.serverTimer.cancel(false);
        search.moreAvailable = results.more();
        this.merge(search, results.files());
        this.afterServer(search);
    }

    /** A UDP answer from {@code from}; ignored unless an open global search asked that server. */
    public void datagramResults(InetSocketAddress from, List<FileEntry> files) {
        int ip = Ipv4.fromBytes(from.getAddress().getAddress());
        for (Search search : this.searches.values()) {
            if (!search.ended && search.askedOverUdp.contains(ip)) {
                this.merge(search, files);
                return;
            }
        }
        logger.debug("Unrequested search results from {}", from);
    }

    /** Ends every search, e.g. when the server connection is lost. */
    public void stopAll() {
        for (Search search : List.copyOf(this.searches.values())) {
            if (!search.ended) {
                this.end(search);
            }
        }
    }

    private void waitForServer(Search search) {
        this.awaitingServer = search;
        search.serverTimer = this.loop.schedule("search server wait", SERVER_WAIT, () -> this.serverSilent(search));
    }

    private void serverSilent(Search search) {
        if (this.awaitingServer == search && !search.ended) {
            this.awaitingServer = null;
            this.afterServer(search);
        }
    }

    private void afterServer(Search search) {
        if (search.scope == SearchRequest.Scope.GLOBAL && !search.globalStarted) {
            this.startGlobal(search);
        } else if (search.scope == SearchRequest.Scope.SERVER || search.udpQueue.isEmpty()) {
            if (!search.moreAvailable && search.udpTimer == null) {
                this.end(search);
            }
        }
    }

    private void startGlobal(Search search) {
        search.globalStarted = true;
        Optional<ServerList.Address> connected = this.session.server().map(ServerList::addressOf);
        for (ServerMet.Server server : this.servers.candidates()) {
            if (connected.isEmpty() || !connected.get().equals(ServerList.addressOf(server))) {
                search.udpQueue.add(server);
            }
        }
        if (this.udp.get().isEmpty() || search.udpQueue.isEmpty()) {
            this.end(search);
            return;
        }
        search.udpTimer = this.loop.scheduleRepeating(
                "global search", Duration.ZERO, GLOBAL_INTERVAL, () -> this.queryNext(search));
    }

    private void queryNext(Search search) {
        if (search.ended) {
            return;
        }
        Optional<ServerUdp> socket = this.udp.get();
        if (search.udpQueue.isEmpty() || search.results.size() >= MAX_RESULTS || socket.isEmpty()) {
            search.udpTimer.cancel(false);
            this.loop.schedule("global search answers", ANSWER_GRACE, () -> this.end(search));
            return;
        }
        ServerMet.Server server = search.udpQueue.poll();
        Optional<Packet> request = ServerDatagrams.searchRequest(
                search.expression, (int) server.udpFlags().orElse(0));
        if (request.isEmpty()) {
            return;
        }
        InetSocketAddress address =
                new InetSocketAddress(Ipv4.format(server.ip()), server.port() + ServerUdp.PORT_OFFSET);
        try {
            socket.get().send(address, request.get());
            search.askedOverUdp.add(server.ip());
        } catch (IOException e) {
            logger.debug("Cannot query {}", address, e);
        }
    }

    private void merge(Search search, List<FileEntry> files) {
        List<SearchResult> found = new ArrayList<>();
        for (FileEntry file : files) {
            Optional<String> name = file.name();
            if (name.isEmpty() || file.size().isEmpty()) {
                continue;
            }
            found.add(new SearchResult(
                    file.hash(),
                    name.get(),
                    file.size().getAsLong(),
                    file.sources(),
                    file.completeSources(),
                    file.type()));
        }
        this.mergeResults(search, found, true);
    }

    /**
     * Adds results to a search. Unusable ones (no name, a size of zero or beyond eD2K's limit, a known hash with
     * another size) are left out. Servers count their own sources, so their counts add up; Kad nodes report the same
     * sources, so the largest count is kept.
     */
    private void mergeResults(Search search, List<SearchResult> found, boolean addCounts) {
        List<SearchResult> changed = new ArrayList<>();
        for (SearchResult file : found) {
            if (file.name().isBlank() || file.size() <= 0 || file.size() > Ed2k.MAX_FILE_SIZE) {
                continue;
            }
            if (!search.results.containsKey(file.hash()) && search.results.size() >= MAX_RESULTS) {
                continue;
            }
            SearchResult known = search.results.get(file.hash());
            if (known != null && known.size() != file.size()) {
                continue;
            }
            SearchResult merged = known == null
                    ? file
                    : new SearchResult(
                            known.hash(),
                            known.name(),
                            known.size(),
                            addCounts
                                    ? saturatedAdd(known.sources(), file.sources())
                                    : Math.max(known.sources(), file.sources()),
                            addCounts
                                    ? saturatedAdd(known.completeSources(), file.completeSources())
                                    : Math.max(known.completeSources(), file.completeSources()),
                            known.type().or(file::type));
            search.results.put(file.hash(), merged);
            changed.add(merged);
        }
        if (!changed.isEmpty()) {
            this.listener.results(search.id, changed);
        }
    }

    private void end(Search search) {
        if (search.ended) {
            return;
        }
        search.ended = true;
        if (search.serverTimer != null) {
            search.serverTimer.cancel(false);
        }
        if (search.udpTimer != null) {
            search.udpTimer.cancel(false);
        }
        if (this.awaitingServer == search) {
            this.awaitingServer = null;
        }
        this.listener.ended(search.id, search.results.size());
    }

    private static int saturatedAdd(int a, int b) {
        return (int) Math.min(Integer.MAX_VALUE, (long) a + b);
    }

    private static final class Search {
        final SearchId id;
        final SearchExpr expression;
        final SearchRequest.Scope scope;
        final Map<Hash16, SearchResult> results = new LinkedHashMap<>();
        final Deque<ServerMet.Server> udpQueue = new ArrayDeque<>();
        final Set<Integer> askedOverUdp = new HashSet<>();
        ScheduledFuture<?> serverTimer;
        ScheduledFuture<?> udpTimer;
        boolean globalStarted;
        boolean moreAvailable;
        int moreRequests;
        boolean ended;

        Search(SearchId id, SearchExpr expression, SearchRequest.Scope scope) {
            this.id = id;
            this.expression = expression;
            this.scope = scope;
        }
    }
}
