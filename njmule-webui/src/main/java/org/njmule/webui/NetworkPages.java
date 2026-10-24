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

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadContactInfo;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.KadSearchInfo;
import org.njmule.core.api.LogLine;
import org.njmule.core.api.PortTest;
import org.njmule.core.api.ServerAddress;
import org.njmule.core.api.ServerInfo;
import org.njmule.core.config.Preferences;
import org.njmule.core.remote.RecentLog;

/**
 * The network, as the desktop app shows it: the state of eD2K, Kad and our ports on cards, each with what to do about
 * it, then the servers, Kad and the log as views of one place.
 */
final class NetworkPages {

    static final String SERVERS = "/servers";
    static final String KAD = "/kad";
    static final String LOG = "/log";

    private static final String ACTION = "action";
    private static final String ADDRESS = "address";
    // A routing table can hold thousands of contacts: the closest ones say enough.
    private static final int CONTACTS_SHOWN = 200;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final EngineCalls calls;
    private final RecentLog log;

    NetworkPages(EngineCalls calls, RecentLog log) {
        this.calls = calls;
        this.log = log;
    }

    String servers(Html.Frame frame) {
        List<ServerInfo> servers = this.calls.await(this.calls.engine().servers());
        String csrf = frame.csrf();
        Optional<String> current = frame.status().flatMap(EngineStatus::server).map(ServerInfo::address);
        StringBuilder body = new StringBuilder(4096 + servers.size() * 768);
        body.append(head(frame, SERVERS))
                .append("<div class=\"toolbar\">")
                .append(Html.form(
                        SERVERS,
                        csrf,
                        Html.hidden(ACTION, "connect")
                                + "<input type=\"text\" name=\"address\" placeholder=\"host:port\""
                                + " aria-label=\"A server's address\" title=\"A server's address and TCP port\"> ",
                        "Connect to address",
                        false))
                .append(Html.form(
                        SERVERS,
                        csrf,
                        Html.hidden(ACTION, "add")
                                + "<input type=\"text\" name=\"address\" required placeholder=\"host:port\""
                                + " aria-label=\"A server to add\"> ",
                        "Add server",
                        false))
                .append(Html.form(
                        SERVERS,
                        csrf,
                        Html.hidden(ACTION, "list")
                                + "<input type=\"text\" name=\"url\" required size=\"30\""
                                + " placeholder=\"https://.../server.met\" aria-label=\"The address of a server.met\"> ",
                        "Update server.met from URL",
                        false))
                .append("</div>");
        if (servers.isEmpty()) {
            body.append(Html.empty("No servers. Add one, or a server.met from its address."));
            return Html.page(frame, "Network", "Servers: 0", body.toString(), 0);
        }
        body.append("<div class=\"table\"><table><thead><tr><th>Server Name</th><th>IP</th><th>Description</th>")
                .append("<th class=\"num\">Ping</th><th class=\"num\">Users</th><th class=\"num\">Max Users</th>")
                .append("<th class=\"num\">Files</th><th>Priority</th><th class=\"num\">Failed</th><th>Static</th>")
                .append("<th class=\"num\">Soft File Limit</th><th class=\"num\">Low ID</th><th>Obfuscation</th>")
                .append("<th></th></tr></thead><tbody>");
        for (ServerInfo s : servers) {
            boolean connected = current.filter(s.address()::equals).isPresent();
            String address = Html.hidden(ADDRESS, s.address());
            body.append(connected ? "<tr class=\"current\">" : "<tr>")
                    .append("<td class=\"name\">")
                    .append(Html.text(s.name()))
                    .append(connected ? " " + Html.chip("Connected", Html.Tone.OK, Optional.empty()) : "")
                    .append("</td><td class=\"mono\">")
                    .append(Html.text(s.address()))
                    .append("</td><td>")
                    .append(Html.text(s.description()))
                    .append("</td><td class=\"num\">")
                    .append(known(s.ping()))
                    .append("</td><td class=\"num\">")
                    .append(known(s.users()))
                    .append("</td><td class=\"num\">")
                    .append(known(s.maxUsers()))
                    .append("</td><td class=\"num\">")
                    .append(known(s.files()))
                    .append("</td><td class=\"nowrap\">")
                    .append(Html.form(
                            SERVERS,
                            csrf,
                            Html.hidden(ACTION, "priority") + address + priorities(s.priority()),
                            "Set",
                            "small"))
                    .append("</td><td class=\"num\">")
                    .append(known(s.failed()))
                    .append("</td><td>")
                    .append(Html.form(
                            SERVERS,
                            csrf,
                            Html.hidden(ACTION, s.isStatic() ? "unstatic" : "static") + address,
                            s.isStatic() ? "Yes" : "No",
                            "small"))
                    .append("</td><td class=\"num\">")
                    .append(known(s.softFiles()))
                    .append("</td><td class=\"num\">")
                    .append(known(s.lowIdUsers()))
                    .append("</td><td>")
                    .append(s.supportsObfuscation() ? "Yes" : "No")
                    .append("</td><td class=\"actions\">")
                    .append(
                            connected
                                    ? Html.form(SERVERS, csrf, Html.hidden(ACTION, "disconnect"), "Disconnect", "small")
                                    : Html.form(
                                            SERVERS,
                                            csrf,
                                            Html.hidden(ACTION, "connect") + address,
                                            "Connect",
                                            "small"))
                    .append(' ')
                    .append(Html.form(SERVERS, csrf, Html.hidden(ACTION, "remove") + address, "Remove", "small danger"))
                    .append("</td></tr>");
        }
        body.append("</tbody></table></div>");
        return Html.page(frame, "Network", "Servers: " + servers.size(), body.toString(), 0);
    }

    String kad(Html.Frame frame) {
        Optional<KadInfo> kad = this.calls.await(this.calls.engine().kad());
        String csrf = frame.csrf();
        StringBuilder body = new StringBuilder(8192);
        body.append(head(frame, KAD));
        if (kad.isEmpty()) {
            body.append(Html.empty("Kad is off. Connect it on its card above."));
            return Html.page(frame, "Network", "Kad: off", body.toString(), 0);
        }
        KadInfo k = kad.get();
        body.append("<div class=\"split\"><div>");
        List<KadSearchInfo> searches =
                this.calls.maybe(this.calls.engine().kadSearches()).orElse(List.of());
        body.append("<h2>Current Searches <span class=\"count\">")
                .append(searches.size())
                .append("</span></h2>");
        if (searches.isEmpty()) {
            body.append(Html.empty("No Kad search runs now."));
        } else {
            body.append("<div class=\"table\"><table><thead><tr><th>KadID</th><th>Type</th>")
                    .append("<th class=\"num\">Answers</th></tr></thead><tbody>");
            for (KadSearchInfo s : searches) {
                body.append("<tr><td class=\"mono\">")
                        .append(Html.text(s.target()))
                        .append("</td><td>")
                        .append(searchKind(s.kind()))
                        .append("</td><td class=\"num\">")
                        .append(s.answers())
                        .append("</td></tr>");
            }
            body.append("</tbody></table></div>");
        }
        List<KadContactInfo> contacts = this.calls.maybe(this.calls.engine().kadContacts()).orElse(List.of()).stream()
                .sorted(Comparator.comparing(KadContactInfo::distance))
                .toList();
        body.append("<h2>Contacts <span class=\"count\">")
                .append(contacts.size())
                .append("</span></h2>");
        if (contacts.isEmpty()) {
            body.append(Html.empty("No contacts yet."));
        } else {
            body.append("<div class=\"table\"><table><thead><tr><th>ID</th><th class=\"num\">Type</th>")
                    .append("<th>Distance</th><th>IP</th><th class=\"num\">Kad</th></tr></thead><tbody>");
            for (KadContactInfo c : contacts.subList(0, Math.min(CONTACTS_SHOWN, contacts.size()))) {
                body.append("<tr><td class=\"mono\">")
                        .append(Html.text(c.id()))
                        .append("</td><td class=\"num\">")
                        .append(c.type())
                        .append("</td><td class=\"mono\">")
                        .append(Html.text(c.distance()))
                        .append("</td><td class=\"mono\">")
                        .append(Html.text(c.address()))
                        .append("</td><td class=\"num\">")
                        .append(c.version())
                        .append("</td></tr>");
            }
            body.append("</tbody></table></div>");
            if (contacts.size() > CONTACTS_SHOWN) {
                body.append("<p class=\"count\">The ")
                        .append(CONTACTS_SHOWN)
                        .append(" closest of ")
                        .append(contacts.size())
                        .append(".</p>");
            }
        }
        body.append("</div><aside class=\"panel\" aria-label=\"Kad\"><h2>Kad</h2><dl class=\"facts\">");
        fact(body, "State", !k.connected() ? "Not connected" : k.firewalled() ? "Firewalled" : "Open");
        fact(body, "UDP state", !k.connected() ? "-" : udpState(k));
        fact(body, "Buddy", buddy(k.buddy()));
        fact(body, "Contacts", Html.count(k.contacts()));
        fact(body, "Verified contacts", Html.count(k.verifiedContacts()));
        fact(body, "Users", Html.count(k.users()));
        fact(body, "Files", Html.count(k.files()));
        fact(body, "KadID", "<span class=\"mono\">" + Html.text(k.id()) + "</span>");
        fact(body, "IP:Port", Html.text((k.publicIp().isEmpty() ? "unknown" : k.publicIp()) + ":" + k.udpPort()));
        body.append("</dl><h2>Join Kad</h2><p>")
                .append(Html.form(
                        KAD,
                        csrf,
                        Html.hidden(ACTION, "bootstrap")
                                + "<input type=\"text\" name=\"address\" required placeholder=\"host:port\""
                                + " aria-label=\"A Kad node\" title=\"A client you know: its address and UDP port\"> ",
                        "Bootstrap",
                        false))
                .append("</p><p>")
                .append(Html.form(
                        KAD,
                        csrf,
                        Html.hidden(ACTION, "nodes")
                                + "<input type=\"text\" name=\"url\" required placeholder=\"https://.../nodes.dat\""
                                + " aria-label=\"The address of a nodes.dat\"> ",
                        "Download nodes.dat",
                        false))
                .append("</p></aside></div>");
        return Html.page(frame, "Network", Html.kadWords(kad), body.toString(), 0);
    }

    String log(Html.Frame frame, Map<String, String> query) {
        List<RecentLog.Entry> lines = this.log.after(0);
        Map<Level, Integer> counts = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            counts.put(level, (int)
                    lines.stream().filter(e -> level.takes(e.line().level())).count());
        }
        Level chosen = Level.of(query.getOrDefault("level", ""));
        StringBuilder body = new StringBuilder(4096 + lines.size() * 160);
        body.append(head(frame, LOG)).append("<div class=\"toolbar\"><nav class=\"filters\" aria-label=\"Levels\">");
        counts.forEach((level, n) -> body.append("<a href=\"log")
                .append(level == Level.ALL ? "" : "?level=" + level.name().toLowerCase(Locale.ROOT))
                .append(level == chosen ? "\" aria-current=\"true\">" : "\">")
                .append(level.words)
                .append(" <span class=\"count\">")
                .append(n)
                .append("</span></a>"));
        body.append("</nav><span class=\"grow\"></span><a href=\"log")
                .append(chosen == Level.ALL ? "" : "?level=" + chosen.name().toLowerCase(Locale.ROOT))
                .append("\">Reload</a></div>");
        List<RecentLog.Entry> shown = lines.reversed().stream()
                .filter(e -> chosen.takes(e.line().level()))
                .toList();
        if (shown.isEmpty()) {
            body.append(Html.empty("Nothing logged yet."));
        } else {
            body.append("<div class=\"table\"><table><thead><tr><th>Time</th><th>Level</th><th>Message</th>")
                    .append("</tr></thead><tbody>");
            ZoneId zone = ZoneId.systemDefault();
            for (RecentLog.Entry e : shown) {
                LogLine line = e.line();
                body.append("<tr><td class=\"nowrap\">")
                        .append(LOG_TIME.format(line.time().atZone(zone)))
                        .append("</td><td>")
                        .append(level(line.level()))
                        .append("</td><td class=\"name\">")
                        .append(Html.text(line.message()))
                        .append("</td></tr>");
            }
            body.append("</tbody></table></div>");
        }
        return Html.page(frame, "Network", "Log: " + lines.size() + " lines", body.toString(), 0);
    }

    // The cards of the networks and our ports, then the views of the network.
    private String head(Html.Frame frame, String view) {
        String csrf = frame.csrf();
        Preferences preferences = this.calls.engine().preferences();
        StringBuilder out = new StringBuilder(4096).append("<div class=\"cards\">");
        Optional<EngineStatus> status = frame.status();
        Optional<ServerInfo> server = status.flatMap(EngineStatus::server);
        if (server.isPresent()) {
            boolean low = status.get().lowId();
            out.append(Html.card(
                    "eD2K",
                    Icons.SERVER,
                    low ? "Not reachable from outside (Low ID)" : "Reachable from outside (High ID)",
                    low ? Html.Tone.WARN : Html.Tone.OK,
                    Html.text(server.get().name()) + "<br>"
                            + Html.count(server.get().users()) + " users · "
                            + Html.count(server.get().files()) + " files",
                    Html.form(SERVERS, csrf, Html.hidden(ACTION, "disconnect"), "Disconnect", "small")));
        } else {
            out.append(Html.card(
                    "eD2K",
                    Icons.SERVER,
                    "Not connected",
                    Html.Tone.NONE,
                    "",
                    Html.form(SERVERS, csrf, Html.hidden(ACTION, "connect"), "Connect", "small")));
        }
        Optional<KadInfo> kad = status.flatMap(EngineStatus::kad);
        String kadAction = Html.form(
                KAD,
                csrf,
                Html.hidden(ACTION, kad.isPresent() ? "disconnect" : "connect"),
                kad.isPresent() ? "Disconnect" : "Connect",
                "small");
        if (kad.isEmpty()) {
            out.append(Html.card("Kad", Icons.KAD, "Off", Html.Tone.NONE, "", kadAction));
        } else if (!kad.get().connected()) {
            out.append(Html.card("Kad", Icons.KAD, "Connecting", Html.Tone.BUSY, "", kadAction));
        } else {
            KadInfo k = kad.get();
            String udp = k.firewalled() && (k.udpVerified() || k.udpFirewalled())
                    ? "<br>"
                            + (k.udpFirewalled() ? "UDP firewalled too" : "UDP open: firewalled clients still reach us")
                    : "";
            out.append(Html.card(
                    "Kad",
                    Icons.KAD,
                    k.firewalled() ? "Not reachable from outside (Firewalled)" : "Reachable from outside (Open)",
                    k.firewalled() ? Html.Tone.WARN : Html.Tone.OK,
                    "Contacts: " + Html.count(k.contacts()) + udp,
                    kadAction + " "
                            + Html.form(KAD, csrf, Html.hidden(ACTION, "recheck"), "Recheck firewall", "small")));
        }
        out.append(ports(csrf, preferences, status, kad.isPresent())).append("</div>");
        out.append("<div class=\"toolbar\">")
                .append(Html.segments(
                        "Network",
                        List.of(
                                Map.entry("servers", Icons.SERVER.svg() + " Servers"),
                                Map.entry("kad", Icons.KAD.svg() + " Kad"),
                                Map.entry("log", Icons.LOG.svg() + " Log")),
                        Html.relative(view)))
                .append("</div>");
        return out.toString();
    }

    // The ports as set, how the router and the outside see them, our IPv6 address and the way through routers.
    private String ports(String csrf, Preferences preferences, Optional<EngineStatus> status, boolean kadRuns) {
        StringBuilder detail = new StringBuilder("UPnP: ").append(preferences.upnp() ? "Yes" : "No");
        status.flatMap(EngineStatus::publicIpv6)
                .ifPresent(ip -> detail.append("<br>IPv6: <span class=\"mono\">")
                        .append(Html.text(ip))
                        .append("</span>"));
        detail.append("<br>NAT traversal (uTP, QUIC): ")
                .append(preferences.routerTraversal() && kadRuns ? "Yes" : "No");
        Optional<PortTest> test =
                this.calls.maybe(this.calls.engine().portTest()).flatMap(t -> t);
        test.ifPresent(t -> detail.append("<br>Test: TCP ")
                .append(reach(t.tcp()))
                .append(", UDP ")
                .append(reach(t.udp())));
        String numbers =
                "TCP " + preferences.tcpPort() + (preferences.udpEnabled() ? " · UDP " + preferences.udpPort() : "");
        return Html.card(
                "Ports",
                Icons.PORT_TEST,
                numbers,
                Html.Tone.NONE,
                detail.toString(),
                Html.form(SERVERS, csrf, Html.hidden(ACTION, "test-ports"), "Test the ports", "small"));
    }

    /** Whether a test of the ports runs, so that the page loads again until its outcome is known. */
    boolean testing() {
        return this.calls
                .maybe(this.calls.engine().portTest())
                .flatMap(t -> t)
                .filter(t -> !t.done())
                .isPresent();
    }

    private static String reach(PortTest.Reach reach) {
        return switch (reach) {
            case TESTING -> "testing…";
            case OPEN -> "open";
            case CLOSED -> "closed";
            case UNKNOWN -> "unknown";
        };
    }

    /** What a posted form of the servers asks; the page to show next. */
    @SuppressWarnings("FutureReturnValueIgnored")
    String actOnServers(Map<String, String> form) {
        String address = form.getOrDefault(ADDRESS, "").strip();
        switch (form.getOrDefault(ACTION, "")) {
            case "connect" ->
                this.calls.await(this.calls
                        .engine()
                        .connect(address.isEmpty() ? Optional.empty() : Optional.of(ServerAddress.parse(address))));
            case "disconnect" -> this.calls.await(this.calls.engine().disconnect());
            case "add" -> {
                if (!this.calls.await(this.calls.engine().addServer(ServerAddress.parse(address)))) {
                    throw new IllegalArgumentException("That server is already listed.");
                }
            }
            case "remove" -> this.calls.await(this.calls.engine().removeServer(ServerAddress.parse(address)));
            case "priority" ->
                this.calls.await(this.calls
                        .engine()
                        .setServerPriority(
                                ServerAddress.parse(address),
                                ServerInfo.Priority.valueOf(
                                        form.getOrDefault("priority", "").toUpperCase(Locale.ROOT))));
            case "static" -> this.calls.await(this.calls.engine().setStaticServer(ServerAddress.parse(address), true));
            case "unstatic" ->
                this.calls.await(this.calls.engine().setStaticServer(ServerAddress.parse(address), false));
            case "list" -> this.calls.await(this.calls.engine().addServers(web(form.getOrDefault("url", ""))));
            // Its outcomes show on the ports card as they come.
            case "test-ports" -> this.calls.engine().testPorts();
            default -> throw new IllegalArgumentException("Unknown action.");
        }
        return SERVERS;
    }

    /** What a posted form of Kad asks; the page to show next. */
    String actOnKad(Map<String, String> form) {
        switch (form.getOrDefault(ACTION, "")) {
            case "connect" -> this.calls.await(this.calls.engine().connectKad());
            case "disconnect" -> this.calls.await(this.calls.engine().disconnectKad());
            case "recheck" -> {
                if (!this.calls.await(this.calls.engine().recheckKadFirewall())) {
                    throw new IllegalArgumentException("Kad is not connected.");
                }
            }
            case "bootstrap" ->
                this.calls.await(this.calls
                        .engine()
                        .kadBootstrap(ServerAddress.parse(
                                form.getOrDefault(ADDRESS, "").strip())));
            case "nodes" -> this.calls.await(this.calls.engine().addKadContacts(web(form.getOrDefault("url", ""))));
            default -> throw new IllegalArgumentException("Unknown action.");
        }
        return KAD;
    }

    // An address on the web, http or https only.
    private static URI web(String text) {
        URI uri = URI.create(text.strip());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an http or https address: " + text);
        }
        return uri;
    }

    private static String priorities(ServerInfo.Priority current) {
        StringBuilder select = new StringBuilder("<select name=\"priority\" aria-label=\"Priority\">");
        for (ServerInfo.Priority p :
                List.of(ServerInfo.Priority.LOW, ServerInfo.Priority.NORMAL, ServerInfo.Priority.HIGH)) {
            String name = p.name().toLowerCase(Locale.ROOT);
            select.append("<option value=\"")
                    .append(name)
                    .append(p == current ? "\" selected>" : "\">")
                    .append(Character.toUpperCase(name.charAt(0)))
                    .append(name.substring(1))
                    .append("</option>");
        }
        return select.append("</select> ").toString();
    }

    // Unknown as nothing rather than 0.
    private static String known(long value) {
        return value <= 0 ? "" : Html.count(value);
    }

    private static void fact(StringBuilder out, String term, String value) {
        out.append("<dt>").append(term).append("</dt><dd>").append(value).append("</dd>");
    }

    private static String udpState(KadInfo k) {
        if (k.udpFirewalled()) {
            return "Firewalled";
        }
        return k.udpVerified() ? "Open" : "Unknown";
    }

    private static String buddy(KadInfo.Buddy buddy) {
        return switch (buddy) {
            case NONE -> "None";
            case CONNECTING -> "Connecting";
            case CONNECTED -> "Connected";
            case SERVING -> "Buddy of a firewalled client";
        };
    }

    private static String searchKind(KadSearchInfo.Kind kind) {
        return switch (kind) {
            case NODE -> "Node Lookup";
            case KEYWORD -> "Search Keywords";
            case SOURCES -> "Search Sources";
            case NOTES -> "Notes";
            case STORE_KEYWORD -> "Store Keyword";
            case STORE_SOURCE -> "Store File";
            case STORE_NOTES -> "Store Notes";
        };
    }

    private static String level(LogLine.Level level) {
        return switch (level) {
            case ERROR -> Html.chip("Error", Html.Tone.ERROR, Optional.empty());
            case WARNING -> Html.chip("Warning", Html.Tone.WARN, Optional.empty());
            case SUCCESS -> Html.chip("Success", Html.Tone.OK, Optional.empty());
            case INFO -> "Info";
            case DEBUG -> "Debug";
        };
    }

    /** The level chips of the log: info takes success and debug too, as the desktop app's. */
    private enum Level {
        ALL("All"),
        ERROR("Error"),
        WARNING("Warning"),
        INFO("Info");

        private final String words;

        Level(String words) {
            this.words = words;
        }

        boolean takes(LogLine.Level level) {
            return switch (this) {
                case ALL -> true;
                case ERROR -> level == LogLine.Level.ERROR;
                case WARNING -> level == LogLine.Level.WARNING;
                case INFO ->
                    level == LogLine.Level.INFO || level == LogLine.Level.SUCCESS || level == LogLine.Level.DEBUG;
            };
        }

        static Level of(String key) {
            for (Level level : values()) {
                if (level.name().equalsIgnoreCase(key)) {
                    return level;
                }
            }
            return ALL;
        }
    }
}
