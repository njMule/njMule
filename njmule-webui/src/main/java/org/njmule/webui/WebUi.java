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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NewVersion;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.api.NjMuleInfo;
import org.njmule.core.api.UpdateStatus;
import org.njmule.core.config.NightHours;
import org.njmule.core.config.Preferences;
import org.njmule.core.config.UpdateChannel;
import org.njmule.core.config.UpdateInstallTime;
import org.njmule.core.config.UpdateMode;
import org.njmule.core.remote.ApiToken;
import org.njmule.core.remote.Exchange;
import org.njmule.core.remote.RecentEvents;
import org.njmule.core.remote.RecentLog;
import org.njmule.core.remote.RemoteApi;
import org.njmule.core.remote.SearchCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web interface: transfers, searches, shared files, the network and the statistics as plain HTML pages behind a
 * password, laid out as the desktop app. Every change is a POST carrying the session's form token; pages run no scripts
 * and cannot be framed, and the session cookie is HTTP-only, same-site and, over HTTPS, secure. When its settings ask
 * for it, the remote-control API answers under {@value RemoteApi#PREFIX}.
 */
public final class WebUi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebUi.class);
    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String SETTINGS_PAGE = "/settings";
    private static final String ACTION = "action";
    private static final int MAX_LOGIN_BODY = 16 * 1024;
    private static final int MAX_LOGIN_FIELDS = 4;
    // Far more than any of the forms has.
    private static final int MAX_FIELDS = 32;
    private static final int MAX_BODY = 1024 * 1024;

    // Each check keeps a core busy for a while: logins from many addresses cannot take them all.
    private static final int PASSWORD_CHECKS = 2;

    // Names under which a browser on this computer reaches the interface; other computers use its
    // address or a name of the settings. Any other Host header may be a DNS-rebinding attempt.
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "[::1]");

    // Synology's QuickConnect names an address of a NAS a-b-c-d.<id>.direct.quickconnect.to, and its DNS answers with
    // that address only: such a name of the address a request came to cannot be rebound elsewhere.
    private static final Pattern QUICKCONNECT =
            Pattern.compile("(\\d{1,3})-(\\d{1,3})-(\\d{1,3})-(\\d{1,3})\\.[a-z0-9-]+\\.direct\\.quickconnect\\.to");

    private final NjMuleEngine engine;
    private final EngineCalls calls;
    private final TransfersPages transfers;
    private final SearchPages searchPages;
    private final NetworkPages network;
    private final LibraryPages library;
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
        this.calls = new EngineCalls(engine);
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
        this.transfers = new TransfersPages(this.calls);
        this.searchPages = new SearchPages(this.calls, this.searches);
        this.network = new NetworkPages(this.calls, this.recentLog);
        this.library = new LibraryPages(this.calls);
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
        } catch (EngineCalls.Failure e) {
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
        return Html.bare(
                "Log in",
                (error.isEmpty() ? "" : "<p class=\"error\">" + Html.text(error) + "</p>")
                        + "<form method=\"post\" action=\"login\">"
                        + "<input type=\"password\" name=\"password\" autofocus aria-label=\"Password\""
                        + " autocomplete=\"current-password\"> "
                        + "<button class=\"main\">Log in</button></form>");
    }

    // The frame of a page: its place, the engine's state for the top bar, the word of a new version.
    private Html.Frame frame(Sessions.Session session, Html.Place place) {
        return new Html.Frame(
                session.csrf(),
                place,
                this.calls.maybe(this.engine.status()),
                updateNotice(this.engine.updateStatus()));
    }

    private void get(Exchange exchange, String path, Sessions.Session session) throws IOException {
        if (path.equals("/")) {
            redirect(exchange, TransfersPages.PATH);
            return;
        }
        Map<String, String> query = exchange.query();
        String page = switch (path) {
            case TransfersPages.PATH -> this.transfers.page(frame(session, Html.Place.TRANSFERS), query);
            case SearchPages.PATH -> this.searchPages.page(frame(session, Html.Place.SEARCH), query);
            case NetworkPages.SERVERS -> this.network.servers(frame(session, Html.Place.NETWORK));
            case NetworkPages.KAD -> this.network.kad(frame(session, Html.Place.NETWORK));
            case NetworkPages.LOG -> this.network.log(frame(session, Html.Place.NETWORK), query);
            case LibraryPages.SHARED -> this.library.shared(frame(session, Html.Place.SHARED), query);
            case LibraryPages.STATISTICS -> this.library.statistics(frame(session, Html.Place.STATISTICS));
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
        if (path.equals(TransfersPages.PATH) && TransfersPages.asksFirst(form)) {
            confirmCancel(exchange, session, form);
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
                case TransfersPages.PATH -> this.transfers.act(form);
                case SearchPages.PATH -> this.searchPages.act(form, Optional.empty(), this.transfers);
                case NetworkPages.SERVERS -> this.network.actOnServers(form);
                case NetworkPages.KAD -> this.network.actOnKad(form);
                case LibraryPages.SHARED -> this.library.actOnShared(form);
                case SETTINGS_PAGE -> {
                    if (form.containsKey(ACTION)) {
                        updateSettings(form);
                    } else {
                        hostNames(form, exchange.header("Host"));
                    }
                    yield SETTINGS_PAGE;
                }
                default -> null;
            };
        } catch (IllegalArgumentException | EngineCalls.Failure e) {
            send(exchange, 400, HTML, notDone(session, path, String.valueOf(e.getMessage())));
            return;
        }
        if (next == null) {
            send(exchange, 404, TEXT, "Not found.");
        } else {
            redirect(exchange, next);
        }
    }

    // A cancel names both outcomes first, as the desktop app's question does.
    private void confirmCancel(Exchange exchange, Sessions.Session session, Map<String, String> form)
            throws IOException {
        String page;
        try {
            page = this.transfers.confirmCancel(
                    frame(session, Html.Place.TRANSFERS), Hash16.parseHex(form.getOrDefault("hash", "")));
        } catch (IllegalArgumentException | EngineCalls.Failure e) {
            send(exchange, 400, HTML, notDone(session, TransfersPages.PATH, String.valueOf(e.getMessage())));
            return;
        }
        send(exchange, 200, HTML, page);
    }

    private String notDone(Sessions.Session session, String path, String message) {
        Html.Place place = switch (path) {
            case SearchPages.PATH -> Html.Place.SEARCH;
            case NetworkPages.SERVERS, NetworkPages.KAD, NetworkPages.LOG -> Html.Place.NETWORK;
            case LibraryPages.SHARED -> Html.Place.SHARED;
            case SETTINGS_PAGE -> Html.Place.SETTINGS;
            default -> Html.Place.TRANSFERS;
        };
        return Html.page(
                frame(session, place),
                "Not done",
                "",
                "<p class=\"error\">" + Html.text(message) + "</p><p><a href=\"" + Html.text(Html.relative(path))
                        + "\">Back</a></p>",
                0);
    }

    // A new version the user has not skipped, above every page: available, downloading, ready or being installed.
    private static String updateNotice(UpdateStatus update) {
        if (update.install() == UpdateStatus.Install.RESTART) {
            return "<p class=\"notice\"><b>" + Html.text(NjMuleInfo.NAME)
                    + " is installing a new version and starting again.</b> Load this page again in a minute.</p>";
        }
        if (!update.isWorthTelling()) {
            return "";
        }
        NewVersion v = update.available().orElseThrow();
        String state = update.ready().isPresent()
                ? " is downloaded and ready to install"
                : update.download().isPresent() ? " is downloading" : " is available";
        return "<p class=\"notice\"><b>" + Html.text(NjMuleInfo.NAME + " " + v.version()) + state + "</b>"
                + (v.critical() ? ", with security fixes" : "") + ". <a href=\""
                + Html.text(v.page().toString())
                + "\" rel=\"noreferrer\">What changed</a> &middot; <a href=\"settings\">"
                + (update.canInstall() ? "Install it" : "How to update")
                + "</a></p>";
    }

    private String settingsPage(Sessions.Session session) {
        String csrf = session.csrf();
        String besides = this.settings.lan()
                ? "localhost, this computer's addresses and their Synology QuickConnect names"
                        + " (192-168-1-80.&lt;id&gt;.direct.quickconnect.to)"
                : "localhost";
        UpdateStatus update = this.calls.engine().updateStatus();
        String body =
                updates(update, this.calls.engine().preferences(), csrf) + "<h2>Host names</h2><p>Besides " + besides
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
        boolean moving = update.checking() || update.download().isPresent();
        return Html.page(
                frame(session, Html.Place.SETTINGS),
                "Settings",
                "Updates and host names",
                body,
                moving ? SearchPages.REFRESH : 0);
    }

    private static String updates(UpdateStatus update, Preferences preferences, String csrf) {
        StringBuilder out = new StringBuilder("<h2>Updates</h2><p>")
                .append(Html.text(NjMuleInfo.NAME + " " + update.current()))
                .append(". It asks github.com whether a new version is out, sending nothing but the request.</p><p>")
                .append(Html.form(
                        SETTINGS_PAGE,
                        csrf,
                        Html.hidden(ACTION, "updates")
                                + "<select name=\"mode\" aria-label=\"What to do with a new version\">"
                                + option(UpdateMode.NOTIFY.name(), "Tell me", preferences.updateMode())
                                + option(UpdateMode.DOWNLOAD.name(), "Download it", preferences.updateMode())
                                + option(UpdateMode.INSTALL.name(), "Download and install it", preferences.updateMode())
                                + option(UpdateMode.OFF.name(), "Look only when asked", preferences.updateMode())
                                + "</select> <select name=\"channel\" aria-label=\"Which versions\">"
                                + option(UpdateChannel.STABLE.name(), "Releases", preferences.updateChannel())
                                + option(
                                        UpdateChannel.PRERELEASE.name(),
                                        "Test versions too",
                                        preferences.updateChannel())
                                + "</select> <select name=\"installAt\" aria-label=\"When to install\">"
                                + option(
                                        UpdateInstallTime.EXIT.name(),
                                        "Install at exit",
                                        preferences.updateInstallTime())
                                + option(
                                        UpdateInstallTime.EXIT_OR_NIGHT.name(),
                                        "At exit or at night",
                                        preferences.updateInstallTime())
                                + "</select> <input type=\"text\" name=\"nightHours\" size=\"11\""
                                + " aria-label=\"Night hours\" value=\""
                                + Html.text(preferences.nightHours().toString())
                                + "\"> ",
                        "Save",
                        false))
                .append("</p><p>");
        if (update.checking()) {
            out.append("Looking for a new version...");
        } else {
            out.append(update.lastCheck()
                            .map(t -> "Last looked: " + Html.text(t.toString()) + ". ")
                            .orElse(""))
                    .append(Html.form(SETTINGS_PAGE, csrf, Html.hidden(ACTION, "check"), "Check now", false));
        }
        out.append("</p>");
        update.failure()
                .ifPresent(f -> out.append("<p class=\"error\">It failed: ")
                        .append(Html.text(f))
                        .append("</p>"));
        if (update.available().isEmpty()
                && update.lastCheck().isPresent()
                && update.failure().isEmpty()) {
            out.append("<p>This is the newest version.</p>");
        }
        if (update.available().isPresent()) {
            NewVersion v = update.available().get();
            out.append("<p class=\"notice\"><b>")
                    .append(Html.text(NjMuleInfo.NAME + " " + v.version()))
                    .append(" is available</b>")
                    .append(v.critical() ? ", with security fixes" : "")
                    .append(". <a href=\"")
                    .append(Html.text(v.page().toString()))
                    .append("\" rel=\"noreferrer\">What changed</a><br>");
            out.append(installing(update, v, csrf));
            if (!v.critical() && !update.skipped().equals(Optional.of(v.version()))) {
                out.append("<br>")
                        .append(Html.form(
                                SETTINGS_PAGE,
                                csrf,
                                Html.hidden(ACTION, "skip") + Html.hidden("version", v.version()),
                                "Skip this version",
                                false));
            }
            out.append("</p>");
        }
        return out.toString();
    }

    // What can be done with the new version here: download it, follow the download, install it, or how to.
    private static String installing(UpdateStatus update, NewVersion v, String csrf) {
        if (update.download().isPresent()) {
            UpdateStatus.Progress p = update.download().get();
            return "Downloading: " + Html.size(p.received()) + " of " + Html.size(p.size()) + " ("
                    + Math.round(p.fraction() * 100) + "%).";
        }
        if (update.install() == UpdateStatus.Install.RESTART) {
            return "Installing it now: " + Html.text(NjMuleInfo.NAME) + " starts again in a moment.";
        }
        if (update.canInstall()) {
            String when = update.install() == UpdateStatus.Install.AT_EXIT
                    ? "It is installed when " + NjMuleInfo.NAME + " exits. "
                    : "";
            return Html.text(when)
                    + Html.form(SETTINGS_PAGE, csrf, Html.hidden(ACTION, "install"), "Install and restart", true)
                    + (update.install() == UpdateStatus.Install.AT_EXIT
                            ? ""
                            : " "
                                    + Html.form(
                                            SETTINGS_PAGE,
                                            csrf,
                                            Html.hidden(ACTION, "install-at-exit"),
                                            "Install at exit",
                                            false));
        }
        String how = update.ready().isPresent()
                ? UpdateHelp.howToInstallFile(
                        update.installation(), update.ready().get())
                : UpdateHelp.howToInstall(
                        update.installation(), v.download().map(NewVersion.Download::uri), Optional.of(v.page()));
        String download = v.download().isPresent() && update.ready().isEmpty()
                ? " " + Html.form(SETTINGS_PAGE, csrf, Html.hidden(ACTION, "download"), "Download", false)
                : "";
        return Html.text(how) + download;
    }

    private static String option(String value, String label, Enum<?> chosen) {
        return "<option value=\"" + value + "\"" + (value.equals(chosen.name()) ? " selected" : "") + ">"
                + Html.text(label) + "</option>";
    }

    // The mode and channel of the updates, a check now or a version skipped.
    @SuppressWarnings("FutureReturnValueIgnored")
    private void updateSettings(Map<String, String> form) {
        switch (form.getOrDefault(ACTION, "")) {
            case "updates" -> {
                UpdateMode mode = UpdateMode.valueOf(form.getOrDefault("mode", ""));
                UpdateChannel channel = UpdateChannel.valueOf(form.getOrDefault("channel", ""));
                UpdateInstallTime at =
                        UpdateInstallTime.valueOf(form.getOrDefault("installAt", UpdateInstallTime.EXIT.name()));
                Optional<NightHours> night = Optional.ofNullable(form.get("nightHours"))
                        .filter(h -> !h.isBlank())
                        .map(NightHours::parse);
                this.calls.await(this.calls
                        .engine()
                        .updatePreferences(p -> p.toBuilder()
                                .updateMode(mode)
                                .updateChannel(channel)
                                .updateInstallTime(at)
                                .nightHours(night.orElse(p.nightHours()))
                                .build()));
            }
            // Its outcome, a failure included, shows in the status once the page loads again.
            case "check" -> this.calls.engine().checkForUpdate();
            case "download" -> this.calls.engine().downloadUpdate();
            // A refusal (nothing to install, or not here) is shown as the engine's other failures are.
            case "install" -> this.calls.await(this.calls.engine().installUpdate(true));
            case "install-at-exit" -> this.calls.await(this.calls.engine().installUpdate(false));
            case "skip" -> this.calls.await(this.calls.engine().skipUpdate(form.getOrDefault("version", "")));
            default -> throw new IllegalArgumentException("Unknown action.");
        }
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
}
