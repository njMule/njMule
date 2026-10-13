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
package org.njmule.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.njmule.core.api.NjMuleInfo;

/**
 * Fetches a list (server.met, nodes.dat, an IP filter) from the web at the user's request, with the JDK's HTTP client:
 * http or https only, at most three redirects, each checked again, never to a loopback, private, link-local or
 * multicast address, and at most 50 MiB; a gzip or zip body is unpacked within the same limit.
 */
public final class WebLists {

    public static final long MAX_SIZE = 50L * 1024 * 1024;

    private static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final HttpClient http;
    private final Predicate<InetAddress> reachable;

    WebLists(Predicate<InetAddress> reachable) {
        this.reachable = Objects.requireNonNull(reachable, "reachable");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** A fetcher that reaches public addresses only. */
    public static WebLists create() {
        return new WebLists(WebLists::isPublic);
    }

    /**
     * The body at {@code uri}, unpacked when it is gzip or zip.
     *
     * @throws IOException when the address is refused, the server fails or the body is too large
     */
    public byte[] fetch(URI uri) throws IOException {
        URI current = Objects.requireNonNull(uri, "uri");
        for (int hop = 0; ; hop++) {
            this.check(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(TIMEOUT)
                    .header("User-Agent", NjMuleInfo.NAME + "/" + NjMuleInfo.version())
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while fetching " + current.getHost(), e);
            }
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (hop >= MAX_REDIRECTS) {
                        throw new IOException("too many redirects from " + uri.getHost());
                    }
                    String location = response.headers()
                            .firstValue("Location")
                            .orElseThrow(() -> new IOException("a redirect without a location"));
                    current = current.resolve(location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status + " from " + current.getHost());
                }
                return unpack(readAtMost(body));
            }
        }
    }

    // The host is looked up here and again by the client: a name that changes its address in between is not stopped.
    private void check(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IOException("only http and https addresses are fetched: " + uri);
        }
        if (uri.getHost() == null) {
            throw new IOException("no host in " + uri);
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (!this.reachable.test(address)) {
                throw new IOException(
                        "refusing to fetch from the local or private address " + address.getHostAddress());
            }
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet6Address) {
            // Unique local addresses, fc00::/7.
            return (b[0] & 0xFE) != 0xFC;
        }
        // 0.0.0.0/8 and the carrier-grade NAT range 100.64.0.0/10.
        return b[0] != 0 && !((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64);
    }

    private static byte[] unpack(byte[] body) throws IOException {
        if (body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return readAtMost(in);
            }
        }
        if (body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4) {
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(body))) {
                for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    if (!entry.isDirectory()) {
                        return readAtMost(in);
                    }
                }
            }
            throw new IOException("an empty zip archive");
        }
        return body;
    }

    private static byte[] readAtMost(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            total += n;
            if (total > MAX_SIZE) {
                throw new IOException("the list is larger than " + MAX_SIZE / (1024 * 1024) + " MiB");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
