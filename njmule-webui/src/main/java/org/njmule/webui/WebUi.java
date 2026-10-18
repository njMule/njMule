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

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.Ed2kLink;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.PartMap;
import org.njmule.core.api.SearchId;
import org.njmule.core.api.SearchRequest;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.api.SharedDirectory;
import org.njmule.core.api.SharedFileInfo;
import org.njmule.core.api.UploadPriority;
import org.njmule.core.remote.ApiToken;
import org.njmule.core.remote.Exchange;
import org.njmule.core.remote.RecentEvents;
import org.njmule.core.remote.RecentLog;
import org.njmule.core.remote.RemoteApi;
import org.njmule.core.remote.SearchCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web interface: status, transfers, searches and servers as plain HTML pages behind a password. Every change is a
 * POST carrying the session's form token; pages run no scripts and cannot be framed, and the session cookie is
 * HTTP-only, same-site and, over HTTPS, secure. When its settings ask for it, the remote-control API answers under
 * {@value RemoteApi#PREFIX}.
 */
public final class WebUi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebUi.class);
    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String TRANSFERS = "/transfers";
    private static final String SEARCH = "/search";
    private static final String SERVERS = "/servers";
    private static final String SHARED = "/shared";
    private static final String KAD = "/kad";
    private static final String LOG = "/log";
    private static final String SETTINGS_PAGE = "/settings";
    private static final String ACTION = "action";
    // The search scope that picks the network as the desktop app does.
    private static final String AUTOMATIC = "AUTOMATIC";
    // Seconds between the loads of a search page while its results may come.
    private static final int SEARCH_REFRESH = 2;
    private static final int MAX_LOGIN_BODY = 16 * 1024;
    private static final int MAX_LOGIN_FIELDS = 4;
    // Far more than any of the forms has.
    private static final int MAX_FIELDS = 32;

    // The longest text of the link field: percent-encoded, a character takes at most 9 bytes, so a
    // full field fits MAX_BODY.
    private static final int MAX_LINKS_LENGTH = 65_536;
    private static final int MAX_BODY = 1024 * 1024;
    private static final long ENGINE_WAIT_SECONDS = 10;

    // Each check keeps a core busy for a while: logins from many addresses cannot take them all.
    private static final int PASSWORD_CHECKS = 2;

    // Pasted links are separated by white space, which file names may contain too.
    private static final Pattern LINKS = Pattern.compile("\\s+(?=ed2k://|magnet:\\?)", Pattern.CASE_INSENSITIVE);

    // Names under which a browser on this computer reaches the interface; other computers use its
    // address or a name of the settings. Any other Host header may be a DNS-rebinding attempt.
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "[::1]");

    // Synology's QuickConnect names an address of a NAS a-b-c-d.<id>.direct.quickconnect.to, and its DNS answers with
    // that address only: such a name of the address a request came to cannot be rebound elsewhere.
    private static final Pattern QUICKCONNECT =
            Pattern.compile("(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.[a-z0-9-]+\\.direct\\.quickconnect\\.to");

    private final NjMuleEngine engine;
    private final WebUiSettings settings;
    // The names of the settings, changed by the settings page.
    private volatile List<String> hosts;
    private final TrustedProxies proxies;
    private final Server server;
    private final int port;
    private final Sessions sessions = new Sessions(new SecureRandom(), System::nanoTime);
    private final int passwordChecks;
    private final Semaphore checking;

    // Browsers keep cookies by host, whatever the port: two instances on one computer need two names.
    private final String cookie;

    private final RecentEvents recent;
    private final SearchCollector searches;
    private final RecentLog recentLog;
    private final Optional<RemoteApi> api;

    private WebUi(
            NjMuleEngine engine,
            WebUiSettings settings,
            Server server,
            int port,
            int passwordChecks,
            Optional<Runnable> exit,
            Duration heartbeat) {
        this.engine = engine;
        this.settings = settings;
        this.hosts = settings.hosts();
        this.proxies = new TrustedProxies(settings.proxies());
        this.server = server;
        this.port = port;
        this.passwordChecks = passwordChecks;
        this.checking = new Semaphore(passwordChecks);
        this.cookie = "njmule-session-" + port;
        this.recent = RecentEvents.follow(engine);
        this.searches = this.recent.searches();
        this.recentLog = this.recent.log();
        // A wrong API token counts as a failed login of its address.
        this.api = settings.api()
                ? Optional.of(new RemoteApi(
                        engine,
                        this.recent,
                        client -> this.sessions.mayTry(Sessions.clientKey(client)),
                        exit,
                        heartbeat))
                : Optional.empty();
    }

    /** Starts the interface; it must be enabled and have a password. The API cannot stop njMule. */
    public static WebUi start(NjMuleEngine engine, WebUiSettings settings) throws IOException {
        return start(engine, settings, Optional.empty(), PASSWORD_CHECKS, RemoteApi.HEARTBEAT);
    }

    /** Starts the interface, whose API may stop njMule through {@code exit}. */
    public static WebUi start(NjMuleEngine engine, WebUiSettings settings, Runnable exit) throws IOException {
        return start(engine, settings, Optional.of(exit), PASSWORD_CHECKS, RemoteApi.HEARTBEAT);
    }

    static WebUi start(NjMuleEngine engine, WebUiSettings settings, int passwordChecks) throws IOException {
        return start(engine, settings, Optional.empty(), passwordChecks, RemoteApi.HEARTBEAT);
    }

    static WebUi start(
            NjMuleEngine engine,
            WebUiSettings settings,
            Optional<Runnable> exit,
            int passwordChecks,
            Duration heartbeat)
            throws IOException {
        Objects.requireNonNull(engine, "engine");
        if (!settings.usable()) {
            throw new IllegalArgumentException("the web interface is off or has no password");
        }
        if (settings.api()) {
            // Made before the first call, so that a command line on this computer finds it.
            ApiToken.loadOrCreate(engine.directories().config(), new SecureRandom());
        }
        // Every request on a virtual thread of its own, where the pages, the API and the streams may wait.
        Server server = new Server(new VirtualThreadPool());
        HttpConfiguration http = new HttpConfiguration();
        http.setSendServerVersion(false);
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(http));
        connector.setHost(
                settings.lan() ? null : InetAddress.getLoopbackAddress().getHostAddress());
        connector.setPort(settings.port());
        server.addConnector(connector);
        ErrorHandler errors = new ErrorHandler();
        errors.setShowStacks(false);
        errors.setShowMessageInTitle(false);
        server.setErrorHandler(errors);
        // Bound first: the session cookie is named after the port.
        connector.open();
        WebUi ui = new WebUi(engine, settings, server, connector.getLocalPort(), passwordChecks, exit, heartbeat);
        Optional<RemoteApi> api = ui.api;
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                Exchange exchange = new Exchange(request, response, ui.proxies);
                try {
                    if (api.isPresent() && exchange.path().startsWith("/api/")) {
                        api.get().handle(exchange);
                    } else {
                        ui.handle(exchange);
                    }
                    exchange.finish();
                    callback.succeeded();
                } catch (IOException | RuntimeException e) {
                    callback.failed(e);
                }
                return true;
            }
        });
        try {
            server.start();
        } catch (IOException e) {
            stop(server);
            throw e;
        } catch (Exception e) {
            stop(server);
            throw new IOException("cannot start the web interface: " + e.getMessage(), e);
        }
        logger.info(
                "Web interface on {}:{}{}{}",
                settings.lan() ? "*" : InetAddress.getLoopbackAddress().getHostAddress(),
                ui.port,
                settings.hosts().isEmpty() ? "" : ", also as " + String.join(", ", settings.hosts()),
                settings.api() ? ", with the remote-control API" : "");
        return ui;
    }

    public int port() {
        return this.port;
    }

    /** Passwords being checked now. */
    int checkingPasswords() {
        return this.passwordChecks - this.checking.availablePermits();
    }

    @Override
    public void close() {
        this.recent.close();
        this.api.ifPresent(RemoteApi::close);
        stop(this.server);
    }

    private static void stop(Server server) {
        try {
            server.stop();
        } catch (Exception e) {
            logger.debug("The web interface did not stop cleanly: {}", e.toString());
        }
    }

    private void handle(Exchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (EngineFailure e) {
            fail(exchange, 503, "The engine cannot answer now: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            fail(exchange, 400, "Bad request.");
        } catch (RuntimeException e) {
            logger.warn("Web interface request failed", e);
            fail(exchange, 500, "The request failed.");
        }
    }

    // An error answer, unless another one has started.
    private void fail(Exchange exchange, int status, String message) throws IOException {
        if (!exchange.started()) {
            send(exchange, status, TEXT, message);
        }
    }

    private void route(Exchange exchange) throws IOException {
        String path = exchange.path();
        // A HEAD gets the headers of the GET; send() leaves the body out.
        String method = exchange.isHead() ? GET : exchange.method();
        List<String> names = this.hosts;
        if (!trustedHost(exchange.header("Host"), this.settings.lan(), names, exchange.localAddress())) {
            send(
                    exchange,
                    421,
                    TEXT,
                    "Unknown host name: open the interface by this computer's address and add the name under"
                            + " Settings.");
            return;
        }
        if (method.equals(POST) && !trustedOrigin(exchange.header("Origin"), exchange.header("Host"), names)) {
            send(exchange, 403, TEXT, "Cross-site request refused.");
            return;
        }
        if (path.startsWith("/api/")) {
            send(exchange, 404, TEXT, "The remote-control API is off.");
            return;
        }
        if (path.equals("/style.css") && method.equals(GET)) {
            send(exchange, 200, "text/css; charset=utf-8", Html.STYLE);
            return;
        }
        Optional<byte[]> icon = Html.icon(path);
        if (icon.isPresent() && method.equals(GET)) {
            send(exchange, 200, path.endsWith(".svg") ? "image/svg+xml" : "image/png", icon.get());
            return;
        }
        if (path.equals("/login")) {
            login(exchange, method);
            return;
        }
        Optional<Sessions.Session> session = session(exchange);
        if (session.isEmpty()) {
            if (method.equals(GET)) {
                redirect(exchange, "/login");
            } else {
                send(exchange, 403, TEXT, "Log in first.");
            }
            return;
        }
        switch (method) {
            case POST -> post(exchange, path, session.get());
            case GET -> get(exchange, path, session.get());
            default -> send(exchange, 405, TEXT, "Method not allowed.");
        }
    }

    static boolean trustedHost(String host, boolean lan, List<String> names, InetAddress reached) {
        if (host == null) {
            return false;
        }
        String name = withoutPort(host);
        return LOOPBACK_NAMES.contains(name)
                || names.contains(name)
                || (lan && (isAddress(name) || isQuickConnectNameOf(name, reached)));
    }

    private static boolean isQuickConnectNameOf(String name, InetAddress reached) {
        Matcher m = QUICKCONNECT.matcher(name);
        if (!m.matches() || !(reached instanceof Inet4Address)) {
            return false;
        }
        try {
            return Inet4Address.ofLiteral(m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4))
                    .equals(reached);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // The name of host[:port], in lower case.
    private static String withoutPort(String authority) {
        int colon = authority.lastIndexOf(':');
        return (colon > authority.lastIndexOf(']') ? authority.substring(0, colon) : authority)
                .toLowerCase(Locale.ROOT);
    }

    // An IP address literal, which no DNS answer can point elsewhere.
    private static boolean isAddress(String name) {
        try {
            if (name.startsWith("[") && name.endsWith("]")) {
                Inet6Address.ofLiteral(name.substring(1, name.length() - 1));
            } else {
                Inet4Address.ofLiteral(name);
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Browsers send Origin with every POST; a different one is another site's form. A name of the settings stands for
    // the pages on any port and scheme: a reverse proxy shows them on its own, and may pass its own address as Host.
    static boolean trustedOrigin(String origin, String host, List<String> names) {
        if (origin == null) {
            return true;
        }
        try {
            URI uri = new URI(origin);
            String authority = uri.getRawAuthority();
            if (authority == null) {
                return false;
            }
            String scheme = String.valueOf(uri.getScheme());
            return authority.equalsIgnoreCase(host)
                    || ((scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                            && authority.indexOf('@') < 0
                            && names.contains(withoutPort(authority)));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // The browser that sent the form came over HTTPS, as through a reverse proxy that ends TLS, when its Origin says
    // so: the cookie then travels only that way. No forwarded header is needed, nor trusted.
    private static boolean cameOverHttps(Exchange exchange) {
        String origin = exchange.header("Origin");
        return origin != null && origin.regionMatches(true, 0, "https://", 0, 8);
    }

    private void login(Exchange exchange, String method) throws IOException {
        if (method.equals(GET)) {
            send(exchange, 200, HTML, loginPage(""));
            return;
        }
        if (!method.equals(POST)) {
            send(exchange, 405, TEXT, "Method not allowed.");
            return;
        }
        // Behind a trusted proxy each client has its own attempts.
        String address = Sessions.clientKey(exchange.clientAddress());
        if (!this.sessions.mayTry(address)) {
            send(exchange, 429, HTML, loginPage("Too many attempts: wait a minute."));
            return;
        }
        char[] password = exchange.form(MAX_LOGIN_FIELDS, MAX_LOGIN_BODY)
                .orElseThrow(() -> new IllegalArgumentException("form too large"))
                .getOrDefault("password", "")
                .toCharArray();
        if (!this.checking.tryAcquire()) {
            Passwords.wipe(password);
            send(exchange, 429, HTML, loginPage("Busy: try again in a moment."));
            return;
        }
        boolean right;
        try {
            right = Passwords.matches(password, this.settings.passwordHash());
        } finally {
            this.checking.release();
            Passwords.wipe(password);
        }
        if (!right) {
            logger.warn("Wrong web interface password from {}", address);
            send(exchange, 401, HTML, loginPage("Wrong password."));
            return;
        }
        this.sessions.succeeded(address);
        Sessions.Session session = this.sessions.open();
        exchange.addCookie(sessionCookie(exchange, session.id()).build());
        redirect(exchange, "/");
    }

    private static String loginPage(String error) {
        return Html.page(
                "Log in",
                "",
                (error.isEmpty() ? "" : "<p class=\"error\">" + Html.text(error) + "</p>")
                        + "<form method=\"post\" action=\"login\">"
                        + "<input type=\"password\" name=\"password\" autofocus"
                        + " autocomplete=\"current-password\"> "
                        + "<button class=\"main\">Log in</button></form>");
    }

    private void get(Exchange exchange, String path, Sessions.Session session) throws IOException {
        String page = switch (path) {
            case "/" -> statusPage(session);
            case TRANSFERS -> transfersPage(session);
            case SEARCH -> searchPage(session, exchange.query().get("id"));
            case SERVERS -> serversPage(session);
            case SHARED -> sharedPage(session);
            case KAD -> kadPage(session);
            case LOG -> logPage(session);
            case SETTINGS_PAGE -> settingsPage(session);
            default -> null;
        };
        if (page == null) {
            send(exchange, 404, TEXT, "Not found.");
        } else {
            send(exchange, 200, HTML, page);
        }
    }

    private void post(Exchange exchange, String path, Sessions.Session session) throws IOException {
        Optional<Map<String, String>> posted = exchange.form(MAX_FIELDS, MAX_BODY);
        if (posted.isEmpty()) {
            send(exchange, 413, HTML, notDone(session, path, "Too much text at once: send it in parts."));
            return;
        }
        Map<String, String> form = posted.get();
        if (!Sessions.validForm(session, form.get("csrf"))) {
            send(exchange, 403, TEXT, "Expired form: reload the page.");
            return;
        }
        String next;
        try {
            next = switch (path) {
                case "/logout" -> {
                    this.sessions.close(session.id());
                    exchange.addCookie(sessionCookie(exchange, "").maxAge(0).build());
                    yield "/login";
                }
                case TRANSFERS -> {
                    transfer(form);
                    yield TRANSFERS;
                }
                case SEARCH -> search(form);
                case SERVERS -> {
                    server(form);
                    yield SERVERS;
                }
                case SHARED -> {
                    shared(form);
                    yield SHARED;
                }
                case KAD -> {
                    kad(form);
                    yield KAD;
                }
                case SETTINGS_PAGE -> {
                    hostNames(form, exchange.header("Host"));
                    yield SETTINGS_PAGE;
                }
                default -> null;
            };
        } catch (IllegalArgumentException | EngineFailure e) {
            send(exchange, 400, HTML, notDone(session, path, String.valueOf(e.getMessage())));
            return;
        }
        if (next == null) {
            send(exchange, 404, TEXT, "Not found.");
        } else {
            redirect(exchange, next);
        }
    }

    private static String notDone(Sessions.Session session, String path, String message) {
        return Html.page(
                "Not done",
                session.csrf(),
                "<p class=\"error\">" + Html.text(message) + "</p><p><a href=\"" + Html.text(path) + "\">Back</a></p>");
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
                await(this.engine.addDownload(link));
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
        if (!await(done)) {
            throw new IllegalArgumentException("That download is gone: reload the page.");
        }
    }

    private String search(Map<String, String> form) {
        if (form.containsKey("download")) {
            long id = Long.parseLong(form.getOrDefault("search", ""));
            Hash16 hash = Hash16.parseHex(form.get("download"));
            SearchResult result = this.searches
                    .result(id, hash)
                    .orElseThrow(() -> new IllegalArgumentException("That result is gone: search again."));
            await(this.engine.addDownload(result.link()));
            return TRANSFERS;
        }
        String expression = form.getOrDefault("query", "").strip();
        if (expression.isEmpty()) {
            return SEARCH;
        }
        SearchRequest.checkExpression(expression);
        String chosen = form.getOrDefault("scope", SearchRequest.Scope.SERVER.name());
        SearchRequest.Scope scope = chosen.equals(AUTOMATIC) ? automaticScope() : SearchRequest.Scope.valueOf(chosen);
        SearchId id = await(this.engine.search(new SearchRequest(expression, scope)));
        this.searches.started(id.value(), new SearchCollector.Asked(expression, chosen));
        return SEARCH + "?id=" + id.value();
    }

    // As in the desktop app: the network connected, Kad with both unless the server is a static or a big one.
    private SearchRequest.Scope automaticScope() {
        EngineStatus status = await(this.engine.status());
        return SearchRequest.automatic(
                        status.server().isPresent(),
                        status.kad().map(KadInfo::connected).orElse(false),
                        status.server(),
                        await(this.engine.servers()).size())
                .orElseThrow(() -> new IllegalArgumentException("Not connected to a server or to Kad."));
    }

    private void server(Map<String, String> form) {
        switch (form.getOrDefault(ACTION, "")) {
            case "connect" -> {
                String address = form.getOrDefault("address", "").strip();
                await(this.engine.connect(
                        address.isEmpty() ? Optional.empty() : Optional.of(ServerAddress.parse(address))));
            }
            case "disconnect" -> await(this.engine.disconnect());
            default -> throw new IllegalArgumentException("Unknown action.");
        }
    }

    private void shared(Map<String, String> form) {
        if (!form.getOrDefault(ACTION, "").equals("priority")) {
            throw new IllegalArgumentException("Unknown action.");
        }
        Hash16 hash = Hash16.parseHex(form.getOrDefault("hash", ""));
        UploadPriority priority =
                UploadPriority.valueOf(form.getOrDefault("priority", "").toUpperCase(Locale.ROOT));
        if (!await(this.engine.setUploadPriority(hash, priority))) {
            throw new IllegalArgumentException("That file is no longer shared: reload the page.");
        }
    }

    private void kad(Map<String, String> form) {
        switch (form.getOrDefault(ACTION, "")) {
            case "connect" -> await(this.engine.connectKad());
            case "disconnect" -> await(this.engine.disconnectKad());
            case "recheck" -> {
                if (!await(this.engine.recheckKadFirewall())) {
                    throw new IllegalArgumentException("Kad is not connected.");
                }
            }
            case "bootstrap" ->
                await(this.engine.kadBootstrap(
                        ServerAddress.parse(form.getOrDefault("address", "").strip())));
            default -> throw new IllegalArgumentException("Unknown action.");
        }
    }

    private String sharedPage(Sessions.Session session) {
        List<SharedFileInfo> files = await(this.engine.sharedFiles());
        List<SharedDirectory> directories = await(this.engine.sharedDirectories());
        String csrf = session.csrf();
        StringBuilder body = new StringBuilder(1024 + files.size() * 384);
        body.append("<p>");
        if (directories.isEmpty()) {
            body.append("Only the incoming folder is shared.");
        } else {
            body.append("Folders: ");
            for (int i = 0; i < directories.size(); i++) {
                SharedDirectory d = directories.get(i);
                body.append(i == 0 ? "" : ", ")
                        .append(Html.text(d.path().toString()))
                        .append(d.subdirectories() ? " (with subfolders)" : "");
            }
        }
        body.append("</p>");
        if (files.isEmpty()) {
            return Html.page(
                    "Shared files", csrf, body.append("<p>No shared files.</p>").toString());
        }
        body.append("<table><tr><th>File</th><th>Size</th><th>Priority</th></tr>");
        for (SharedFileInfo f : files) {
            body.append("<tr><td>")
                    .append(Html.text(f.name()))
                    .append("</td><td>")
                    .append(Html.size(f.size()))
                    .append("</td><td>")
                    .append(Html.form(
                            SHARED,
                            csrf,
                            Html.hidden(ACTION, "priority")
                                    + Html.hidden("hash", f.hash().toHex())
                                    + options("priority", UploadPriority.values(), f.priority()),
                            "Set",
                            false))
                    .append("</td></tr>");
        }
        return Html.page("Shared files", csrf, body.append("</table>").toString());
    }

    private String kadPage(Sessions.Session session) {
        Optional<KadInfo> kad = await(this.engine.kad());
        String csrf = session.csrf();
        StringBuilder body = new StringBuilder(2048);
        if (kad.isEmpty()) {
            body.append("<p>Kad is off in the settings.</p>");
            return Html.page("Kad", csrf, body.toString());
        }
        KadInfo k = kad.get();
        body.append("<div class=\"cards\">")
                .append(card("State", !k.connected() ? "not connected" : k.firewalled() ? "firewalled" : "open"))
                .append(card("UDP", k.udpFirewalled() ? "firewalled" : k.udpVerified() ? "open" : "not checked yet"))
                .append(card("Buddy", k.buddy().name().toLowerCase(Locale.ROOT)))
                .append(card("Contacts", k.contacts() + " (" + k.verifiedContacts() + " verified)"))
                .append(card("Public address", Html.text(k.publicIp().isEmpty() ? "unknown" : k.publicIp())))
                .append("</div><p>")
                .append(Html.form(
                        KAD,
                        csrf,
                        Html.hidden(ACTION, k.connected() ? "disconnect" : "connect"),
                        k.connected() ? "Disconnect" : "Connect",
                        true))
                .append(' ')
                .append(Html.form(KAD, csrf, Html.hidden(ACTION, "recheck"), "Recheck firewall", false))
                .append("</p><p>")
                .append(Html.form(
                        KAD,
                        csrf,
                        Html.hidden(ACTION, "bootstrap")
                                + "<input type=\"text\" name=\"address\" required placeholder=\"host:port\""
                                + " title=\"A Kad node: its address and UDP port\"> ",
                        "Bootstrap",
                        false))
                .append("</p>");
        return Html.page("Kad", csrf, body.toString());
    }

    private String logPage(Sessions.Session session) {
        List<RecentLog.Entry> lines = this.recentLog.after(0);
        StringBuilder body = new StringBuilder(256 + lines.size() * 128);
        body.append("<p><a href=\"log\">Reload</a></p>");
        if (lines.isEmpty()) {
            return Html.page(
                    "Log",
                    session.csrf(),
                    body.append("<p>Nothing logged yet.</p>").toString());
        }
        body.append("<table><tr><th>Time</th><th>Level</th><th>Message</th></tr>");
        for (RecentLog.Entry e : lines.reversed()) {
            body.append("<tr><td>")
                    .append(Html.text(e.line().time().toString()))
                    .append("</td><td>")
                    .append(e.line().level().name().toLowerCase(Locale.ROOT))
                    .append("</td><td>")
                    .append(Html.text(e.line().message()))
                    .append("</td></tr>");
        }
        return Html.page("Log", session.csrf(), body.append("</table>").toString());
    }

    // A choice among the values of an enum, as lower-case names.
    private static String options(String name, Enum<?>[] values, Enum<?> current) {
        StringBuilder select =
                new StringBuilder("<select name=\"").append(Html.text(name)).append("\">");
        for (Enum<?> value : values) {
            String text = value.name().toLowerCase(Locale.ROOT);
            select.append("<option value=\"")
                    .append(text)
                    .append(value == current ? "\" selected>" : "\">")
                    .append(text.replace('_', ' '))
                    .append("</option>");
        }
        return select.append("</select> ").toString();
    }

    private String statusPage(Sessions.Session session) {
        EngineStatus s = await(this.engine.status());
        String ed2k = s.server()
                .map(i -> Html.text(i.name()) + (s.lowId() ? " (LowID)" : " (HighID)"))
                .orElse("not connected");
        String kad = s.kad()
                .map(k -> !k.connected() ? "not connected" : k.firewalled() ? "firewalled" : "open")
                .orElse("off");
        String body = "<div class=\"cards\">"
                + card("eD2K", ed2k)
                + card("Kad", kad)
                + card("IPv6", Html.text(s.publicIpv6().orElse("none")))
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
        List<DownloadInfo> downloads = await(this.engine.downloads());
        String csrf = session.csrf();
        StringBuilder body = new StringBuilder(1024 + downloads.size() * 512);
        body.append("<p>")
                .append(Html.form(
                        TRANSFERS,
                        csrf,
                        Html.hidden(ACTION, "add")
                                + "<input type=\"text\" name=\"link\" size=\"60\" required"
                                + " pattern=\"\\s*([eE][dD]2[kK]://|[mM][aA][gG][nN][eE][tT]:\\?).+\""
                                + " maxlength=\"" + MAX_LINKS_LENGTH + "\""
                                + " title=\"One or more eD2K file or magnet links\""
                                + " placeholder=\"ed2k://|file|...|/\"> ",
                        "Download",
                        true))
                .append("</p>");
        if (downloads.isEmpty()) {
            return Html.page(
                    "Transfers", csrf, body.append("<p>No downloads.</p>").toString());
        }
        body.append("<table><tr><th>File</th><th>Size</th><th>Done</th><th>Speed</th>")
                .append("<th>Sources</th><th>Status</th><th>Priority</th><th>Category</th><th></th></tr>");
        Map<Integer, String> categories = new HashMap<>();
        await(this.engine.categories()).forEach(c -> categories.put(c.id(), c.name()));
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
                    .append(Html.text(categories.getOrDefault(d.category(), "")))
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
        List<SearchCollector.Listed> started = this.searches.searches();
        // Back from another page, the latest search shows again.
        Long id = parseId(idText);
        if (id == null && !started.isEmpty()) {
            id = started.getLast().id();
        }
        List<SearchResult> found = id == null ? null : this.searches.results(id).orElse(null);
        SearchCollector.Asked asked = id == null
                ? new SearchCollector.Asked("", "")
                : this.searches.asked(id).orElse(new SearchCollector.Asked("", ""));
        StringBuilder body = new StringBuilder(4096);
        if (started.size() > 1) {
            body.append("<nav class=\"tabs\">");
            for (SearchCollector.Listed s : started) {
                body.append("<a href=\"search?id=")
                        .append(s.id())
                        .append(s.id() == id ? "\" class=\"current\">" : "\">")
                        .append(Html.text(
                                s.asked().expression().isEmpty()
                                        ? "#" + s.id()
                                        : s.asked().expression()))
                        .append("</a>");
            }
            body.append("</nav>");
        }
        body.append("<p>")
                .append(Html.form(
                        SEARCH,
                        csrf,
                        "<input type=\"text\" name=\"query\" size=\"40\" required maxlength=\""
                                + SearchRequest.MAX_EXPRESSION_LENGTH
                                + "\" value=\""
                                + Html.text(asked.expression())
                                + "\" autofocus> "
                                + "<select name=\"scope\">"
                                + scopeOption(AUTOMATIC, "Automatic", asked.scope())
                                + scopeOption("SERVER", "Server", asked.scope())
                                + scopeOption("GLOBAL", "Global", asked.scope())
                                + scopeOption("KAD", "Kad", asked.scope())
                                + "</select> ",
                        "Search",
                        true))
                .append("</p>");
        if (found == null) {
            return Html.page("Search", csrf, body.toString());
        }
        // The page asks for itself again while results may come: the pages run no scripts.
        boolean running = this.searches.running(id);
        found.sort((a, b) -> Integer.compare(b.sources(), a.sources()));
        body.append("<p>")
                .append(running ? "Searching… " : "Search finished: ")
                .append(found.size())
                .append(found.size() == 1 ? " result" : " results")
                .append(running ? " so far." : ".")
                .append("</p>");
        Set<Hash16> downloading = knownHashes(this.engine.downloads(), DownloadInfo::hash);
        Set<Hash16> shared = knownHashes(this.engine.sharedFiles(), SharedFileInfo::hash);
        // The media columns show when a result has something in them, as the files of other kinds have nothing.
        List<MediaColumn> media = MediaColumn.ALL.stream()
                .filter(c -> found.stream().anyMatch(c::has))
                .toList();
        body.append(
                        "<div class=\"scroll\"><table class=\"results\"><tr><th>File</th><th>Size</th><th>Sources</th><th>Complete</th>")
                .append("<th>Type</th>")
                .append("<th>Known</th>");
        media.forEach(c -> body.append("<th>").append(c.title).append("</th>"));
        body.append("<th></th></tr>");
        for (SearchResult r : found) {
            String known = downloading.contains(r.hash())
                    ? "Downloading"
                    : shared.contains(r.hash()) ? "Shared" : r.isSpam() ? "Spam" : "";
            body.append(r.isSpam() ? "<tr class=\"spam\"><td>" : "<tr><td>")
                    .append(Html.text(r.name()))
                    .append("</td><td>")
                    .append(Html.size(r.size()))
                    .append("</td><td>")
                    .append(r.sources())
                    .append("</td><td>")
                    .append(complete(r))
                    .append("</td><td>")
                    .append(Html.text(r.type().orElse("")))
                    .append("</td><td>")
                    .append(known)
                    .append("</td>");
            media.forEach(c -> body.append(c.words() ? "<td class=\"words\">" : "<td>")
                    .append(Html.text(c.text(r)))
                    .append("</td>"));
            body.append("<td>")
                    .append(Html.form(
                            SEARCH,
                            csrf,
                            Html.hidden("search", String.valueOf(id))
                                    + Html.hidden("download", r.hash().toHex()),
                            "Download",
                            false))
                    .append("</td></tr>");
        }
        return Html.page("Search", csrf, body.append("</table></div>").toString(), running ? SEARCH_REFRESH : 0);
    }

    // The files a column of results marks; none when the engine cannot tell, which leaves the results shown.
    private <T> Set<Hash16> knownHashes(CompletableFuture<List<T>> files, Function<T, Hash16> hash) {
        try {
            return await(files).stream().map(hash).collect(Collectors.toSet());
        } catch (EngineFailure e) {
            return Set.of();
        }
    }

    // As the desktop app: the complete sources as a share of the sources, "?" when the network does not tell (Kad),
    // but a file of one part is whole wherever it is.
    private static String complete(SearchResult r) {
        if (r.completeSources() < 0) {
            return r.size() <= PartMap.PART_SIZE ? "Yes" : "?";
        }
        if (r.completeSources() == 0) {
            return "0%";
        }
        return r.sources() > 0 ? (100L * r.completeSources() / r.sources()) + "%" : "Yes";
    }

    /** A column of the media details of the results; one of {@code words} may wrap. */
    private record MediaColumn(String title, boolean words, Function<SearchResult.Media, String> value) {
        static final List<MediaColumn> ALL = List.of(
                new MediaColumn("Artist", true, SearchResult.Media::artist),
                new MediaColumn("Album", true, SearchResult.Media::album),
                new MediaColumn("Title", true, SearchResult.Media::title),
                new MediaColumn("Length", false, m -> m.length() == 0 ? "" : playingTime(m.length())),
                new MediaColumn("Bitrate", false, m -> m.bitrate() == 0 ? "" : m.bitrate() + " kbit/s"),
                new MediaColumn("Codec", false, SearchResult.Media::codec));

        boolean has(SearchResult r) {
            return !text(r).isEmpty();
        }

        String text(SearchResult r) {
            return this.value.apply(r.media());
        }

        private static String playingTime(int seconds) {
            int h = seconds / 3600;
            int m = seconds / 60 % 60;
            int s = seconds % 60;
            return h > 0
                    ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                    : String.format(Locale.ROOT, "%d:%02d", m, s);
        }
    }

    private String settingsPage(Sessions.Session session) {
        String csrf = session.csrf();
        String besides = this.settings.lan()
                ? "localhost, this computer's addresses and their Synology QuickConnect names"
                        + " (192-168-1-80.&lt;id&gt;.direct.quickconnect.to)"
                : "localhost";
        String body = "<h2>Host names</h2><p>Besides " + besides
                + ", the interface answers only to these names, such as a name on the LAN or a reverse proxy's:"
                + " a page of another site that reaches it under another name is refused (DNS rebinding).</p><p>"
                + Html.form(
                        SETTINGS_PAGE,
                        csrf,
                        "<input type=\"text\" name=\"hosts\" size=\"60\" value=\""
                                + Html.text(String.join(", ", this.hosts))
                                + "\" placeholder=\"nas.local, njmule.example.com\"> ",
                        "Save",
                        true)
                + "</p>";
        return Html.page("Settings", csrf, body);
    }

    // The names of the settings, written to the file of the running settings, which the command line may have changed.
    private void hostNames(Map<String, String> form, String host) {
        List<String> names = WebUiSettings.hostNames(form.getOrDefault("hosts", ""));
        String current = host == null ? "" : withoutPort(host);
        if (this.hosts.contains(current) && !names.contains(current)) {
            throw new IllegalArgumentException("Keep " + current + ": this page is open under that name.");
        }
        Path dir = this.engine.directories().config();
        WebUiSettings file = WebUiSettings.load(dir);
        if (!file.usable()) {
            throw new IllegalArgumentException("Cannot read " + WebUiSettings.FILE_NAME + ": nothing was changed.");
        }
        WebUiSettings changed = new WebUiSettings(
                file.enabled(), file.port(), file.lan(), file.passwordHash(), file.api(), names, file.proxies());
        try {
            changed.save(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot save the settings: " + e.getMessage(), e);
        }
        this.hosts = changed.hosts();
        logger.info("Web interface host names: {}", changed.hosts().isEmpty() ? "none" : changed.hosts());
    }

    private static String scopeOption(String value, String label, String chosen) {
        return "<option value=\"" + value + "\"" + (value.equals(chosen) ? " selected" : "") + ">" + label
                + "</option>";
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
        List<ServerInfo> servers = await(this.engine.servers());
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

    private Optional<Sessions.Session> session(Exchange exchange) {
        return exchange.cookie(this.cookie).flatMap(this.sessions::find);
    }

    private HttpCookie.Builder sessionCookie(Exchange exchange, String value) {
        return HttpCookie.build(this.cookie, value)
                .path("/")
                .httpOnly(true)
                .sameSite(HttpCookie.SameSite.STRICT)
                .secure(cameOverHttps(exchange));
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

    private void redirect(Exchange exchange, String location) throws IOException {
        exchange.setHeader("Location", Html.relative(location));
        securityHeaders(exchange);
        exchange.sendHeaders(303, -1);
    }

    private void send(Exchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(Exchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.setHeader("Content-Type", type);
        securityHeaders(exchange);
        if (exchange.isHead()) {
            // The length the GET would have, without the body the server refuses to send.
            exchange.setHeader("Content-Length", String.valueOf(bytes.length));
            exchange.sendHeaders(status, -1);
            return;
        }
        exchange.sendHeaders(status, bytes.length);
        try (OutputStream out = exchange.responseBody()) {
            out.write(bytes);
        }
    }

    private static void securityHeaders(Exchange exchange) {
        exchange.setHeader(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none';"
                        + " base-uri 'none'");
        exchange.setHeader("X-Frame-Options", "DENY");
        exchange.setHeader("X-Content-Type-Options", "nosniff");
        // Not no-referrer: browsers would then send "Origin: null" with the pages' own forms.
        exchange.setHeader("Referrer-Policy", "same-origin");
        exchange.setHeader("Cache-Control", "no-store");
    }

    /** The engine could not do what a request asked; the message is meant for the user. */
    private static final class EngineFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EngineFailure(String message) {
            super(message);
        }
    }
}
