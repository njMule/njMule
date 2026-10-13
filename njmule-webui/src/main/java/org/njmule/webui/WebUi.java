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
package org.njmule.webui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web interface: status, transfers, searches and servers as plain HTML pages behind a password. Every change is a
 * POST carrying the session's form token; pages run no scripts and cannot be framed, and the session cookie is
 * HTTP-only and same-site.
 */
public final class WebUi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebUi.class);
    private static final String COOKIE = "njmule-session";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String TRANSFERS = "/transfers";
    private static final String SEARCH = "/search";
    private static final String SERVERS = "/servers";
    private static final String ACTION = "action";
    private static final int MAX_BODY = 16 * 1024;
    private static final int SEARCHES_KEPT = 10;
    private static final int RESULTS_KEPT = 1000;
    private static final long ENGINE_WAIT_SECONDS = 10;

    // Pasted links are separated by white space, which file names may contain too.
    private static final Pattern LINKS = Pattern.compile("\\s+(?=ed2k://)", Pattern.CASE_INSENSITIVE);

    // Names under which a browser on this computer reaches a loopback-only interface; any other
    // Host header is a DNS-rebinding attempt.
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "[::1]");

    private final NjMuleEngine engine;
    private final WebUiSettings settings;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Sessions sessions = new Sessions(new SecureRandom(), System::nanoTime);

    // Results of the latest searches, by search id, oldest first.
    private final LinkedHashMap<Long, Map<Hash16, SearchResult>> results = new LinkedHashMap<>();
    private volatile Flow.Subscription subscription;

    private WebUi(NjMuleEngine engine, WebUiSettings settings, HttpServer server) {
        this.engine = engine;
        this.settings = settings;
        this.server = server;
    }

    /** Starts the interface; it must be enabled and have a password. */
    public static WebUi start(NjMuleEngine engine, WebUiSettings settings) throws IOException {
        Objects.requireNonNull(engine, "engine");
        if (!settings.usable()) {
            throw new IllegalArgumentException("the web interface is off or has no password");
        }
        InetSocketAddress bind = settings.lan()
                ? new InetSocketAddress(settings.port())
                : new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.port());
        HttpServer server = HttpServer.create(bind, 0);
        WebUi ui = new WebUi(engine, settings, server);
        server.setExecutor(ui.executor);
        server.createContext("/", ui::handle);
        engine.events().subscribe(ui.new Results());
        server.start();
        logger.info("Web interface on {}", server.getAddress());
        return ui;
    }

    public int port() {
        return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
        Flow.Subscription s = this.subscription;
        if (s != null) {
            s.cancel();
        }
        this.server.stop(0);
        this.executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            this.route(exchange);
        } catch (IllegalArgumentException e) {
            if (exchange.getResponseCode() < 0) {
                this.send(exchange, 400, TEXT, "Bad request.");
            }
        } catch (RuntimeException e) {
            logger.warn("Web interface request failed", e);
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (!this.trustedHost(exchange.getRequestHeaders())) {
            this.send(exchange, 421, TEXT, "Unknown host name.");
            return;
        }
        if (method.equals(POST) && !sameOrigin(exchange.getRequestHeaders())) {
            this.send(exchange, 403, TEXT, "Cross-site request refused.");
            return;
        }
        if (path.equals("/style.css") && method.equals(GET)) {
            this.send(exchange, 200, "text/css; charset=utf-8", Html.STYLE);
            return;
        }
        if (path.equals("/login")) {
            this.login(exchange, method);
            return;
        }
        Optional<Sessions.Session> session = this.session(exchange);
        if (session.isEmpty()) {
            if (method.equals(GET)) {
                this.redirect(exchange, "/login");
            } else {
                this.send(exchange, 403, TEXT, "Log in first.");
            }
            return;
        }
        switch (method) {
            case POST -> this.post(exchange, path, session.get());
            case GET -> this.get(exchange, path, session.get());
            default -> this.send(exchange, 405, TEXT, "Method not allowed.");
        }
    }

    private boolean trustedHost(Headers headers) {
        if (this.settings.lan()) {
            return true;
        }
        String host = headers.getFirst("Host");
        if (host == null) {
            return false;
        }
        int colon = host.lastIndexOf(':');
        String name = colon > host.lastIndexOf(']') ? host.substring(0, colon) : host;
        return LOOPBACK_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    // Browsers send Origin with every POST; a different one is another site's form.
    private static boolean sameOrigin(Headers headers) {
        String origin = headers.getFirst("Origin");
        if (origin == null) {
            return true;
        }
        try {
            String authority = new URI(origin).getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(headers.getFirst("Host"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private void login(HttpExchange exchange, String method) throws IOException {
        if (method.equals(GET)) {
            this.send(exchange, 200, HTML, loginPage(""));
            return;
        }
        if (!method.equals(POST)) {
            this.send(exchange, 405, TEXT, "Method not allowed.");
            return;
        }
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!this.sessions.mayTry(address)) {
            this.send(exchange, 429, HTML, loginPage("Too many attempts: wait a minute."));
            return;
        }
        char[] password = form(exchange).getOrDefault("password", "").toCharArray();
        boolean right = Passwords.matches(password, this.settings.passwordHash());
        Passwords.wipe(password);
        if (!right) {
            this.sessions.failed(address);
            this.send(exchange, 401, HTML, loginPage("Wrong password."));
            return;
        }
        this.sessions.succeeded(address);
        Sessions.Session session = this.sessions.open();
        exchange.getResponseHeaders()
                .add("Set-Cookie", COOKIE + "=" + session.id() + "; Path=/; HttpOnly; SameSite=Strict");
        this.redirect(exchange, "/");
    }

    private static String loginPage(String error) {
        return Html.page(
                "Log in",
                "",
                (error.isEmpty() ? "" : "<p class=\"error\">" + Html.text(error) + "</p>")
                        + "<form method=\"post\" action=\"/login\">"
                        + "<input type=\"password\" name=\"password\" autofocus"
                        + " autocomplete=\"current-password\"> "
                        + "<button class=\"main\">Log in</button></form>");
    }

    private void get(HttpExchange exchange, String path, Sessions.Session session) throws IOException {
        String page = switch (path) {
            case "/" -> this.statusPage(session);
            case TRANSFERS -> this.transfersPage(session);
            case SEARCH -> this.searchPage(session, query(exchange).get("id"));
            case SERVERS -> this.serversPage(session);
            default -> null;
        };
        if (page == null) {
            this.send(exchange, 404, TEXT, "Not found.");
        } else {
            this.send(exchange, 200, HTML, page);
        }
    }

    private void post(HttpExchange exchange, String path, Sessions.Session session) throws IOException {
        Map<String, String> form = form(exchange);
        if (!Sessions.validForm(session, form.get("csrf"))) {
            this.send(exchange, 403, TEXT, "Expired form: reload the page.");
            return;
        }
        String next;
        try {
            next = switch (path) {
                case "/logout" -> {
                    this.sessions.close(session.id());
                    exchange.getResponseHeaders()
                            .add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly;" + " SameSite=Strict");
                    yield "/login";
                }
                case TRANSFERS -> {
                    this.transfer(form);
                    yield TRANSFERS;
                }
                case SEARCH -> this.search(form);
                case SERVERS -> {
                    this.server(form);
                    yield SERVERS;
                }
                default -> null;
            };
        } catch (IllegalArgumentException | EngineFailure e) {
            this.send(
                    exchange,
                    400,
                    HTML,
                    Html.page(
                            "Not done",
                            session.csrf(),
                            "<p class=\"error\">"
                                    + Html.text(String.valueOf(e.getMessage()))
                                    + "</p><p><a href=\""
                                    + path
                                    + "\">Back</a></p>"));
            return;
        }
        if (next == null) {
            this.send(exchange, 404, TEXT, "Not found.");
        } else {
            this.redirect(exchange, next);
        }
    }

    private void transfer(Map<String, String> form) {
        String action = form.getOrDefault(ACTION, "");
        if (action.equals("add")) {
            for (String one : LINKS.split(form.getOrDefault("link", "").strip(), -1)) {
                if (one.isEmpty()) {
                    continue;
                }
                if (!(Ed2kLink.tryParse(one).orElse(null) instanceof Ed2kLink.FileLink link)) {
                    throw new IllegalArgumentException("Not an eD2K file link: " + one);
                }
                this.await(this.engine.addDownload(link));
            }
            return;
        }
        Hash16 hash = Hash16.parseHex(form.getOrDefault("hash", ""));
        CompletableFuture<Boolean> done = switch (action) {
            case "pause" -> this.engine.pauseDownload(hash);
            case "resume" -> this.engine.resumeDownload(hash);
            case "stop" -> this.engine.stopDownload(hash);
            case "priority" ->
                this.engine.setDownloadPriority(
                        hash,
                        DownloadPriority.valueOf(
                                form.getOrDefault("priority", "").toUpperCase(Locale.ROOT)));
            case "cancel" -> this.engine.cancelDownload(hash);
            default -> throw new IllegalArgumentException("Unknown action.");
        };
        this.await(done);
    }

    private String search(Map<String, String> form) {
        if (form.containsKey("download")) {
            long id = Long.parseLong(form.getOrDefault("search", ""));
            Hash16 hash = Hash16.parseHex(form.get("download"));
            SearchResult result;
            synchronized (this.results) {
                result = this.results.getOrDefault(id, Map.of()).get(hash);
            }
            if (result == null) {
                throw new IllegalArgumentException("That result is gone: search again.");
            }
            this.await(this.engine.addDownload(result.link()));
            return TRANSFERS;
        }
        String expression = form.getOrDefault("query", "").strip();
        if (expression.isEmpty()) {
            return SEARCH;
        }
        SearchRequest.checkExpression(expression);
        SearchRequest.Scope scope = SearchRequest.Scope.valueOf(form.getOrDefault("scope", "SERVER"));
        SearchId id = this.await(this.engine.search(new SearchRequest(expression, scope)));
        synchronized (this.results) {
            this.remember(id.value());
        }
        return SEARCH + "?id=" + id.value();
    }

    // Called holding the results' lock.
    private Map<Hash16, SearchResult> remember(long search) {
        Map<Hash16, SearchResult> found = this.results.computeIfAbsent(search, k -> new LinkedHashMap<>());
        while (this.results.size() > SEARCHES_KEPT) {
            this.results.pollFirstEntry();
        }
        return found;
    }

    private void server(Map<String, String> form) {
        switch (form.getOrDefault(ACTION, "")) {
            case "connect" -> {
                String address = form.getOrDefault("address", "").strip();
                this.await(this.engine.connect(
                        address.isEmpty() ? Optional.empty() : Optional.of(ServerAddress.parse(address))));
            }
            case "disconnect" -> this.await(this.engine.disconnect());
            default -> throw new IllegalArgumentException("Unknown action.");
        }
    }

    private String statusPage(Sessions.Session session) {
        EngineStatus s = this.await(this.engine.status());
        String ed2k = s.server()
                .map(i -> Html.text(i.name()) + (s.lowId() ? " (LowID)" : " (HighID)"))
                .orElse("not connected");
        String kad = s.kad()
                .map(k -> !k.connected() ? "not connected" : k.firewalled() ? "firewalled" : "open")
                .orElse("off");
        String body = "<div class=\"cards\">"
                + card("eD2K", ed2k)
                + card("Kad", kad)
                + card("Download", Html.rate(s.downloadBytesPerSecond()))
                + card("Upload", Html.rate(s.uploadBytesPerSecond()))
                + card("Downloaded (session)", Html.size(s.downloaded()))
                + card("Uploaded (session)", Html.size(s.uploaded()))
                + card("Downloads", String.valueOf(s.downloads()))
                + card("Uploads / queue", s.uploads() + " / " + s.queued())
                + card("Shared files", String.valueOf(s.sharedFiles()))
                + "</div>";
        return Html.page("Status", session.csrf(), body);
    }

    private String transfersPage(Sessions.Session session) {
        List<DownloadInfo> downloads = this.await(this.engine.downloads());
        String csrf = session.csrf();
        StringBuilder body = new StringBuilder(1024 + downloads.size() * 512);
        body.append("<p>")
                .append(Html.form(
                        TRANSFERS,
                        csrf,
                        Html.hidden(ACTION, "add")
                                + "<input type=\"text\" name=\"link\" size=\"60\" required"
                                + " pattern=\"\\s*[eE][dD]2[kK]://.+\" maxlength=\"65536\""
                                + " title=\"One or more eD2K file links\""
                                + " placeholder=\"ed2k://|file|...|/\"> ",
                        "Download",
                        true))
                .append("</p>");
        if (downloads.isEmpty()) {
            return Html.page(
                    "Transfers", csrf, body.append("<p>No downloads.</p>").toString());
        }
        body.append("<table><tr><th>File</th><th>Size</th><th>Done</th><th>Speed</th>")
                .append("<th>Sources</th><th>Status</th><th>Priority</th><th></th></tr>");
        for (DownloadInfo d : downloads) {
            int percent = d.size() <= 0 ? 0 : (int) (d.completed() * 100 / d.size());
            String hash = Html.hidden("hash", d.hash().toHex());
            boolean paused = d.status() == DownloadInfo.Status.PAUSED || d.status() == DownloadInfo.Status.STOPPED;
            body.append("<tr><td>")
                    .append(Html.text(d.name()))
                    .append("</td><td>")
                    .append(Html.size(d.size()))
                    .append("</td><td><progress max=\"100\" value=\"")
                    .append(percent)
                    .append("\"></progress> ")
                    .append(percent)
                    .append(" %</td><td>")
                    .append(Html.rate(d.bytesPerSecond()))
                    .append("</td><td>")
                    .append(d.transferring())
                    .append(" / ")
                    .append(d.sources())
                    .append("</td><td>")
                    .append(d.status().name().toLowerCase(Locale.ROOT))
                    .append("</td><td>")
                    .append(Html.form(
                            TRANSFERS, csrf, Html.hidden(ACTION, "priority") + hash + priorities(d), "Set", false))
                    .append("</td><td>")
                    .append(Html.form(
                            TRANSFERS,
                            csrf,
                            Html.hidden(ACTION, paused ? "resume" : "pause") + hash,
                            paused ? "Resume" : "Pause",
                            false))
                    .append(' ');
            if (d.status() != DownloadInfo.Status.STOPPED) {
                body.append(Html.form(TRANSFERS, csrf, Html.hidden(ACTION, "stop") + hash, "Stop", false))
                        .append(' ');
            }
            body.append(Html.form(TRANSFERS, csrf, Html.hidden(ACTION, "cancel") + hash, "Cancel", false))
                    .append("</td></tr>");
        }
        return Html.page("Transfers", csrf, body.append("</table>").toString());
    }

    // The priority of a download, to change.
    private static String priorities(DownloadInfo d) {
        StringBuilder select = new StringBuilder("<select name=\"priority\">");
        for (DownloadPriority priority : DownloadPriority.values()) {
            String name = priority.name().toLowerCase(Locale.ROOT);
            select.append("<option value=\"")
                    .append(name)
                    .append(priority == d.priority() ? "\" selected>" : "\">")
                    .append(name)
                    .append("</option>");
        }
        return select.append("</select> ").toString();
    }

    private String searchPage(Sessions.Session session, String idText) {
        String csrf = session.csrf();
        StringBuilder body = new StringBuilder(4096);
        body.append("<p>")
                .append(Html.form(
                        SEARCH,
                        csrf,
                        "<input type=\"text\" name=\"query\" size=\"40\" required maxlength=\""
                                + SearchRequest.MAX_EXPRESSION_LENGTH
                                + "\" autofocus> "
                                + "<select name=\"scope\">"
                                + "<option value=\"SERVER\">Server</option>"
                                + "<option value=\"GLOBAL\">Global</option>"
                                + "<option value=\"KAD\">Kad</option></select> ",
                        "Search",
                        true))
                .append("</p>");
        Long id = parseId(idText);
        List<SearchResult> found;
        synchronized (this.results) {
            Map<Hash16, SearchResult> all = id == null ? null : this.results.get(id);
            found = all == null ? null : new ArrayList<>(all.values());
        }
        if (found == null) {
            return Html.page("Search", csrf, body.toString());
        }
        found.sort((a, b) -> Integer.compare(b.sources(), a.sources()));
        body.append("<p>")
                .append(found.size())
                .append(" results; <a href=\"/search?id=")
                .append(id)
                .append("\">reload</a> for more.</p>")
                .append("<table><tr><th>File</th><th>Size</th><th>Sources</th><th></th></tr>");
        for (SearchResult r : found) {
            body.append("<tr><td>")
                    .append(Html.text(r.name()))
                    .append("</td><td>")
                    .append(Html.size(r.size()))
                    .append("</td><td>")
                    .append(r.sources())
                    .append("</td><td>")
                    .append(Html.form(
                            SEARCH,
                            csrf,
                            Html.hidden("search", String.valueOf(id))
                                    + Html.hidden("download", r.hash().toHex()),
                            "Download",
                            false))
                    .append("</td></tr>");
        }
        return Html.page("Search", csrf, body.append("</table>").toString());
    }

    private static Long parseId(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String serversPage(Sessions.Session session) {
        List<ServerInfo> servers = this.await(this.engine.servers());
        String csrf = session.csrf();
        StringBuilder body = new StringBuilder(1024 + servers.size() * 256);
        body.append("<p>")
                .append(Html.form(SERVERS, csrf, Html.hidden(ACTION, "connect"), "Connect", true))
                .append(' ')
                .append(Html.form(SERVERS, csrf, Html.hidden(ACTION, "disconnect"), "Disconnect", false))
                .append("</p><table><tr><th>Name</th><th>Address</th><th>Users</th>")
                .append("<th>Files</th><th></th></tr>");
        for (ServerInfo s : servers) {
            body.append("<tr><td>")
                    .append(Html.text(s.name()))
                    .append("</td><td>")
                    .append(Html.text(s.address()))
                    .append("</td><td>")
                    .append(s.users())
                    .append("</td><td>")
                    .append(s.files())
                    .append("</td><td>")
                    .append(Html.form(
                            SERVERS,
                            csrf,
                            Html.hidden(ACTION, "connect") + Html.hidden("address", s.address()),
                            "Connect",
                            false))
                    .append("</td></tr>");
        }
        return Html.page("Servers", csrf, body.append("</table>").toString());
    }

    private static String card(String name, String value) {
        return "<div class=\"card\">" + Html.text(name) + "<b>" + value + "</b></div>";
    }

    private Optional<Sessions.Session> session(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String cookie : header.split(";", -1)) {
                String[] pair = cookie.strip().split("=", 2);
                if (pair.length == 2 && pair[0].equals(COOKIE)) {
                    return this.sessions.find(pair[1]);
                }
            }
        }
        return Optional.empty();
    }

    private <T> T await(CompletableFuture<T> work) {
        try {
            return work.get(ENGINE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineFailure("Interrupted.");
        } catch (ExecutionException e) {
            throw new EngineFailure(String.valueOf(e.getCause().getMessage()));
        } catch (TimeoutException e) {
            throw new EngineFailure("The engine did not answer in time.");
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                throw new IllegalArgumentException("form too large");
            }
            return decode(new String(body, StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        return raw == null ? Map.of() : decode(raw);
    }

    private static Map<String, String> decode(String encoded) {
        Map<String, String> values = new HashMap<>();
        for (String pair : encoded.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        securityHeaders(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(303, -1);
    }

    private void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        securityHeaders(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void securityHeaders(Headers headers) {
        headers.set(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'self'; form-action 'self'; frame-ancestors 'none';"
                        + " base-uri 'none'");
        headers.set("X-Frame-Options", "DENY");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
    }

    /** The engine could not do what a request asked; the message is meant for the user. */
    private static final class EngineFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EngineFailure(String message) {
            super(message);
        }
    }

    /** Collects search results from the engine's events. */
    private final class Results implements Flow.Subscriber<EngineEvent> {
        @Override
        public void onSubscribe(Flow.Subscription s) {
            WebUi.this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(EngineEvent event) {
            if (!(event instanceof EngineEvent.SearchResults r)) {
                return;
            }
            // Results may arrive before the request that started the search notes its id.
            synchronized (WebUi.this.results) {
                Map<Hash16, SearchResult> found = WebUi.this.remember(r.search().value());
                for (SearchResult result : r.results()) {
                    if (found.size() < RESULTS_KEPT || found.containsKey(result.hash())) {
                        found.put(result.hash(), result);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            logger.debug("Engine events ended", throwable);
        }

        @Override
        public void onComplete() {
            // The engine stopped; the next request reports it.
        }
    }
}
