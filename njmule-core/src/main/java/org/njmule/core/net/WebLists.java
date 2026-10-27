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
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.InputStreamResponseListener;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;

/**
 * Fetches a list (server.met, nodes.dat, an IP filter) from the web at the user's request, with Jetty's HTTP client:
 * http or https only, at most three redirects, each checked again and never from https to http, never to a loopback,
 * private, link-local or multicast address, at most 50 MiB and 90 seconds in all; a gzip or zip body is unpacked within
 * the same limit. The proxy of the preferences is used, HTTP or SOCKS 5 with its user name and password. The host is
 * looked up here to check its addresses, also when a proxy then connects to it. A {@linkplain #download download} of a
 * file streams to disk instead, within the length it is expected to have.
 */
public final class WebLists {

    public static final long MAX_SIZE = 50L * 1024 * 1024;

    private static final int MAX_REDIRECTS = 3;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);
    // A download may take long on a slow line; it fails when nothing comes for a minute.
    private static final Duration DOWNLOAD_IDLE = Duration.ofSeconds(60);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(4);
    private static final int DOWNLOAD_BUFFER = 64 * 1024;
    // What an archive holds the list as, by kind; any other file only when none of these is there.
    private static final Set<String> LIST_NAMES =
            Set.of("ipfilter.dat", "guarding.p2p", "guardian.p2p", "server.met", "nodes.dat");

    private final Predicate<InetAddress> reachable;
    private final Duration timeout;

    WebLists(Predicate<InetAddress> reachable) {
        this(reachable, DEFAULT_TIMEOUT);
    }

    WebLists(Predicate<InetAddress> reachable, Duration timeout) {
        this.reachable = Objects.requireNonNull(reachable, "reachable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** A fetcher that reaches public addresses only. */
    public static WebLists create() {
        return new WebLists(WebLists::isPublic);
    }

    /**
     * A fetcher that reaches any address, for one the user set by hand as where to look for new versions: what comes
     * from there is taken only when signed.
     */
    public static WebLists anyAddress() {
        return new WebLists(address -> true);
    }

    /**
     * The body at {@code uri}, unpacked when it is gzip or zip.
     *
     * @throws IOException when the address is refused, the server fails, the body is too large or late
     */
    public byte[] fetch(URI uri) throws IOException {
        return fetch(uri, false);
    }

    /**
     * As {@link #fetch(URI)}, for a list of lines (an IP filter): a plain body that ends with the connection, not at a
     * length given first, must end with a line break, as a list cut short would lose its last lines unseen.
     */
    public byte[] fetchText(URI uri) throws IOException {
        return fetch(uri, true);
    }

    private byte[] fetch(URI uri, boolean lines) throws IOException {
        URI current = Objects.requireNonNull(uri, "uri");
        long deadline = System.nanoTime() + this.timeout.toNanos();
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("interrupted while fetching " + current.getHost());
        }
        HttpClient http = HttpRoute.start();
        try {
            for (int hop = 0; ; hop++) {
                check(current);
                // The whole answer, within the size and the time left: the next hop starts after this one is done.
                Request request =
                        http.newRequest(current).timeout(left(deadline).toNanos(), TimeUnit.NANOSECONDS);
                ContentResponse response = answer(
                        new CompletableResponseListener(request, (int) MAX_SIZE).send(), current, left(deadline));
                int status = response.getStatus();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (hop >= MAX_REDIRECTS) {
                        throw new IOException("too many redirects from " + uri.getHost());
                    }
                    String location = response.getHeaders().get(HttpHeader.LOCATION);
                    if (location == null) {
                        throw new IOException("a redirect without a location");
                    }
                    current = redirect(current, location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status + " from " + current.getHost());
                }
                byte[] data = response.getContent();
                if (lines && !isPacked(data) && endsWithConnection(response) && !endsWithLineBreak(data)) {
                    throw new IOException("the list from " + current.getHost() + " was cut short");
                }
                return unpack(data);
            }
        } finally {
            HttpRoute.stop(http);
        }
    }

    /**
     * Downloads the file at {@code uri} into {@code part}, going on from the bytes {@code part} already holds when the
     * server allows it, until it holds {@code size} bytes; reports the bytes held to {@code progress}. A file that
     * turns out longer is refused; one cut short leaves {@code part} for the next try.
     *
     * @throws IOException when the address is refused, the server fails, the file is longer or the line idle too long
     */
    public void download(URI uri, Path part, long size, LongConsumer progress) throws IOException {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(progress, "progress");
        if (size < 0) {
            throw new IllegalArgumentException("size " + size);
        }
        URI current = Objects.requireNonNull(uri, "uri");
        HttpClient http = HttpRoute.start();
        try {
            int hops = 0;
            boolean restarted = false;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("interrupted while downloading from " + current.getHost());
                }
                check(current);
                long held = held(part, size);
                progress.accept(held);
                if (held == size) {
                    return;
                }
                Request request = http.newRequest(current)
                        .idleTimeout(DOWNLOAD_IDLE.toMillis(), TimeUnit.MILLISECONDS)
                        .timeout(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (held > 0) {
                    request.headers(h -> h.put(HttpHeader.RANGE, "bytes=" + held + "-"));
                }
                InputStreamResponseListener listener = new InputStreamResponseListener();
                request.send(listener);
                Response response = headers(listener, current);
                int status = response.getStatus();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    listener.getInputStream().close();
                    if (++hops > MAX_REDIRECTS) {
                        throw new IOException("too many redirects from " + uri.getHost());
                    }
                    String location = response.getHeaders().get(HttpHeader.LOCATION);
                    if (location == null) {
                        throw new IOException("a redirect without a location");
                    }
                    current = redirect(current, location);
                    continue;
                }
                // The bytes held no longer fit the file: once, from the start.
                if (status == 416 && held > 0 && !restarted) {
                    listener.getInputStream().close();
                    Files.deleteIfExists(part);
                    restarted = true;
                    continue;
                }
                boolean resumed = status == 206 && held > 0 && startsAt(response, held);
                if (status != 200 && !resumed) {
                    listener.getInputStream().close();
                    throw new IOException("HTTP " + status + " from " + current.getHost());
                }
                receive(listener, response, part, resumed ? held : 0, size, progress, current);
                return;
            }
        } finally {
            HttpRoute.stop(http);
        }
    }

    // What part holds already, when it is no longer than the file; a longer one is dropped.
    private static long held(Path part, long size) throws IOException {
        if (!Files.isRegularFile(part)) {
            return 0;
        }
        long held = Files.size(part);
        if (held > size) {
            Files.delete(part);
            return 0;
        }
        return held;
    }

    private static Response headers(InputStreamResponseListener listener, URI uri) throws IOException {
        try {
            return listener.get(HttpRoute.CONNECT_TIMEOUT.plus(DOWNLOAD_IDLE).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while downloading from " + uri.getHost());
        } catch (TimeoutException e) {
            throw new IOException("no answer from " + uri.getHost() + " in time", e);
        } catch (ExecutionException e) {
            throw failure(e.getCause(), uri);
        }
    }

    // A partial answer counts only when it goes on from the bytes held: "bytes <held>-<last>/<length>".
    private static boolean startsAt(Response response, long held) {
        String range = response.getHeaders().get(HttpHeader.CONTENT_RANGE);
        return range != null && range.strip().startsWith("bytes " + held + "-");
    }

    private static void receive(
            InputStreamResponseListener listener,
            Response response,
            Path part,
            long from,
            long size,
            LongConsumer progress,
            URI uri)
            throws IOException {
        long length = response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH);
        if (length > size - from) {
            listener.getInputStream().close();
            throw new IOException("the file at " + uri.getHost() + " is longer than " + size + " bytes");
        }
        StandardOpenOption[] options = from > 0
                ? new StandardOpenOption[] {StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                : new StandardOpenOption[] {
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
                };
        long held = from;
        try (InputStream in = listener.getInputStream();
                OutputStream out = Files.newOutputStream(part, options)) {
            byte[] buffer = new byte[DOWNLOAD_BUFFER];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                if (n > size - held) {
                    throw new IOException("the file at " + uri.getHost() + " is longer than " + size + " bytes");
                }
                out.write(buffer, 0, n);
                held += n;
                progress.accept(held);
            }
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (held != size) {
            throw new IOException(
                    "the download from " + uri.getHost() + " stopped at " + held + " of " + size + " bytes");
        }
    }

    private static ContentResponse answer(CompletableFuture<ContentResponse> answer, URI uri, Duration left)
            throws IOException {
        try {
            return answer.get(left.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + uri.getHost(), e);
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw new IOException("the list did not come in time", e);
        } catch (ExecutionException e) {
            throw failure(e.getCause(), uri);
        }
    }

    private static IOException failure(Throwable cause, URI uri) {
        if (cause instanceof TimeoutException) {
            return new IOException("the list did not come in time", cause);
        }
        // The buffer refuses more than its capacity.
        if (cause instanceof IllegalArgumentException
                && String.valueOf(cause.getMessage()).contains("capacity")) {
            return new IOException("the list is larger than " + MAX_SIZE / (1024 * 1024) + " MiB", cause);
        }
        return cause instanceof IOException io
                ? io
                : new IOException("cannot fetch from " + uri.getHost() + ": " + cause.getMessage(), cause);
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

    // An HTTP/1 body with neither a length nor chunks ends when the server closes: a cut looks the same.
    private static boolean endsWithConnection(ContentResponse response) {
        return (response.getVersion() == HttpVersion.HTTP_1_1 || response.getVersion() == HttpVersion.HTTP_1_0)
                && !response.getHeaders().contains(HttpHeader.CONTENT_LENGTH)
                && !response.getHeaders().contains(HttpHeader.TRANSFER_ENCODING);
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

    // The host is looked up here and again by the client or the proxy: a name that changes its address in between is
    // not stopped.
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
        // One byte past the limit tells a larger list from one that fills it exactly.
        byte[] data = in.readNBytes(Math.toIntExact(limit + 1));
        if (data.length > limit) {
            throw new IOException("the list is larger than " + MAX_SIZE / (1024 * 1024) + " MiB");
        }
        return data;
    }
}
