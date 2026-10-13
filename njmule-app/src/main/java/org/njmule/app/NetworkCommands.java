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
package org.njmule.app;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.njmule.app.LaunchOptions.NetworkTasks;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PeerInfo;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;

/**
 * The network work asked on the command line: add servers, connect, search, say hello, download.
 */
final class NetworkCommands implements Flow.Subscriber<EngineEvent> {

    /** Longest wait for a login, several servers included. */
    static final Duration CONNECT_WAIT = Duration.ofMinutes(3);

    /** Longest wait for a search to end. */
    static final Duration SEARCH_WAIT = Duration.ofSeconds(90);

    /** A search that went quiet this long after its results is over. */
    static final Duration SEARCH_QUIET = Duration.ofSeconds(3);

    /** How long a greeted peer keeps seeing us connected. */
    static final Duration HELLO_HOLD = Duration.ofSeconds(30);

    /** Downloads print their progress this often. */
    static final Duration PROGRESS_EVERY = Duration.ofSeconds(5);

    private final NjMuleEngine engine;
    private final PrintStream out;
    private final PrintStream err;
    private final BlockingQueue<EngineEvent> events = new LinkedBlockingQueue<>();

    NetworkCommands(NjMuleEngine engine, PrintStream out, PrintStream err) {
        this.engine = engine;
        this.out = out;
        this.err = err;
        engine.events().subscribe(this);
    }

    /**
     * Adds servers and starts the connection; with a search or a hello also runs them and waits.
     *
     * @return the exit code
     */
    int run(NetworkTasks tasks) throws InterruptedException {
        try {
            if (tasks.serverMet().isPresent()) {
                int added = this.engine.addServers(tasks.serverMet().get()).get();
                this.out.println("Added " + added + " servers from " + tasks.serverMet().get());
            }
            if (tasks.hello().isPresent()) {
                this.hello(tasks);
            }
            if (tasks.server().isEmpty() && tasks.search().isEmpty()) {
                return tasks.downloads().isEmpty()
                        ? Main.EXIT_OK
                        : this.download(tasks.downloads());
            }
            this.engine.connect(tasks.server()).get();
            if (!tasks.isOneShot()) {
                return Main.EXIT_OK;
            }
            if (!this.awaitLogin()) {
                return Main.EXIT_FAILURE;
            }
            if (tasks.search().isPresent()) {
                this.search(tasks.search().get(), tasks.global());
            }
            return tasks.downloads().isEmpty() ? Main.EXIT_OK : this.download(tasks.downloads());
        } catch (ExecutionException e) {
            this.err.println("Failed: " + message(e.getCause()));
            return Main.EXIT_FAILURE;
        }
    }

    private void hello(NetworkTasks tasks) throws InterruptedException, ExecutionException {
        PeerInfo peer;
        try {
            peer = this.engine.hello(tasks.hello().get()).get(1, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new ExecutionException(e);
        }
        this.out.println(
                "Hello answered by "
                        + peer.address()
                        + ": "
                        + peer.software()
                        + peer.nick().map(n -> ", \"" + n + "\"").orElse("")
                        + (peer.obfuscated() ? ", obfuscated" : "")
                        + ", user hash "
                        + peer.userHash());
        if (!tasks.isOneShot() || tasks.search().isEmpty()) {
            this.out.println(
                    "Keeping the connection open for "
                            + HELLO_HOLD.toSeconds()
                            + " s, so the peer lists us among its clients");
            Thread.sleep(HELLO_HOLD.toMillis());
        }
    }

    private boolean awaitLogin() throws InterruptedException {
        long deadline = System.nanoTime() + CONNECT_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            EngineEvent event = this.events.poll(1, TimeUnit.SECONDS);
            switch (event) {
                case EngineEvent.ServerConnecting e ->
                        this.out.println(
                                "Connecting to "
                                        + describe(e.server().name(), e.server().address()));
                case EngineEvent.ServerMessage e -> this.out.println("  > " + e.text());
                case EngineEvent.ServerConnected e -> {
                    this.out.println(
                            "Connected to "
                                    + describe(e.server().name(), e.server().address())
                                    + " with "
                                    + (e.lowId() ? "LowID " : "HighID ")
                                    + e.clientId()
                                    + (e.obfuscated() ? ", obfuscated" : ""));
                    return true;
                }
                case EngineEvent.ServerDisconnected e -> {
                    this.err.println("Not connected: " + e.reason());
                    return false;
                }
                case null, default -> {
                    // keep waiting
                }
            }
        }
        this.err.println("Not connected within " + CONNECT_WAIT.toMinutes() + " minutes");
        return false;
    }

    private void search(String expression, boolean global)
            throws InterruptedException, ExecutionException {
        SearchRequest.Scope scope =
                global ? SearchRequest.Scope.GLOBAL : SearchRequest.Scope.SERVER;
        SearchId id = this.engine.search(new SearchRequest(expression, scope)).get();
        this.out.println(
                "Searching \""
                        + expression
                        + "\""
                        + (global ? " on every known server" : " on the server"));
        Map<Hash16, SearchResult> results = new LinkedHashMap<>();
        long deadline = System.nanoTime() + SEARCH_WAIT.toNanos();
        long quietSince = Long.MAX_VALUE;
        while (System.nanoTime() < deadline) {
            EngineEvent event = this.events.poll(200, TimeUnit.MILLISECONDS);
            if (event instanceof EngineEvent.SearchResults r && r.search().equals(id)) {
                r.results().forEach(result -> results.put(result.hash(), result));
                quietSince = System.nanoTime();
            } else if (event instanceof EngineEvent.SearchEnded e && e.search().equals(id)) {
                break;
            } else if (event instanceof EngineEvent.ServerMessage m) {
                this.out.println("  > " + m.text());
            } else if (event instanceof EngineEvent.ServerDisconnected d) {
                this.err.println("Disconnected: " + d.reason());
                break;
            } else if (!global && System.nanoTime() - quietSince > SEARCH_QUIET.toNanos()) {
                break;
            }
        }
        this.engine.stopSearch(id).get();
        List<SearchResult> sorted = new ArrayList<>(results.values());
        sorted.sort(Comparator.comparingInt(SearchResult::sources).reversed());
        for (SearchResult result : sorted) {
            this.out.printf(
                    Locale.ROOT,
                    "%7d  %10s  %s%n",
                    result.sources(),
                    size(result.size()),
                    result.name());
            this.out.println("                     " + result.link().toUri());
        }
        this.out.println(sorted.size() + " results");
    }

    // Runs until every download is finished or has failed.
    private int download(List<Ed2kLink.FileLink> links)
            throws InterruptedException, ExecutionException {
        Map<Hash16, DownloadInfo> running = new LinkedHashMap<>();
        for (Ed2kLink.FileLink link : links) {
            DownloadInfo info = this.engine.addDownload(link).get();
            running.put(info.hash(), info);
            this.out.println(
                    "Downloading "
                            + info.name()
                            + " ("
                            + size(info.size())
                            + ", "
                            + info.sources()
                            + (info.sources() == 1 ? " source)" : " sources)"));
        }
        Map<Hash16, DownloadInfo> latest = new HashMap<>(running);
        boolean failed = false;
        long nextReport = System.nanoTime() + PROGRESS_EVERY.toNanos();
        while (!running.isEmpty()) {
            EngineEvent event = this.events.poll(500, TimeUnit.MILLISECONDS);
            switch (event) {
                case EngineEvent.DownloadProgress p when running.containsKey(p.download().hash()) ->
                        latest.put(p.download().hash(), p.download());
                case EngineEvent.DownloadCompleted c
                        when running.containsKey(c.download().hash()) -> {
                    running.remove(c.download().hash());
                    this.out.println("Finished " + c.download().name() + ": " + c.file());
                }
                case EngineEvent.DownloadFailed f when running.containsKey(f.download().hash()) -> {
                    running.remove(f.download().hash());
                    failed = true;
                    this.err.println("Failed " + f.download().name() + ": " + f.reason());
                }
                case EngineEvent.ServerConnected e ->
                        this.out.println("Connected to " + e.server().address());
                case EngineEvent.ServerDisconnected e ->
                        this.err.println("Disconnected from the server: " + e.reason());
                case null, default -> {
                    // keep waiting
                }
            }
            if (System.nanoTime() - nextReport >= 0) {
                nextReport = System.nanoTime() + PROGRESS_EVERY.toNanos();
                for (Hash16 hash : running.keySet()) {
                    this.out.println(progress(latest.get(hash)));
                }
            }
        }
        return failed ? Main.EXIT_FAILURE : Main.EXIT_OK;
    }

    static String progress(DownloadInfo info) {
        double percent = info.size() == 0 ? 100 : 100.0 * info.completed() / info.size();
        return String.format(
                Locale.ROOT,
                "%6.1f%%  %12s/s  %d/%d sources  %s",
                percent,
                size(info.bytesPerSecond()),
                info.transferring(),
                info.sources(),
                info.name());
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(EngineEvent item) {
        this.events.add(item);
    }

    @Override
    public void onError(Throwable throwable) {}

    @Override
    public void onComplete() {}

    private static String describe(String name, String address) {
        return name.equals(address) ? address : name + " (" + address + ")";
    }

    static String size(long bytes) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private static String message(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
