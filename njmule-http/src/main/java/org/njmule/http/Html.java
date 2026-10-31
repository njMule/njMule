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
package org.njmule.http;

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.NjMuleInfo;

/**
 * The one page the server writes itself, the login, which runs no script; and escaping: every text that is not ours
 * goes through {@link #text}.
 */
final class Html {

    // & < > " ' as &amp; &lt; &gt; &quot; &#39;, and the text itself when none is there.
    private static final Escaper ESCAPER = HtmlEscapers.htmlEscaper();

    /** The style sheet of the login page, from the resources. */
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

    /** A page outside a session, such as the login. */
    static String bare(String title, String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<link rel=\"icon\" href=\"favicon.png\" sizes=\"32x32\">"
                + "<link rel=\"icon\" href=\"favicon.svg\" type=\"image/svg+xml\">"
                + "<title>" + text(title) + " - njMule</title><link rel=\"stylesheet\" href=\"style.css\"></head><body>"
                + "<main class=\"login\"><a class=\"brand\" href=\"./\">"
                + "<img src=\"favicon.svg\" alt=\"\" width=\"28\" height=\"28\"><span>" + text(NjMuleInfo.NAME)
                + "</span></a><h1>" + text(title) + "</h1>" + body + "</main></body></html>";
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
}
