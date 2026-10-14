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

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.njmule.webui.ApiClient;

/**
 * The commands of {@code njmule --remote}: they act on a running njMule through its remote-control API and print what
 * it answers.
 */
final class RemoteControl {

    static final String COMMANDS = """
            status                          connection, speeds and counts
            downloads                       the downloads
            add <ed2k link>...              download the files of these links
            pause|resume|stop|cancel <hash>...
            priority <hash> <low|normal|high|auto>
            uploads                         clients being sent to or waiting
            servers                         the known servers
            connect [<host:port>]           connect to this server, or any
            disconnect                      leave the server
            kad [connect|disconnect|recheck]
            search [server|global|kad] <expression>
                                            search, wait a moment and print the results
            results <id>                    the results of a search
            get <id> <hash>                 download a result of a search
            limits [<upload KiB/s> <download KiB/s>]  (0: unlimited)
            log                             the latest log lines
            events [<prefix>,...]           print events as they come, until interrupted
            shutdown                        save and exit
            """;

    private static final Set<String> SCOPES = Set.of("server", "global", "kad");
    private static final Duration POLL = Duration.ofMillis(500);
    private static final int SETTLED_POLLS = 2;
    private static final String DOWNLOADS = "/downloads/";

    private final ApiClient api;
    private final PrintStream out;
    private final Duration searchWait;

    RemoteControl(ApiClient api, PrintStream out, Duration searchWait) {
        this.api = api;
        this.out = out;
        this.searchWait = searchWait;
    }

    /** Thrown for a command line the remote commands do not take; the message is meant for the user. */
    static final class Usage extends Exception {
        private static final long serialVersionUID = 1L;

        Usage(String message) {
            super(message);
        }
    }

    void run(List<String> words) throws IOException, InterruptedException, Usage {
        if (words.isEmpty()) {
            throw new Usage("Name a command.");
        }
        List<String> args = words.subList(1, words.size());
        switch (words.getFirst().toLowerCase(Locale.ROOT)) {
            case "status" -> {
                none(args);
                this.status();
            }
            case "downloads" -> {
                none(args);
                this.downloads();
            }
            case "add" -> this.add(args);
            case "pause", "resume", "stop" -> {
                String action = words.getFirst().toLowerCase(Locale.ROOT);
                this.each(args, h -> this.api.post(DOWNLOADS + encode(h) + "/" + action, Map.of()));
            }
            case "cancel" -> this.each(args, h -> this.api.delete(DOWNLOADS + encode(h)));
            case "priority" -> {
                exactly(args, 2);
                this.api.put(DOWNLOADS + encode(args.getFirst()) + "/priority", Map.of("priority", args.get(1)));
                this.out.println("Done.");
            }
            case "uploads" -> {
                none(args);
                this.uploads();
            }
            case "servers" -> {
                none(args);
                this.servers();
            }
            case "connect" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                if (args.size() > 1) {
                    throw new Usage("connect takes one server at most.");
                }
                if (!args.isEmpty()) {
                    body.put("address", args.getFirst());
                }
                this.api.post("/servers/connect", body);
                this.out.println("Connecting.");
            }
            case "disconnect" -> {
                none(args);
                this.api.post("/servers/disconnect", Map.of());
                this.out.println("Disconnected.");
            }
            case "kad" -> this.kad(args);
            case "search" -> this.search(args);
            case "results" -> {
                exactly(args, 1);
                this.results(this.api.get("/searches/" + encode(args.getFirst())));
            }
            case "get" -> {
                exactly(args, 2);
                Object d = this.api.post(
                        "/searches/" + encode(args.getFirst()) + "/download", Map.of("hash", args.get(1)));
                this.out.println("Downloading " + text(d, "name"));
            }
            case "limits" -> this.limits(args);
            case "log" -> {
                none(args);
                for (Object line : list(this.api.get("/log"))) {
                    this.out.println(text(line, "time") + "  " + text(line, "level") + "  " + text(line, "message"));
                }
            }
            case "events" -> {
                if (args.size() > 1) {
                    throw new Usage("events takes one list of prefixes, as download,search.");
                }
                this.api.events(args.isEmpty() ? "" : args.getFirst(), (name, data) -> {
                    this.out.println(name + " " + data);
                    this.out.flush();
                    return !this.out.checkError();
                });
            }
            case "shutdown" -> {
                none(args);
                this.api.post("/shutdown", Map.of());
                this.out.println("njMule is saving and exiting.");
            }
            default -> throw new Usage("Unknown command: " + words.getFirst());
        }
    }

    private void status() throws IOException, InterruptedException {
        Object s = this.api.get("/status");
        Object server = field(s, "server");
        this.out.println("eD2K:      "
                + (server == null
                        ? "not connected"
                        : text(server, "name") + " (" + text(server, "address") + ", "
                                + (Boolean.TRUE.equals(field(s, "lowId")) ? "LowID" : "HighID") + ")"));
        Object kad = field(s, "kad");
        this.out.println("Kad:       "
                + (kad == null
                        ? "off"
                        : !Boolean.TRUE.equals(field(kad, "connected"))
                                ? "not connected"
                                : Boolean.TRUE.equals(field(kad, "firewalled")) ? "firewalled" : "open"));
        this.out.println("Download:  " + NetworkCommands.size(number(s, "downloadBytesPerSecond")) + "/s, "
                + NetworkCommands.size(number(s, "downloaded")) + " this session");
        this.out.println("Upload:    " + NetworkCommands.size(number(s, "uploadBytesPerSecond")) + "/s, "
                + NetworkCommands.size(number(s, "uploaded")) + " this session");
        this.out.println("Downloads: " + number(s, "downloads") + ", uploads " + number(s, "uploads") + ", queued "
                + number(s, "queued") + ", shared files " + number(s, "sharedFiles"));
    }

    private void downloads() throws IOException, InterruptedException {
        List<?> all = list(this.api.get("/downloads"));
        if (all.isEmpty()) {
            this.out.println("No downloads.");
            return;
        }
        for (Object d : all) {
            long size = number(d, "size");
            long percent = size <= 0 ? 0 : number(d, "completed") * 100 / size;
            this.out.printf(
                    Locale.ROOT,
                    "%s  %3d%%  %10s  %10s/s  %3d/%-3d  %-11s  %s%n",
                    text(d, "hash"),
                    percent,
                    NetworkCommands.size(size),
                    NetworkCommands.size(number(d, "bytesPerSecond")),
                    number(d, "transferring"),
                    number(d, "sources"),
                    text(d, "status"),
                    text(d, "name"));
        }
    }

    private void add(List<String> links) throws IOException, InterruptedException, Usage {
        if (links.isEmpty()) {
            throw new Usage("add takes one link or more.");
        }
        for (Object d : list(this.api.post("/downloads", Map.of("links", links)))) {
            this.out.println("Added " + text(d, "name"));
        }
    }

    @FunctionalInterface
    private interface Call {
        Object on(String hash) throws IOException, InterruptedException;
    }

    private void each(List<String> hashes, Call call) throws IOException, InterruptedException, Usage {
        if (hashes.isEmpty()) {
            throw new Usage("Name the hash of one download or more.");
        }
        for (String hash : hashes) {
            Object d = call.on(hash);
            this.out.println(field(d, "name") == null ? "Done." : text(d, "name") + ": " + text(d, "status"));
        }
    }

    private void uploads() throws IOException, InterruptedException {
        List<?> all = list(this.api.get("/uploads"));
        if (all.isEmpty()) {
            this.out.println("No uploads and nobody waiting.");
            return;
        }
        for (Object u : all) {
            this.out.printf(
                    Locale.ROOT,
                    "%-9s  %-21s  %10s/s  %10s  %s%n",
                    text(u, "state"),
                    text(u, "address"),
                    NetworkCommands.size(number(u, "bytesPerSecond")),
                    NetworkCommands.size(number(u, "transferred")),
                    text(u, "file"));
        }
    }

    private void servers() throws IOException, InterruptedException {
        for (Object s : list(this.api.get("/servers"))) {
            this.out.printf(
                    Locale.ROOT,
                    "%-21s  %8d users  %10d files  %s%n",
                    text(s, "address"),
                    number(s, "users"),
                    number(s, "files"),
                    text(s, "name"));
        }
    }

    private void kad(List<String> args) throws IOException, InterruptedException, Usage {
        if (args.isEmpty()) {
            Object kad = this.api.get("/kad");
            if (kad == null) {
                this.out.println("Kad is off.");
                return;
            }
            this.out.println((Boolean.TRUE.equals(field(kad, "connected")) ? "connected" : "not connected")
                    + (Boolean.TRUE.equals(field(kad, "firewalled")) ? ", firewalled" : "")
                    + ", " + number(kad, "contacts") + " contacts");
            return;
        }
        exactly(args, 1);
        String action = args.getFirst().toLowerCase(Locale.ROOT);
        if (!Set.of("connect", "disconnect", "recheck").contains(action)) {
            throw new Usage("kad takes connect, disconnect or recheck.");
        }
        this.api.post("/kad/" + action, Map.of());
        this.out.println("Done.");
    }

    private void search(List<String> args) throws IOException, InterruptedException, Usage {
        String scope = "server";
        List<String> words = args;
        if (!words.isEmpty() && SCOPES.contains(words.getFirst().toLowerCase(Locale.ROOT))) {
            scope = words.getFirst().toLowerCase(Locale.ROOT);
            words = words.subList(1, words.size());
        }
        if (words.isEmpty()) {
            throw new Usage("search takes what to look for.");
        }
        Object started = this.api.post("/searches", Map.of("query", String.join(" ", words), "scope", scope));
        long id = number(started, "id");
        this.out.println("Search " + id + ": waiting for results...");
        this.results(this.settled("/searches/" + id));
        this.out.println("More: njmule --remote results " + id + "; download: njmule --remote get " + id + " <hash>");
    }

    // The results once they stop growing for a moment, or at the end of the wait.
    private Object settled(String search) throws IOException, InterruptedException {
        long end = System.nanoTime() + this.searchWait.toNanos();
        Object now = this.api.get(search);
        int seen = -1;
        int still = 0;
        while (System.nanoTime() < end && still < SETTLED_POLLS) {
            Thread.sleep(POLL.toMillis());
            now = this.api.get(search);
            int count = list(field(now, "results")).size();
            still = count > 0 && count == seen ? still + 1 : 0;
            seen = count;
        }
        return now;
    }

    private void results(Object search) {
        List<?> found = list(field(search, "results"));
        this.out.println(found.size() + " results");
        for (Object r : found) {
            this.out.printf(
                    Locale.ROOT,
                    "%s  %7d  %10s  %s%n",
                    text(r, "hash"),
                    number(r, "sources"),
                    NetworkCommands.size(number(r, "size")),
                    text(r, "name"));
        }
    }

    private void limits(List<String> args) throws IOException, InterruptedException, Usage {
        Object now;
        if (args.isEmpty()) {
            now = this.api.get("/limits");
        } else {
            exactly(args, 2);
            now = this.api.put(
                    "/limits", Map.of("uploadKiBps", rate(args.getFirst()), "downloadKiBps", rate(args.get(1))));
        }
        this.out.println("Upload:   " + limit(number(now, "uploadKiBps")));
        this.out.println("Download: " + limit(number(now, "downloadKiBps")));
    }

    private static String limit(long kibps) {
        return kibps == 0 ? "unlimited" : kibps + " KiB/s";
    }

    private static long rate(String text) throws Usage {
        try {
            long value = Long.parseLong(text.strip());
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new Usage("Not a rate in KiB/s: " + text);
        }
    }

    // A path segment the user typed, so that it cannot reach another call.
    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void none(List<String> args) throws Usage {
        if (!args.isEmpty()) {
            throw new Usage("Unexpected: " + String.join(" ", args));
        }
    }

    private static void exactly(List<String> args, int count) throws Usage {
        if (args.size() != count) {
            throw new Usage("Expected " + count + (count == 1 ? " argument." : " arguments."));
        }
    }

    private static Object field(Object value, String name) {
        return value instanceof Map<?, ?> fields ? fields.get(name) : null;
    }

    private static String text(Object value, String name) {
        Object field = field(value, name);
        return field == null ? "" : field.toString();
    }

    private static long number(Object value, String name) {
        return field(value, name) instanceof Number n ? n.longValue() : 0;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }
}
