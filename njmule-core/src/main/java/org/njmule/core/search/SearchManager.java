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

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.Hash20;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.config.ObfuscationMode;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.files.FileTypes;
import org.njmule.core.kad.Keywords;
import org.njmule.core.persist.format.ServerMet;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.server.ServerList;
import org.njmule.core.server.ServerRoute;
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

    /** A global search asks no further server once it holds more than this many results. */
    public static final int GLOBAL_RESULTS = 100;

    /**
     * The results one search keeps at most, against a flood: well above what servers give, up to 300 an answer and more
     * asked for {@link #MAX_MORE_REQUESTS} times.
     */
    public static final int MAX_RESULTS = 2_000;

    /** Requests for more results of one search, as servers allow. */
    public static final int MAX_MORE_REQUESTS = 5;

    /** A Kad search ends after this long. */
    public static final Duration KAD_WAIT = Duration.ofSeconds(50);

    /** Rates results for spam, and keeps how many spam results each server gives over UDP. */
    public interface SpamRater {
        SpamRater NONE = new SpamRater() {
            @Override
            public int rate(SearchResult result) {
                return 0;
            }

            @Override
            public void answered(int serverIp, int results, int spamResults) {
                // Nothing kept.
            }
        };

        int rate(SearchResult result);

        /** The server at {@code serverIp} ({@code Ipv4} form) gave {@code results} over UDP, some rated as spam. */
        void answered(int serverIp, int results, int spamResults);
    }

    /** Kad, when it runs. */
    public interface KadSearches {
        /**
         * Looks for files under {@code keyword}, filtered by {@code expression}; the handle ends this search and no
         * other. Empty when Kad cannot search for it now.
         */
        Optional<Runnable> search(String keyword, SearchExpr expression, Consumer<List<SearchResult>> results);
    }

    private static final Logger logger = LoggerFactory.getLogger(SearchManager.class);
    // What separates the words of a name, as a bare name leaves out the words searched for.
    private static final CharMatcher NAME_SEPARATORS =
            CharMatcher.anyOf(".[]()!-'_").or(CharMatcher.whitespace());
    private static final Splitter WORDS = Splitter.on(NAME_SEPARATORS).omitEmptyStrings();
    // Control characters, and the embeddings, overrides and isolates that reorder text.
    private static final CharMatcher UNSHOWN = CharMatcher.javaIsoControl()
            .or(CharMatcher.inRange((char) 0x202A, (char) 0x202E))
            .or(CharMatcher.inRange((char) 0x2066, (char) 0x2069));

    private final EngineLoop loop;
    private final ServerSession session;
    private final ServerList servers;
    private final Supplier<Optional<ServerUdp>> udp;
    private final Supplier<Optional<KadSearches>> kad;
    private final Listener listener;
    private final Function<ServerMet.Server, Optional<ServerRoute>> route;
    private final SecureRandom random;
    private SpamRater spam = SpamRater.NONE;
    // Searches still running.
    private final Map<SearchId, Search> searches = new HashMap<>();
    private long nextId = 1;
    // Searches the connected server has yet to answer, in the order we asked: its answers carry no
    // search id, so a late answer must not land in the search asked after it.
    private final Deque<Search> serverQueue = new ArrayDeque<>();
    // The last search sent to the server, the only one whose results it can continue.
    private Search serverSearch;

    public SearchManager(
            EngineLoop loop,
            ServerSession session,
            ServerList servers,
            Supplier<Optional<ServerUdp>> udp,
            Supplier<Optional<KadSearches>> kad,
            Listener listener) {
        this(
                loop,
                session,
                servers,
                udp,
                kad,
                listener,
                s -> ServerRoute.of(s, 0, ObfuscationMode.DISABLED),
                new SecureRandom());
    }

    /**
     * As above, with {@code route} telling how a server is asked over UDP (empty: not at all, as when obfuscation is
     * required and the server cannot take it).
     */
    public SearchManager(
            EngineLoop loop,
            ServerSession session,
            ServerList servers,
            Supplier<Optional<ServerUdp>> udp,
            Supplier<Optional<KadSearches>> kad,
            Listener listener,
            Function<ServerMet.Server, Optional<ServerRoute>> route,
            SecureRandom random) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.session = Objects.requireNonNull(session, "session");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.udp = Objects.requireNonNull(udp, "udp");
        this.kad = Objects.requireNonNull(kad, "kad");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.route = Objects.requireNonNull(route, "route");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Results are rated for spam by {@code rater} from now on. */
    public void rateSpamWith(SpamRater rater) {
        this.spam = Objects.requireNonNull(rater, "rater");
    }

    /**
     * Starts a search on the connected server; the server and global searches still open end, as server answers carry
     * no search id to tell them apart.
     *
     * @throws SearchSyntaxException for an invalid expression
     * @throws IllegalStateException when no server is connected
     */
    public SearchId start(SearchRequest request) {
        SearchExpr expression = SearchParser.parse(request.expression());
        if (request.scope() == SearchRequest.Scope.KAD) {
            return startKad(request, expression);
        }
        ServerMessages.IdChange id =
                this.session.id().orElseThrow(() -> new IllegalStateException("not connected to a server"));
        for (Search open : List.copyOf(this.searches.values())) {
            if (open.scope != SearchRequest.Scope.KAD) {
                end(open);
            }
        }
        Search search = new Search(new SearchId(this.nextId++), expression, request.scope());
        this.searches.put(search.id, search);
        this.serverSearch = search;
        this.session.send(ServerMessages.searchRequest(expression, id.has(ServerMessages.TCP_LARGEFILES)));
        waitForServer(search);
        return search.id;
    }

    // The longest word every result must have is the keyword looked up, as short common words land on
    // overloaded nodes; the whole expression filters.
    private SearchId startKad(SearchRequest request, SearchExpr expression) {
        KadSearches searches = this.kad.get().orElseThrow(() -> new IllegalStateException("Kad is not running"));
        String keyword = kadKeyword(expression);
        Search search = new Search(new SearchId(this.nextId++), expression, request.scope());
        Optional<Runnable> started = searches.search(keyword, SearchTreeCodec.indexedTypes(expression), results -> {
            if (!search.ended) {
                SearchResult.Origin kad = new SearchResult.Origin(Set.of(), true, Set.of(), "");
                mergeResults(
                        search, results.stream().map(r -> r.withOrigin(kad)).toList(), false);
            }
        });
        if (started.isEmpty()) {
            throw new IllegalStateException("Kad cannot search for \"" + keyword + "\" now");
        }
        search.kadStop = started.get();
        this.searches.put(search.id, search);
        search.serverTimer = this.loop.schedule("Kad search", KAD_WAIT, () -> end(search));
        return search.id;
    }

    /**
     * The longest word, as Kad indexes words, that every match of {@code expression} has: joined by AND or on the left
     * of NOT, preferably outside quotes.
     *
     * @throws IllegalArgumentException when there is none
     */
    static String kadKeyword(SearchExpr expression) {
        List<String> plain = new ArrayList<>();
        List<String> quoted = new ArrayList<>();
        boolean alternatives = requiredWords(expression, plain, quoted);
        List<String> words = plain.isEmpty() ? quoted : plain;
        if (words.isEmpty()) {
            throw new IllegalArgumentException(
                    alternatives
                            ? "Kad cannot search for words joined by OR: search for each on its own"
                            : "Kad needs a word of at least three letters to search for");
        }
        String best = words.getFirst();
        for (String word : words) {
            if (word.codePointCount(0, word.length()) > best.codePointCount(0, best.length())) {
                best = word;
            }
        }
        return best;
    }

    // Archives and disc images every match must be: asked for as programs, the others are left out here.
    private static Optional<String> requiredKind(SearchExpr expression) {
        return switch (expression) {
            case SearchExpr.And and -> requiredKind(and.left()).or(() -> requiredKind(and.right()));
            case SearchExpr.Not not -> requiredKind(not.left());
            case SearchExpr.StringAttr attr
            when attr.tag() == FileTag.FILETYPE
                    && (attr.value().equals(FileTypes.ARCHIVE) || attr.value().equals(FileTypes.DISC_IMAGE)) ->
                Optional.of(attr.value());
            default -> Optional.empty();
        };
    }

    // Adds the words every match has; true when alternatives (OR) were left out.
    private static boolean requiredWords(SearchExpr expression, List<String> plain, List<String> quoted) {
        return switch (expression) {
            case SearchExpr.And and -> {
                boolean left = requiredWords(and.left(), plain, quoted);
                boolean right = requiredWords(and.right(), plain, quoted);
                yield left || right;
            }
            case SearchExpr.Not not -> requiredWords(not.left(), plain, quoted);
            case SearchExpr.Or or -> true;
            case SearchExpr.Keyword keyword -> {
                (keyword.text().startsWith("\"") ? quoted : plain).addAll(Keywords.of(keyword.text()));
                yield false;
            }
            case SearchExpr.StringAttr attr -> false;
            case SearchExpr.NumericAttr attr -> false;
        };
    }

    /**
     * Asks the server for more results; {@code false} if it has none, the limit is reached or the server has taken a
     * newer search since, whose results it would continue.
     */
    public boolean more(SearchId id) {
        Search search = this.searches.get(id);
        if (search == null
                || search != this.serverSearch
                || search.ended
                || !search.moreAvailable
                || search.moreRequests >= MAX_MORE_REQUESTS
                || !this.session.send(ServerMessages.queryMoreResults())) {
            return false;
        }
        search.moreRequests++;
        search.moreAvailable = false;
        waitForServer(search);
        return true;
    }

    public void stop(SearchId id) {
        Search search = this.searches.get(id);
        if (search != null && !search.ended) {
            end(search);
        }
    }

    /** Results from the connected server, for the oldest search it has yet to answer. */
    public void serverResults(ServerMet.Server server, ServerMessages.SearchResults results) {
        Search search = this.serverQueue.pollFirst();
        if (search == null) {
            return;
        }
        search.serverTimer.cancel(false);
        if (search.ended) {
            logger.debug("Dropping the server's answer to an ended search");
            return;
        }
        search.moreAvailable = results.more();
        merge(search, results.files(), OptionalInt.empty());
        afterServer(search);
    }

    /** A UDP answer from {@code from}; ignored unless an open global search asked that server. */
    public void datagramResults(InetSocketAddress from, List<FileEntry> files) {
        int ip = Ipv4.fromBytes(from.getAddress().getAddress());
        for (Search search : this.searches.values()) {
            if (!search.ended && search.askedOverUdp.contains(ip)) {
                List<SearchResult> rated = merge(search, files, OptionalInt.of(ip));
                int spamResults =
                        (int) rated.stream().filter(SearchResult::isSpam).count();
                this.spam.answered(ip, rated.size(), spamResults);
                return;
            }
        }
        logger.debug("Unrequested search results from {}", from);
    }

    /** Ends the server and global searches when the server connection is lost; Kad searches go on. */
    public void serverLost() {
        this.serverQueue.clear();
        for (Search search : List.copyOf(this.searches.values())) {
            if (search.scope != SearchRequest.Scope.KAD) {
                end(search);
            }
        }
    }

    private void waitForServer(Search search) {
        this.serverQueue.addLast(search);
        search.serverTimer = this.loop.schedule("search server wait", SERVER_WAIT, () -> serverSilent(search));
    }

    // A server that does not answer in time is taken not to answer at all.
    private void serverSilent(Search search) {
        if (this.serverQueue.remove(search) && !search.ended) {
            afterServer(search);
        }
    }

    private void afterServer(Search search) {
        if (search.scope == SearchRequest.Scope.GLOBAL && !search.globalStarted) {
            startGlobal(search);
        } else if (search.scope == SearchRequest.Scope.SERVER || search.udpQueue.isEmpty()) {
            if (!search.moreAvailable && search.udpTimer == null) {
                end(search);
            }
        }
    }

    private void startGlobal(Search search) {
        search.globalStarted = true;
        Optional<ServerList.Address> connected = this.session.server().map(ServerList::addressOf);
        for (ServerMet.Server server : this.servers.candidates()) {
            // A server listens for UDP on its TCP port + 4, which does not exist above 65531.
            boolean hasUdpPort = server.port() + ServerUdp.PORT_OFFSET <= 0xFFFF;
            if (hasUdpPort && (connected.isEmpty() || !connected.get().equals(ServerList.addressOf(server)))) {
                search.udpQueue.add(server);
            }
        }
        if (this.udp.get().isEmpty() || search.udpQueue.isEmpty()) {
            end(search);
            return;
        }
        search.udpTimer =
                this.loop.scheduleRepeating("global search", Duration.ZERO, GLOBAL_INTERVAL, () -> queryNext(search));
    }

    private void queryNext(Search search) {
        if (search.ended) {
            return;
        }
        Optional<ServerUdp> socket = this.udp.get();
        if (search.udpQueue.isEmpty() || search.results.size() > GLOBAL_RESULTS || socket.isEmpty()) {
            search.udpTimer.cancel(false);
            this.loop.schedule("global search answers", ANSWER_GRACE, () -> end(search));
            return;
        }
        ServerMet.Server server = search.udpQueue.poll();
        Optional<Packet> request = ServerDatagrams.searchRequest(
                search.expression, (int) server.udpFlags().orElse(0));
        if (request.isEmpty()) {
            return;
        }
        Optional<ServerRoute> route = this.route.apply(server);
        if (route.isEmpty()) {
            return;
        }
        try {
            socket.get().send(route.get(), request.get(), this.random);
            search.askedOverUdp.add(server.ip());
        } catch (IOException e) {
            logger.debug("Cannot query {}", route.get().target(), e);
        }
    }

    // Answers of the connected server, or of the server at udpServer over UDP; the results they made, rated.
    private List<SearchResult> merge(Search search, List<FileEntry> files, OptionalInt udpServer) {
        List<SearchResult> found = new ArrayList<>();
        for (FileEntry file : files) {
            Optional<String> name = file.name();
            if (name.isEmpty() || file.size().isEmpty()) {
                continue;
            }
            // A HighID source the answer names; a LowID is no address.
            Set<Integer> source = file.clientId() >= ServerMessages.LOW_ID_LIMIT && file.port() != 0
                    ? Set.of(Ipv4.fromEd2k(file.clientId()))
                    : Set.of();
            SearchResult.Origin origin = new SearchResult.Origin(
                    udpServer.isPresent() ? Set.of(udpServer.getAsInt()) : Set.of(), udpServer.isEmpty(), source, "");
            found.add(new SearchResult(
                    file.hash(),
                    name.get(),
                    file.size().getAsLong(),
                    file.sources(),
                    file.completeSources(),
                    file.type(),
                    file.media(),
                    origin,
                    0,
                    file.aichHash()));
        }
        return mergeResults(search, found, true);
    }

    /**
     * Adds results to a search, rated for spam once merged; returns them as merged. Unusable ones (no name, a size of
     * zero or beyond eD2K's limit, a known hash with another size) are left out. Servers count their own sources, so
     * their counts add up; Kad nodes report the same sources, so the largest count is kept.
     */
    private List<SearchResult> mergeResults(Search search, List<SearchResult> found, boolean addCounts) {
        List<SearchResult> changed = new ArrayList<>();
        List<SearchResult> rated = new ArrayList<>();
        for (SearchResult reported : found) {
            SearchResult shown = asShown(reported);
            SearchResult file = shown.withOrigin(new SearchResult.Origin(
                    shown.origin().udpServers(),
                    shown.origin().direct(),
                    shown.origin().sourceIps(),
                    bareName(shown.name(), search.words)));
            if (!SearchResult.isDownloadable(file.name(), file.size())) {
                continue;
            }
            if (search.kind.isPresent() && !FileTypes.isA(search.kind.get(), file.name())) {
                continue;
            }
            if (!search.results.containsKey(file.hash()) && search.results.size() >= MAX_RESULTS) {
                continue;
            }
            SearchResult known = search.results.get(file.hash());
            if (known != null && known.size() != file.size()) {
                continue;
            }
            SearchResult merged = file;
            if (known != null) {
                SearchResult counted = ResultNames.foundAgain(known, file, addCounts);
                merged = new SearchResult(
                        counted.hash(),
                        counted.name(),
                        counted.size(),
                        counted.sources(),
                        counted.completeSources(),
                        counted.type(),
                        known.media().or(file.media()),
                        known.origin().and(file.origin()),
                        0,
                        agreedAich(search, known, file),
                        counted.names());
            }
            merged = merged.withSpamRating(this.spam.rate(merged));
            rated.add(merged);
            if (!merged.equals(known)) {
                search.results.put(file.hash(), merged);
                changed.add(merged);
            }
        }
        if (!changed.isEmpty()) {
            this.listener.results(search.id, changed);
        }
        return rated;
    }

    /** {@code name} in lower case without the words searched for, its other words apart by single spaces. */
    static String bareName(String name, Set<String> searched) {
        List<String> kept = new ArrayList<>();
        for (String word : WORDS.split(name.toLowerCase(Locale.ROOT))) {
            if (!searched.contains(word)) {
                kept.add(word);
            }
        }
        return String.join(" ", kept);
    }

    // The words of the expression's keywords, as names are split, in lower case.
    private static Set<String> searchedWords(SearchExpr expression) {
        Set<String> words = new HashSet<>();
        collectWords(expression, words);
        return Set.copyOf(words);
    }

    private static void collectWords(SearchExpr expression, Set<String> words) {
        switch (expression) {
            case SearchExpr.And and -> {
                collectWords(and.left(), words);
                collectWords(and.right(), words);
            }
            case SearchExpr.Or or -> {
                collectWords(or.left(), words);
                collectWords(or.right(), words);
            }
            case SearchExpr.Not not -> collectWords(not.left(), words);
            case SearchExpr.Keyword keyword ->
                WORDS.split(keyword.text().replace("\"", "").toLowerCase(Locale.ROOT))
                        .forEach(words::add);
            case SearchExpr.StringAttr attr -> {
                // Attributes are no words of a name.
            }
            case SearchExpr.NumericAttr attr -> {
                // Nor are numbers.
            }
        }
    }

    // Names are shown and printed: no terminal escapes, line breaks or direction overrides that would hide the real
    // extension. The publisher's type may lie (a program shown as a video) or be no type at all: the name's extension
    // tells first, and a reported type counts only when it is a real one.
    private static SearchResult asShown(SearchResult file) {
        String name = UNSHOWN.removeFrom(file.name()).strip();
        Optional<String> type = FileTypes.of(name).or(() -> file.type().filter(FileTypes::isKnown));
        return name.equals(file.name()) && type.equals(file.type())
                ? file
                : new SearchResult(
                        file.hash(),
                        name,
                        file.size(),
                        file.sources(),
                        file.completeSources(),
                        type,
                        file.media(),
                        file.origin(),
                        file.spamRating(),
                        file.aichHash());
    }

    // Publishers that disagree on a file's AICH root leave it none for the rest of the search: a wrong one would keep
    // its download from completing.
    private static Optional<Hash20> agreedAich(Search search, SearchResult known, SearchResult file) {
        if (search.aichDisputed.contains(file.hash())) {
            return Optional.empty();
        }
        if (known.aichHash().isPresent()
                && file.aichHash().isPresent()
                && !known.aichHash().equals(file.aichHash())) {
            search.aichDisputed.add(file.hash());
            return Optional.empty();
        }
        return known.aichHash().or(file::aichHash);
    }

    private void end(Search search) {
        if (search.ended) {
            return;
        }
        search.ended = true;
        this.searches.remove(search.id);
        // The server's answer may still come, and its wait keeps the order of the answers.
        if (search.serverTimer != null && !this.serverQueue.contains(search)) {
            search.serverTimer.cancel(false);
        }
        if (search.udpTimer != null) {
            search.udpTimer.cancel(false);
        }
        if (search.kadStop != null) {
            search.kadStop.run();
        }
        this.listener.ended(search.id, search.results.size());
    }

    private static final class Search {
        final SearchId id;
        final SearchExpr expression;
        final SearchRequest.Scope scope;
        // An archive or disc image search, which the networks answer with every program.
        final Optional<String> kind;
        final Map<Hash16, SearchResult> results = new LinkedHashMap<>();
        // Files given two AICH roots, which keep none.
        final Set<Hash16> aichDisputed = new HashSet<>();
        // The words searched for, which bare names leave out.
        final Set<String> words;
        final Deque<ServerMet.Server> udpQueue = new ArrayDeque<>();
        final Set<Integer> askedOverUdp = new HashSet<>();
        ScheduledFuture<?> serverTimer;
        ScheduledFuture<?> udpTimer;
        // Ends this search's Kad lookup, not a newer one for the same keyword.
        Runnable kadStop;
        boolean globalStarted;
        boolean moreAvailable;
        int moreRequests;
        boolean ended;

        Search(SearchId id, SearchExpr expression, SearchRequest.Scope scope) {
            this.id = id;
            this.expression = expression;
            this.scope = scope;
            this.kind = requiredKind(expression);
            this.words = searchedWords(expression);
        }
    }
}
