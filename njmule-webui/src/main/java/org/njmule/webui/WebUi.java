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
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.remote.ApiToken;
import org.njmule.core.remote.Exchange;
import org.njmule.core.remote.RecentEvents;
import org.njmule.core.remote.RemoteApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web interface: the browser app of njmule-webapp behind a password, and the remote-control API it calls with the
 * login session, every change carrying the session's token in a header. The login page runs no script; no page can be
 * framed, and the session cookie is HTTP-only, same-site and, over HTTPS, secure. When its settings ask for it, the API
 * also answers callers with the API token, under {@value RemoteApi#PREFIX}.
 */
public final class WebUi implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebUi.class);
    private static final String HTML = "text/html; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String API_PATH = "/api/";
    // The web interface's own settings the app changes: the host names it answers to.
    private static final String HOSTS_PATH = "/webui/hosts";
    // The header that carries the session's token on the app's changes: no other site's page can send it.
    private static final String CSRF_HEADER = "X-NjMule-CSRF";
    private static final String PAGES_POLICY =
            "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self';"
                    + " frame-ancestors 'none'; base-uri 'none'";
    private static final String APP_POLICY = "default-src 'none'; script-src 'self'; style-src 'self';"
            + " img-src 'self' data:; font-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none';"
            + " base-uri 'none'";
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
    private final RemoteApi api;
    private final WebApp app = new WebApp();

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
        // The app calls it with the login session; callers with the API token too when the settings allow them. A
        // wrong API token counts as a failed login of its address.
        this.api = new RemoteApi(
                engine, this.recent, client -> this.sessions.mayTry(Sessions.clientKey(client)), exit, heartbeat);
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
        RemoteApi api = ui.api;
        boolean tokens = settings.api();
        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) {
                Exchange exchange = new Exchange(request, response, ui.proxies);
                try {
                    if (tokens && exchange.path().startsWith(API_PATH) && exchange.header("Authorization") != null) {
                        api.handle(exchange);
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
        this.api.close();
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
        if (!method.equals(GET) && !trustedOrigin(exchange.header("Origin"), exchange.header("Host"), names)) {
            send(exchange, 403, TEXT, "Cross-site request refused.");
            return;
        }
        Optional<WebApp.File> file = method.equals(GET) ? this.app.file(path) : Optional.empty();
        if (file.isPresent()) {
            send(exchange, file.get());
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
        if (path.startsWith(API_PATH)) {
            callApi(exchange, method, session);
            return;
        }
        if (path.equals(HOSTS_PATH)) {
            hosts(exchange, method, session);
            return;
        }
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

    // The app's calls: with the session, and with its token on changes. Token callers reach here only while the
    // settings turn them away.
    private void callApi(Exchange exchange, String method, Optional<Sessions.Session> session) throws IOException {
        if (session.isEmpty() && exchange.header("Authorization") != null) {
            sendJson(exchange, 404, "The remote-control API is off.");
        } else if (session.isEmpty()) {
            sendJson(exchange, 401, "Log in first.");
        } else if (!method.equals(GET) && !Sessions.validForm(session.get(), exchange.header(CSRF_HEADER))) {
            sendJson(exchange, 403, "Expired session token: reload the page.");
        } else {
            this.api.handleTrusted(exchange);
        }
    }

    // The host names as JSON; a PUT of the form field "hosts", with the session's token, replaces them.
    private void hosts(Exchange exchange, String method, Optional<Sessions.Session> session) throws IOException {
        if (session.isEmpty()) {
            sendJson(exchange, 401, "Log in first.");
            return;
        }
        if (!method.equals(GET)) {
            Optional<Map<String, String>> form = exchange.form(MAX_FIELDS, MAX_BODY);
            if (!method.equals("PUT") || form.isEmpty()) {
                sendJson(exchange, 400, "Send the host names as the form field hosts.");
                return;
            }
            if (!Sessions.validForm(session.get(), exchange.header(CSRF_HEADER))) {
                sendJson(exchange, 403, "Expired session token: reload the page.");
                return;
            }
            try {
                hostNames(form.get(), exchange.header("Host"));
            } catch (IllegalArgumentException e) {
                sendJson(exchange, 400, String.valueOf(e.getMessage()));
                return;
            }
        }
        StringBuilder json =
                new StringBuilder("{\"lan\":").append(this.settings.lan()).append(",\"hosts\":[");
        List<String> names = this.hosts;
        for (int i = 0; i < names.size(); i++) {
            // Host names are letters, digits, dots and hyphens: nothing to escape.
            json.append(i == 0 ? "" : ",").append('"').append(names.get(i)).append('"');
        }
        send(exchange, 200, "application/json; charset=utf-8", json.append("]}").toString());
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

    private void get(Exchange exchange, String path, Sessions.Session session) throws IOException {
        if (path.equals("/")) {
            send(exchange, 200, HTML, this.app.page(session.csrf()).getBytes(StandardCharsets.UTF_8), APP_POLICY);
        } else {
            send(exchange, 404, TEXT, "Not found.");
        }
    }

    // The one form left besides the login: logging out, with the session's form token.
    private void post(Exchange exchange, String path, Sessions.Session session) throws IOException {
        Optional<Map<String, String>> form = exchange.form(MAX_FIELDS, MAX_BODY);
        if (form.isEmpty() || !Sessions.validForm(session, form.get().get("csrf"))) {
            send(exchange, 403, TEXT, "Expired form: reload the page.");
            return;
        }
        if (!path.equals("/logout")) {
            send(exchange, 404, TEXT, "Not found.");
            return;
        }
        this.sessions.close(session.id());
        exchange.addCookie(sessionCookie(exchange, "").maxAge(0).build());
        redirect(exchange, "/login");
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
        securityHeaders(exchange, PAGES_POLICY);
        exchange.sendHeaders(303, -1);
    }

    private void send(Exchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private void send(Exchange exchange, int status, String type, byte[] bytes) throws IOException {
        send(exchange, status, type, bytes, PAGES_POLICY);
    }

    // A file of the app; the bundled ones never change under their names.
    private static void send(Exchange exchange, WebApp.File file) throws IOException {
        exchange.setHeader("Content-Type", file.type());
        securityHeaders(exchange, PAGES_POLICY);
        exchange.setHeader("Cache-Control", file.immutable() ? "public, max-age=31536000, immutable" : "no-cache");
        byte[] bytes = file.bytes();
        if (exchange.isHead()) {
            exchange.setHeader("Content-Length", String.valueOf(bytes.length));
            exchange.sendHeaders(200, -1);
            return;
        }
        exchange.sendHeaders(200, bytes.length);
        try (OutputStream out = exchange.responseBody()) {
            out.write(bytes);
        }
    }

    // An error of the app's calls, in the API's form.
    private void sendJson(Exchange exchange, int status, String message) throws IOException {
        String text = message.replace("\\", "\\\\").replace("\"", "\\\"");
        send(
                exchange,
                status,
                "application/json; charset=utf-8",
                ("{\"error\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private void send(Exchange exchange, int status, String type, byte[] bytes, String policy) throws IOException {
        exchange.setHeader("Content-Type", type);
        securityHeaders(exchange, policy);
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

    private static void securityHeaders(Exchange exchange, String policy) {
        exchange.setHeader("Content-Security-Policy", policy);
        exchange.setHeader("X-Frame-Options", "DENY");
        exchange.setHeader("X-Content-Type-Options", "nosniff");
        // Not no-referrer: browsers would then send "Origin: null" with the pages' own forms.
        exchange.setHeader("Referrer-Policy", "same-origin");
        exchange.setHeader("Cache-Control", "no-store");
    }
}
