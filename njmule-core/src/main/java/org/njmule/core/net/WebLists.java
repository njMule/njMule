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
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.njmule.core.api.NjMuleInfo;

/**
 * Fetches a list (server.met, nodes.dat, an IP filter) from the web at the user's request, with the JDK's HTTP client:
 * http or https only, at most three redirects, each checked again and never from https to http, never to a loopback,
 * private, link-local or multicast address, at most 50 MiB and 90 seconds in all; a gzip or zip body is unpacked within
 * the same limit. The HTTP proxy of the preferences is used; the client takes no SOCKS proxy, so with one lists come
 * directly.
 */
public final class WebLists {

    public static final long MAX_SIZE = 50L * 1024 * 1024;

    private static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    // What an archive holds the list as, by kind; any other file only when none of these is there.
    private static final Set<String> LIST_NAMES =
            Set.of("ipfilter.dat", "guarding.p2p", "guardian.p2p", "server.met", "nodes.dat");

    // The proxy of the preferences, read at each request.
    private static final ProxySelector PREFERRED_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            Proxy proxy = TcpConnector.currentProxy();
            return List.of(proxy.type() == Proxy.Type.HTTP ? proxy : Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
            // The request fails and says why.
        }
    };

    private final HttpClient http;
    private final Predicate<InetAddress> reachable;
    private final Duration timeout;

    WebLists(Predicate<InetAddress> reachable) {
        this(reachable, TIMEOUT);
    }

    WebLists(Predicate<InetAddress> reachable, Duration timeout) {
        this.reachable = Objects.requireNonNull(reachable, "reachable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(PREFERRED_PROXY)
                .build();
    }

    /** A fetcher that reaches public addresses only. */
    public static WebLists create() {
        return new WebLists(WebLists::isPublic);
    }

    /**
     * The body at {@code uri}, unpacked when it is gzip or zip.
     *
     * @throws IOException when the address is refused, the server fails, the body is too large or late
     */
    public byte[] fetch(URI uri) throws IOException {
        return this.fetch(uri, false);
    }

    /**
     * As {@link #fetch(URI)}, for a list of lines (an IP filter): a plain body that ends with the connection, not at a
     * length given first, must end with a line break, as a list cut short would lose its last lines unseen.
     */
    public byte[] fetchText(URI uri) throws IOException {
        return this.fetch(uri, true);
    }

    private byte[] fetch(URI uri, boolean lines) throws IOException {
        URI current = Objects.requireNonNull(uri, "uri");
        long deadline = System.nanoTime() + this.timeout.toNanos();
        for (int hop = 0; ; hop++) {
            this.check(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(left(deadline))
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
                    current = redirect(current, location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status + " from " + current.getHost());
                }
                byte[] data = readWithin(body, left(deadline));
                if (lines && !isPacked(data) && endsWithConnection(response) && !endsWithLineBreak(data)) {
                    throw new IOException("the list from " + current.getHost() + " was cut short");
                }
                return unpack(data);
            }
        }
    }

    // A redirect is checked like the first address, and one from https stays on https.
    static URI redirect(URI from, String location) throws IOException {
        URI to;
        try {
            to = from.resolve(location);
        } catch (IllegalArgumentException e) {
            throw new IOException("a redirect to a bad address: " + location, e);
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(to.getScheme())) {
            throw new IOException("refusing a redirect from https to " + to);
        }
        return to;
    }

    private static Duration left(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) {
            throw new IOException("the list did not come in time");
        }
        return Duration.ofNanos(nanos);
    }

    // The client's own timeout ends with the headers: a body that stops coming is given up at the deadline.
    private static byte[] readWithin(InputStream body, Duration left) throws IOException {
        CompletableFuture<byte[]> read = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().name("njmule-web-read").start(() -> {
            try {
                read.complete(readAtMost(body));
            } catch (IOException | RuntimeException e) {
                read.completeExceptionally(e);
            }
        });
        try {
            return read.get(left.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            reader.interrupt();
            body.close();
            throw new IOException("the list did not come in time", e);
        } catch (InterruptedException e) {
            reader.interrupt();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading a list", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    // An HTTP/1.1 body with neither a length nor chunks ends when the server closes: a cut looks the same.
    private static boolean endsWithConnection(HttpResponse<?> response) {
        return response.version() == HttpClient.Version.HTTP_1_1
                && response.headers().firstValue("Content-Length").isEmpty()
                && response.headers().firstValue("Transfer-Encoding").isEmpty();
    }

    private static boolean endsWithLineBreak(byte[] data) {
        return data.length == 0 || data[data.length - 1] == '\n' || data[data.length - 1] == '\r';
    }

    // Gzip and zip check their own length and checksum.
    private static boolean isPacked(byte[] body) {
        return isGzip(body) || isZip(body);
    }

    private static boolean isGzip(byte[] body) {
        return body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B;
    }

    private static boolean isZip(byte[] body) {
        return body.length >= 4 && body[0] == 'P' && body[1] == 'K' && body[2] == 3 && body[3] == 4;
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
        if (isGzip(body)) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return readAtMost(in);
            }
        }
        if (isZip(body)) {
            byte[] first = null;
            long left = MAX_SIZE;
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(body))) {
                for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    byte[] data = readAtMost(in, left);
                    left -= data.length;
                    String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    if (LIST_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                        return data;
                    }
                    if (first == null) {
                        first = data;
                    }
                }
            }
            if (first == null) {
                throw new IOException("an empty zip archive");
            }
            return first;
        }
        return body;
    }

    private static byte[] readAtMost(InputStream in) throws IOException {
        return readAtMost(in, MAX_SIZE);
    }

    private static byte[] readAtMost(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            total += n;
            if (total > limit) {
                throw new IOException("the list is larger than " + MAX_SIZE / (1024 * 1024) + " MiB");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
