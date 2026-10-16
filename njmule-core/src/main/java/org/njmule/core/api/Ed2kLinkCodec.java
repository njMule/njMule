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
package org.njmule.core.api;

import com.google.common.base.Utf8;
import com.google.common.escape.Escaper;
import com.google.common.net.PercentEscaper;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parsing and escaping shared by the link records. */
final class Ed2kLinkCodec {

    private static final Pattern HEX32 = Pattern.compile("[0-9A-Fa-f]{32}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,15}");
    private static final Pattern IPV4 =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
    private static final int MAX_HOST = 255;
    // Printable ASCII but % (the escape), space and | (the link's separator), with upper-case hex.
    private static final Escaper LINK_TEXT = new PercentEscaper("!\"#$&'()*+,-./:;<=>?@[\\]^_`{}~", false);

    private Ed2kLinkCodec() {}

    static Ed2kLink parse(String text) {
        String link = text.strip();
        if (link.regionMatches(true, 0, "magnet:?", 0, 8)) {
            return magnet(link.substring(8));
        }
        // Browsers hand links to the program they open with the separators escaped.
        if (link.indexOf('|') < 0 && link.regionMatches(true, 0, "ed2k://%7C", 0, 10)) {
            link = link.replace("%7C", "|").replace("%7c", "|");
        }
        List<String> tokens = tokens(link, '|');
        if (tokens.isEmpty() || !tokens.getFirst().equalsIgnoreCase("ed2k://")) {
            throw new IllegalArgumentException("not an ed2k link");
        }
        String kind = token(tokens, 1);
        return switch (kind) {
            case "file" -> file(tokens);
            case "server" -> {
                expectEnd(tokens, 4);
                yield new Ed2kLink.ServerLink(token(tokens, 2), port(token(tokens, 3)));
            }
            case "serverlist" -> {
                expectEnd(tokens, 3);
                yield new Ed2kLink.ServerListLink(token(tokens, 2));
            }
            case "nodeslist" -> {
                expectEnd(tokens, 3);
                yield new Ed2kLink.NodesListLink(token(tokens, 2));
            }
            case "search" -> new Ed2kLink.SearchLink(decode(token(tokens, 2)));
            default -> throw new IllegalArgumentException("unknown ed2k link type: " + kind);
        };
    }

    private static Ed2kLink file(List<String> tokens) {
        String name = decode(token(tokens, 2)).strip();
        long size = size(token(tokens, 3));
        Hash16 hash = hash(token(tokens, 4));
        Optional<Hash20> aich = Optional.empty();
        List<Hash16> parts = List.of();
        List<String> http = new ArrayList<>();
        List<Ed2kLink.Source> sources = List.of();
        int i = 5;
        for (; i < tokens.size() && !tokens.get(i).equals("/"); i++) {
            String param = tokens.get(i);
            if (param.startsWith("h=")) {
                aich = aich(param.substring(2));
            } else if (param.startsWith("p=")) {
                parts = partHashes(param.substring(2));
            } else if (param.startsWith("s=") && isWebUrl(param.substring(2))) {
                http.add(param.substring(2));
            }
        }
        if (i >= tokens.size()) {
            throw new IllegalArgumentException("a file link ends with |/");
        }
        if (i + 1 < tokens.size()) {
            // Optional extension: |sources[@YYMMDD],ip:port,…|/
            if (i + 2 >= tokens.size() || !tokens.get(i + 2).equals("/")) {
                throw new IllegalArgumentException("unterminated link extension");
            }
            sources = sources(tokens.get(i + 1));
        }
        return new Ed2kLink.FileLink(name, size, hash, aich, parts, sources, http);
    }

    private static Ed2kLink magnet(String query) {
        String name = null;
        String size = null;
        String hash = null;
        Optional<Hash20> aich = Optional.empty();
        List<Ed2kLink.Source> sources = new ArrayList<>();
        List<String> http = new ArrayList<>();
        for (String param : tokens(query, '&')) {
            if (param.length() < 3 || param.charAt(2) != '=') {
                continue;
            }
            String key = param.substring(0, 2);
            String value = param.substring(3);
            switch (key) {
                case "dn" -> name = value;
                case "xl" -> size = value;
                case "as" -> {
                    if (value.regionMatches(true, 0, "http://", 0, 7)) {
                        http.add(value);
                    }
                }
                case "xs" -> {
                    if (value.startsWith("ed2kftp://")) {
                        String rest = value.substring(10);
                        int slash = rest.indexOf('/');
                        if (slash > 0) {
                            source(rest.substring(0, slash)).ifPresent(sources::add);
                        }
                    }
                }
                case "xt" -> {
                    if (value.startsWith("urn:ed2k:")) {
                        hash = value.substring(9);
                    } else if (value.startsWith("urn:ed2khash:")) {
                        hash = value.substring(13);
                    } else if (value.startsWith("urn:aich:")) {
                        aich = aich(value.substring(9));
                    }
                }
                default -> {
                    // other magnet parameters do not apply to eD2K
                }
            }
        }
        if (name == null || size == null || hash == null) {
            throw new IllegalArgumentException("magnet link without eD2K name, size and hash");
        }
        return new Ed2kLink.FileLink(
                decode(name).strip(),
                size(size),
                hash(hash),
                aich,
                List.of(),
                sources.subList(0, Math.min(sources.size(), Ed2kLink.MAX_SOURCES)),
                http);
    }

    private static List<Ed2kLink.Source> sources(String extension) {
        if (!extension.startsWith("sources")) {
            return List.of();
        }
        String rest = extension.substring(7);
        if (rest.startsWith("@")) {
            if (rest.length() < 7 || !DIGITS.matcher(rest.substring(1, 7)).matches()) {
                return List.of();
            }
            int year = 2000 + Integer.parseInt(rest.substring(1, 3));
            int month = Integer.parseInt(rest.substring(3, 5));
            int day = Integer.parseInt(rest.substring(5, 7));
            try {
                if (!LocalDate.of(year, month, day).isAfter(LocalDate.now(ZoneOffset.UTC))) {
                    return List.of();
                }
            } catch (DateTimeException e) {
                return List.of();
            }
            rest = rest.substring(7);
        }
        List<Ed2kLink.Source> out = new ArrayList<>();
        for (String entry : rest.split(",", -1)) {
            if (out.size() >= Ed2kLink.MAX_SOURCES) {
                break;
            }
            source(entry).ifPresent(out::add);
        }
        return out;
    }

    private static Optional<Ed2kLink.Source> source(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon <= 0 || colon > MAX_HOST) {
            return Optional.empty();
        }
        String host = entry.substring(0, colon);
        String portText = entry.substring(colon + 1);
        if (!DIGITS.matcher(portText).matches()) {
            return Optional.empty();
        }
        int port = (int) Math.min(Long.parseLong(portText), Integer.MAX_VALUE);
        if (port < 1 || port > 0xFFFF) {
            return Optional.empty();
        }
        if (!IPV4.matcher(host).matches() && !isHostName(host)) {
            return Optional.empty();
        }
        return Optional.of(new Ed2kLink.Source(host, port));
    }

    private static boolean isHostName(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Hash20> aich(String base32) {
        try {
            return Optional.of(Hash20.parseBase32(base32));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<Hash16> partHashes(String list) {
        List<Hash16> out = new ArrayList<>();
        for (String hex : list.split(":", -1)) {
            if (!HEX32.matcher(hex).matches()) {
                return List.of();
            }
            out.add(Hash16.parseHex(hex));
        }
        return out;
    }

    private static Hash16 hash(String hex) {
        if (!HEX32.matcher(hex).matches()) {
            throw new IllegalArgumentException("ill-formed file hash");
        }
        return Hash16.parseHex(hex);
    }

    private static long size(String text) {
        if (!DIGITS.matcher(text).matches()) {
            throw new IllegalArgumentException("ill-formed file size: " + text);
        }
        return Long.parseLong(text);
    }

    private static int port(String text) {
        if (!DIGITS.matcher(text).matches() || text.length() > 5) {
            throw new IllegalArgumentException("ill-formed port: " + text);
        }
        return Integer.parseInt(text);
    }

    static void checkUrl(String url) {
        Objects.requireNonNull(url, "url");
        if (!isWebUrl(url)) {
            throw new IllegalArgumentException("only http and https URLs are allowed: " + url);
        }
    }

    private static boolean isWebUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String token(List<String> tokens, int index) {
        if (index >= tokens.size() || tokens.get(index).isEmpty()) {
            throw new IllegalArgumentException("incomplete ed2k link");
        }
        return tokens.get(index);
    }

    private static void expectEnd(List<String> tokens, int index) {
        if (index >= tokens.size() || !tokens.get(index).equals("/")) {
            throw new IllegalArgumentException("an ed2k link ends with |/");
        }
    }

    /** Splits like the eD2K link tokenizer: an empty token ends the list. */
    private static List<String> tokens(String text, char separator) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf(separator, start);
            String token = end < 0 ? text.substring(start) : text.substring(start, end);
            if (token.isEmpty()) {
                break;
            }
            out.add(token);
            if (end < 0) {
                break;
            }
            start = end + 1;
        }
        return out;
    }

    /**
     * Percent-decoding (control characters dropped), then UTF-8 when the result is a valid UTF-8 byte string; anything
     * else is kept as typed.
     */
    static String decode(String text) {
        StringBuilder chars = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%'
                    && i + 2 < text.length()
                    && HexFormat.isHexDigit(text.charAt(i + 1))
                    && HexFormat.isHexDigit(text.charAt(i + 2))) {
                int value = HexFormat.fromHexDigits(text, i + 1, i + 3);
                i += 2;
                if (value >= 0x20) {
                    chars.append((char) value);
                }
            } else {
                chars.append(c);
            }
        }
        String decoded = chars.toString();
        for (int i = 0; i < decoded.length(); i++) {
            if (decoded.charAt(i) > 0xFF) {
                return decoded;
            }
        }
        byte[] bytes = decoded.getBytes(StandardCharsets.ISO_8859_1);
        return Utf8.isWellFormed(bytes) ? new String(bytes, StandardCharsets.UTF_8) : decoded;
    }

    /** UTF-8 with {@code %}, space, {@code |}, control and non-ASCII bytes percent-encoded. */
    static String encode(String text) {
        // A lone surrogate becomes '?' through UTF-8, which the escaper would refuse.
        return LINK_TEXT.escape(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
