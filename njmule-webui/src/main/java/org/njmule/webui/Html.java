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

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.EngineStatus;
import org.njmule.core.api.KadInfo;
import org.njmule.core.api.NjMuleInfo;

/**
 * The pages' frame, as the desktop app's: the places in a sidebar, a top bar with the page's title, one field to search
 * or paste links, the rates and the state of the networks. Escaping too: every text that is not ours goes through
 * {@link #text}.
 */
final class Html {

    // & < > " ' as &amp; &lt; &gt; &quot; &#39;, and the text itself when none is there.
    private static final Escaper ESCAPER = HtmlEscapers.htmlEscaper();

    // As long as the field of links on the transfers page: a search expression is far shorter.
    private static final int QUICK_LENGTH = 65_536;

    /** The places of the sidebar, in the desktop app's groups: what one does first, then the network, the settings. */
    enum Place {
        TRANSFERS("transfers", "Transfers", Icons.TRANSFERS, 0),
        SEARCH("search", "Search", Icons.SEARCH, 0),
        SHARED("shared", "Shared files", Icons.SHARED, 0),
        NETWORK("servers", "Network", Icons.NETWORK, 1),
        STATISTICS("statistics", "Statistics", Icons.STATISTICS, 1),
        SETTINGS("settings", "Settings", Icons.OPTIONS, 2);

        private final String path;
        private final String title;
        private final Icons icon;
        private final int group;

        Place(String path, String title, Icons icon, int group) {
            this.path = path;
            this.title = title;
            this.icon = icon;
            this.group = group;
        }
    }

    /**
     * What the frame of a session's page shows: its form token, the place it belongs to, the engine's state if known,
     * and a notice above every page (HTML, empty for none).
     */
    record Frame(String csrf, Place place, Optional<EngineStatus> status, String notice) {}

    /** The tone of a state chip: none, fine, a warning or something under way. */
    enum Tone {
        NONE(""),
        OK("ok"),
        WARN("warn"),
        BUSY("busy"),
        ERROR("error");

        private final String css;

        Tone(String css) {
            this.css = css;
        }
    }

    /** The style sheet of every page, from the resources. */
    static final String STYLE = new String(resource("style.css"), StandardCharsets.UTF_8);

    // The browser icons, from the branding at build time; one missing answers 404.
    private static final Map<String, byte[]> ICONS =
            Map.of("/favicon.svg", resource("favicon.svg"), "/favicon.png", resource("favicon.png"));

    private Html() {}

    /** The icon at {@code path}, if it is one. */
    static Optional<byte[]> icon(String path) {
        return Optional.ofNullable(ICONS.get(path)).filter(b -> b.length > 0).map(byte[]::clone);
    }

    private static byte[] resource(String name) {
        try (InputStream in = Html.class.getResourceAsStream(name)) {
            return in == null ? new byte[0] : in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** {@code value} safe in element content and in quoted attributes. */
    static String text(String value) {
        return ESCAPER.escape(value);
    }

    /** A page outside a session, such as the login: no places, no state. */
    static String bare(String title, String body) {
        return head(title, 0) + "<main class=\"login\"><a class=\"brand\" href=\"./\">"
                + "<img src=\"favicon.svg\" alt=\"\" width=\"28\" height=\"28\"><span>" + text(NjMuleInfo.NAME)
                + "</span></a><h1>" + text(title) + "</h1>" + body + "</main></body></html>";
    }

    /**
     * A session's page: the places, the top bar with {@code title} and a one-line {@code summary}, then {@code body};
     * the browser loads it again every {@code refresh} seconds, if more than 0.
     */
    static String page(Frame frame, String title, String summary, String body, int refresh) {
        StringBuilder out = new StringBuilder(8192 + body.length());
        out.append(head(title, refresh))
                .append("<a class=\"skip\" href=\"#main\">Skip to the content</a><div class=\"app\">")
                .append(sidebar(frame))
                .append("<div class=\"frame\"><header class=\"topbar\"><div class=\"heading\"><h1>")
                .append(text(title))
                .append("</h1>");
        if (!summary.isEmpty()) {
            out.append("<p>").append(text(summary)).append("</p>");
        }
        out.append("</div>")
                .append(quick(frame.csrf()))
                .append(frame.status().map(Html::rates).orElse(""))
                .append(frame.status().map(Html::networkChip).orElse(""))
                .append("</header><main id=\"main\">")
                .append(frame.notice())
                .append(body)
                .append("</main></div></div></body></html>");
        return out.toString();
    }

    private static String head(String title, int refresh) {
        StringBuilder out = new StringBuilder(512);
        out.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        if (refresh > 0) {
            out.append("<meta http-equiv=\"refresh\" content=\"")
                    .append(refresh)
                    .append("\">");
        }
        return out.append("<link rel=\"icon\" href=\"favicon.png\" sizes=\"32x32\">")
                .append("<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">")
                .append("<title>")
                .append(text(title))
                .append(" - njMule</title><link rel=\"stylesheet\" href=\"style.css\"></head><body>")
                .toString();
    }

    private static String sidebar(Frame frame) {
        StringBuilder out = new StringBuilder(4096);
        out.append("<aside class=\"sidebar\"><a class=\"brand\" href=\"transfers\">")
                .append("<img src=\"favicon.svg\" alt=\"\" width=\"28\" height=\"28\"><span>")
                .append(text(NjMuleInfo.NAME))
                .append("</span></a><nav aria-label=\"Places\">");
        int group = -1;
        for (Place place : Place.values()) {
            if (place.group != group) {
                out.append(group < 0 ? "<ul>" : "</ul><ul>");
                group = place.group;
            }
            out.append("<li><a class=\"place\" href=\"")
                    .append(place.path)
                    .append(place == frame.place() ? "\" aria-current=\"page\">" : "\">")
                    .append(place.icon.svg())
                    .append("<span>")
                    .append(place.title)
                    .append("</span>")
                    .append(marker(place, frame.status()))
                    .append("</a></li>");
        }
        return out.append("<li><form method=\"post\" action=\"logout\"><input type=\"hidden\" name=\"csrf\" value=\"")
                .append(text(frame.csrf()))
                .append("\"><button class=\"place\">")
                .append(Icons.EXIT.svg())
                .append("<span>Log out</span></button></form></li></ul></nav><p class=\"version\">")
                .append(text(NjMuleInfo.NAME + " " + NjMuleInfo.version()))
                .append("</p></aside>")
                .toString();
    }

    // The downloads beside the transfers, and the state of the networks beside the network, in a dot of its tone.
    private static String marker(Place place, Optional<EngineStatus> status) {
        if (status.isEmpty()) {
            return "";
        }
        EngineStatus s = status.get();
        return switch (place) {
            case TRANSFERS -> s.downloads() > 0 ? "<span class=\"badge\">" + s.downloads() + "</span>" : "";
            case NETWORK ->
                "<span class=\"dot " + networkTone(s).css + "\" title=\"" + text(networkWords(s)) + "\"></span>";
            default -> "";
        };
    }

    private static String quick(String csrf) {
        return "<form class=\"quick\" role=\"search\" method=\"post\" action=\"search\">"
                + "<input type=\"hidden\" name=\"csrf\" value=\"" + text(csrf) + "\">"
                + "<input type=\"hidden\" name=\"scope\" value=\"AUTOMATIC\">"
                + "<input type=\"search\" name=\"query\" required maxlength=\"" + QUICK_LENGTH + "\""
                + " placeholder=\"Search the network or paste an eD2K link\""
                + " aria-label=\"Search the network or paste an eD2K link\"></form>";
    }

    private static String rates(EngineStatus s) {
        return "<p class=\"rates\"><span title=\"Download rate\">" + Icons.ARROW_DOWN.svg() + " "
                + rate(s.downloadBytesPerSecond()) + "</span><span title=\"Upload rate\">" + Icons.ARROW_UP.svg()
                + " " + rate(s.uploadBytesPerSecond()) + "</span></p>";
    }

    private static String networkChip(EngineStatus s) {
        Tone tone = networkTone(s);
        return "<a class=\"chip " + tone.css + "\" href=\"servers\">" + (tone == Tone.NONE ? Icons.CLOUD_OFF.svg() : "")
                + text(networkWords(s)) + "</a>";
    }

    /** The networks in a few words: the server's ID, then Kad, as the desktop app's network control says it. */
    static String networkWords(EngineStatus s) {
        List<String> words = new ArrayList<>(3);
        if (s.server().isPresent()) {
            words.add("Connected");
            words.add(s.lowId() ? "Low ID" : "High ID");
        } else {
            words.add("eD2K not connected");
        }
        words.add(kadWords(s.kad()));
        return String.join(" · ", words);
    }

    static String kadWords(Optional<KadInfo> kad) {
        if (kad.isEmpty()) {
            return "Kad off";
        }
        if (!kad.get().connected()) {
            return "Kad connecting";
        }
        return kad.get().firewalled() ? "Kad firewalled" : "Kad open";
    }

    // Reachable on either network is fine; connected but firewalled everywhere is a warning.
    private static Tone networkTone(EngineStatus s) {
        boolean kadOpen =
                s.kad().filter(KadInfo::connected).filter(k -> !k.firewalled()).isPresent();
        boolean kadUp = s.kad().filter(KadInfo::connected).isPresent();
        if ((s.server().isPresent() && !s.lowId()) || kadOpen) {
            return Tone.OK;
        }
        if (s.server().isPresent() || kadUp) {
            return Tone.WARN;
        }
        return s.kad().isPresent() ? Tone.BUSY : Tone.NONE;
    }

    /** A state chip in {@code tone}, with {@code icon} if any. */
    static String chip(String words, Tone tone, Optional<Icons> icon) {
        return "<span class=\"chip " + tone.css + "\">" + icon.map(Icons::svg).orElse("") + text(words) + "</span>";
    }

    /** Segments that lead to {@code links} (path to words), the one at {@code current} marked. */
    static String segments(String label, List<Map.Entry<String, String>> links, String current) {
        StringBuilder out = new StringBuilder(512)
                .append("<nav class=\"segments\" aria-label=\"")
                .append(text(label))
                .append("\">");
        for (Map.Entry<String, String> link : links) {
            out.append("<a href=\"")
                    .append(text(link.getKey()))
                    .append(link.getKey().equals(current) ? "\" aria-current=\"page\">" : "\">")
                    .append(link.getValue())
                    .append("</a>");
        }
        return out.append("</nav>").toString();
    }

    /** A bar of {@code fraction} (0 to 1) with its percent, done or halted in its own colour. */
    static String progress(double fraction, String kind) {
        int percent = (int) Math.floor(Math.max(0, Math.min(1, fraction)) * 100);
        return "<progress" + (kind.isEmpty() ? "" : " class=\"" + kind + "\"") + " max=\"100\" value=\"" + percent
                + "\"></progress> <span class=\"percent\">" + percent + " %</span>";
    }

    /** A card of a state: its title and icon, the state in words, a detail, and forms to act on it. */
    static String card(String title, Icons icon, String state, Tone tone, String detail, String actions) {
        return "<section class=\"card " + tone.css + "\"><h3>" + icon.svg() + text(title)
                + "</h3><span class=\"state\">" + text(state) + "</span>"
                + (detail.isEmpty() ? "" : "<p>" + detail + "</p>") + actions + "</section>";
    }

    /** A tile of one number and what it counts. */
    static String tile(String value, String words) {
        return "<div class=\"tile\"><b>" + text(value) + "</b><span>" + text(words) + "</span></div>";
    }

    /** An empty table's place: what is missing, then what to do. */
    static String empty(String words) {
        return "<p class=\"empty\">" + words + "</p>";
    }

    /**
     * {@code path}, a page of the interface, relative to the page that links to it: every page sits at the top, so the
     * interface works under whatever path a reverse proxy serves it.
     */
    static String relative(String path) {
        if (path.equals("/")) {
            return "./";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** A form posting {@code fields} (already escaped hidden inputs) with one button. */
    static String form(String action, String csrf, String fields, String button, boolean main) {
        return form(action, csrf, fields, button, main ? "main" : "");
    }

    /** A form posting {@code fields} with one button of the style {@code kind} ("main", "danger", "small" or none). */
    static String form(String action, String csrf, String fields, String button, String kind) {
        return "<form class=\"inline\" method=\"post\" action=\""
                + text(relative(action))
                + "\"><input type=\"hidden\" name=\"csrf\" value=\""
                + text(csrf)
                + "\">"
                + fields
                + "<button"
                + (kind.isEmpty() ? "" : " class=\"" + kind + "\"")
                + ">"
                + text(button)
                + "</button></form>";
    }

    static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + text(name) + "\" value=\"" + text(value) + "\">";
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    // As the desktop interface: KB/s, then MB/s and GB/s from a thousand of the smaller unit on.
    static String rate(long bytesPerSecond) {
        double value = Math.max(0, bytesPerSecond) / 1024.0;
        if (value < 999.95) {
            return String.format(Locale.ROOT, "%.1f KB/s", value);
        }
        String unit = "MB/s";
        value /= 1024;
        if (value >= 999.95) {
            unit = "GB/s";
            value /= 1024;
        }
        return String.format(Locale.ROOT, value < 99.995 ? "%.2f %s" : "%.1f %s", value, unit);
    }

    /** A time in its two largest units: "45 s", "12 min 5 s", "3 h 20 min", "2 d 4 h". */
    static String duration(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min " + s % 60 + " s";
        }
        if (s < 86_400) {
            return s / 3600 + " h " + s % 3600 / 60 + " min";
        }
        return s / 86_400 + " d " + s % 86_400 / 3600 + " h";
    }

    /** A count with thin separators of thousands, as figures read best. */
    static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
