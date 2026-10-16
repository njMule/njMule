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
import java.util.Locale;
import org.njmule.core.api.NjMuleInfo;

/** The pages' frame and escaping: every text that is not ours goes through {@link #text}. */
final class Html {

    // & < > " ' as &amp; &lt; &gt; &quot; &#39;, and the text itself when none is there.
    private static final Escaper ESCAPER = HtmlEscapers.htmlEscaper();

    static final String STYLE = """
            :root { color-scheme: light dark; --bg: #f4f6fb; --fg: #2e3440; --muted: #4c566a;
              --card: #ffffff; --accent: #24427a; --border: #d8dee9; }
            @media (prefers-color-scheme: dark) {
              :root { --bg: #2e3440; --fg: #eceff4; --muted: #d8dee9; --card: #3b4252;
                --accent: #88c0d0; --border: #4c566a; } }
            body { margin: 0; font: 15px system-ui, sans-serif; background: var(--bg); color: var(--fg); }
            header { display: flex; gap: 18px; align-items: center; padding: 10px 18px;
              background: var(--card); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
            header strong { color: var(--accent); }
            nav a { margin-right: 14px; color: var(--fg); text-decoration: none; }
            nav a:hover { text-decoration: underline; }
            main { padding: 18px; max-width: 1100px; }
            table { border-collapse: collapse; width: 100%; background: var(--card); }
            th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--border); }
            th { color: var(--muted); font-weight: 600; }
            form.inline { display: inline; }
            input[type=text], input[type=password], select { padding: 6px; border: 1px solid var(--border);
              border-radius: 6px; background: var(--card); color: var(--fg); }
            button { padding: 6px 12px; border: 1px solid var(--border); border-radius: 6px;
              background: var(--card); color: var(--fg); cursor: pointer; }
            button.main { background: var(--accent); color: var(--bg); border-color: var(--accent); }
            .cards { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
            .card { background: var(--card); border: 1px solid var(--border); border-radius: 8px;
              padding: 10px 14px; min-width: 160px; }
            .card b { display: block; font-size: 1.2em; }
            .error { color: #bf616a; }
            progress { width: 120px; height: 10px; accent-color: #a3be8c; vertical-align: middle; }
            @media (max-width: 640px) {
              main { padding: 10px; }
              table { display: block; overflow-x: auto; white-space: nowrap; }
              nav a { display: inline-block; margin: 4px 12px 4px 0; } }
            """;

    private Html() {}

    /** {@code value} safe in element content and in quoted attributes. */
    static String text(String value) {
        return ESCAPER.escape(value);
    }

    /** A whole page; {@code csrf} is empty on the login page, which has no navigation. */
    static String page(String title, String csrf, String body) {
        StringBuilder out = new StringBuilder(4096);
        out.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>")
                .append(text(title))
                .append(" - njMule</title><link rel=\"stylesheet\"" + " href=\"/style.css\"></head><body><header>")
                .append("<strong>")
                .append(text(NjMuleInfo.NAME + " " + NjMuleInfo.version()))
                .append("</strong>");
        if (!csrf.isEmpty()) {
            out.append("<nav><a href=\"/\">Status</a><a href=\"/transfers\">Transfers</a>")
                    .append("<a href=\"/search\">Search</a><a href=\"/servers\">Servers</a>")
                    .append("<a href=\"/kad\">Kad</a><a href=\"/shared\">Shared files</a>")
                    .append("<a href=\"/log\">Log</a></nav>")
                    .append(form("/logout", csrf, "", "Log out", false));
        }
        out.append("</header><main><h1>").append(text(title)).append("</h1>").append(body);
        out.append("</main></body></html>");
        return out.toString();
    }

    /** A form posting {@code fields} (already escaped hidden inputs) with one button. */
    static String form(String action, String csrf, String fields, String button, boolean main) {
        return "<form class=\"inline\" method=\"post\" action=\""
                + text(action)
                + "\"><input type=\"hidden\" name=\"csrf\" value=\""
                + text(csrf)
                + "\">"
                + fields
                + "<button"
                + (main ? " class=\"main\"" : "")
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
}
