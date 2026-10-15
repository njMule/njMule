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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.google.common.base.Splitter;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.config.ProxySettings;

@Timeout(60)
class WebListsTest {

    // Path -> the whole response, status line to body.
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    // Paths whose response stops there, the connection left open.
    private final Set<String> stalls = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private final WebLists anyAddress = new WebLists(a -> true);
    private final List<ServerSocket> proxies = new CopyOnWriteArrayList<>();
    private final List<String> proxySaw = new CopyOnWriteArrayList<>();
    private byte[] fetched;

    @BeforeEach
    void start() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!this.server.isClosed()) {
                try (Socket client = this.server.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
                    String path = Splitter.on(' ').splitToList(in.readLine()).get(1);
                    for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                        // Headers are not looked at.
                    }
                    OutputStream out = client.getOutputStream();
                    out.write(this.responses.getOrDefault(path, response(404, "", new byte[0])));
                    out.flush();
                    if (this.stalls.contains(path)) {
                        Thread.sleep(20_000);
                    }
                } catch (IOException e) {
                    // Closed by the test.
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        this.server.close();
        for (ServerSocket proxy : this.proxies) {
            proxy.close();
        }
    }

    @Test
    void fetchesFollowsAFewRedirectsAndUnpacks() throws Exception {
        byte[] list = "server list".getBytes(StandardCharsets.US_ASCII);
        this.responses.put("/server.met", response(200, "", list));
        this.responses.put("/moved", response(302, "Location: /server.met\r\n", new byte[0]));
        this.responses.put("/list.gz", response(200, "", gzip(list)));
        this.responses.put("/list.zip", response(200, "", zip("nodes.dat", list)));

        assertThat(this.anyAddress.fetch(this.uri("/server.met"))).isEqualTo(list);
        assertThat(this.anyAddress.fetch(this.uri("/moved"))).isEqualTo(list);
        assertThat(this.anyAddress.fetch(this.uri("/list.gz"))).isEqualTo(list);
        assertThat(this.anyAddress.fetch(this.uri("/list.zip"))).isEqualTo(list);
    }

    @Test
    void refusesLocalAddressesOtherSchemesFailuresAndRedirectLoops() throws Exception {
        this.responses.put("/loop", response(302, "Location: /loop\r\n", new byte[0]));
        this.responses.put("/away", response(301, "Location: ftp://example.org/server.met\r\n", new byte[0]));

        assertThatThrownBy(() -> WebLists.create().fetch(this.uri("/server.met")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("local or private");
        assertThatThrownBy(() -> this.anyAddress.fetch(URI.create("ftp://example.org/server.met")))
                .hasMessageContaining("http and https");
        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/missing"))).hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/loop"))).hasMessageContaining("too many redirects");
        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/away")))
                .as("a redirect is checked like the first address")
                .hasMessageContaining("http and https");
    }

    @Test
    void aBodyThatStopsComingFailsWithinTheDeadline() {
        byte[] head = "HTTP/1.1 200 X\r\nContent-Length: 1000\r\n\r\n0123456789".getBytes(StandardCharsets.US_ASCII);
        this.responses.put("/stalled", head);
        this.stalls.add("/stalled");
        WebLists quick = new WebLists(a -> true, Duration.ofSeconds(2));

        assertTimeoutPreemptively(
                Duration.ofSeconds(15),
                () -> assertThatThrownBy(() -> quick.fetch(this.uri("/stalled")))
                        .isInstanceOf(IOException.class));
    }

    @Test
    void anArchiveGivesTheListItHoldsRatherThanItsFirstFile() throws Exception {
        byte[] list = "1.2.3.4 - 1.2.3.5 , 100 , bad".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write("read me".getBytes(StandardCharsets.US_ASCII));
            zip.putNextEntry(new ZipEntry("lists/ipfilter.dat"));
            zip.write(list);
        }
        this.responses.put("/lists.zip", response(200, "", out.toByteArray()));
        this.responses.put("/other.zip", response(200, "", zip("only.txt", list)));

        assertThat(this.anyAddress.fetch(this.uri("/lists.zip"))).isEqualTo(list);
        assertThat(this.anyAddress.fetch(this.uri("/other.zip")))
                .as("else the first")
                .isEqualTo(list);
    }

    @Test
    void aRedirectMayNotLeaveHttpsForHttp() throws Exception {
        URI secure = URI.create("https://lists.example/ipfilter.zip");

        assertThat(WebLists.redirect(secure, "/other.zip")).isEqualTo(URI.create("https://lists.example/other.zip"));
        assertThat(WebLists.redirect(URI.create("http://lists.example/a"), "https://mirror.example/a"))
                .isEqualTo(URI.create("https://mirror.example/a"));
        assertThatThrownBy(() -> WebLists.redirect(secure, "http://mirror.example/ipfilter.zip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("https");
    }

    @Test
    void aListCutShortIsRefused() throws Exception {
        byte[] lines =
                "1.2.3.4 - 1.2.3.5 , 100 , one\n2.2.3.4 - 2.2.3.5 , 100 , two\n".getBytes(StandardCharsets.US_ASCII);
        byte[] cut = Arrays.copyOf(lines, 40);
        byte[] shortHead = "HTTP/1.1 200 X\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        this.responses.put("/short", concat(shortHead, cut));
        this.responses.put("/whole.dat", untilClosed(lines));
        this.responses.put("/cut.dat", untilClosed(cut));

        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/short"))).isInstanceOf(IOException.class);
        assertThat(this.anyAddress.fetchText(this.uri("/whole.dat"))).isEqualTo(lines);
        assertThatThrownBy(() -> this.anyAddress.fetchText(this.uri("/cut.dat")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cut short");
        assertThat(this.anyAddress.fetch(this.uri("/cut.dat")))
                .as("not a list of lines")
                .isEqualTo(cut);
    }

    @Test
    void listsGoThroughTheHttpProxyOfThePreferences() throws Exception {
        byte[] list = "through the proxy".getBytes(StandardCharsets.US_ASCII);
        // A proxy gets the whole address; nothing listens at the address itself.
        this.responses.put("http://localhost:9/server.met", response(200, "", list));
        ProxyRoute.install(
                () -> new ProxySettings(ProxySettings.Type.HTTP, "127.0.0.1", this.server.getLocalPort(), "", ""));
        try {
            assertThat(new WebLists(a -> true).fetch(URI.create("http://localhost:9/server.met")))
                    .isEqualTo(list);
        } finally {
            ProxyRoute.install(() -> ProxySettings.NONE);
            Authenticator.setDefault(null);
        }
    }

    @Test
    void listsGoThroughTheSocksProxyOfThePreferencesWithItsCredentials() throws Exception {
        this.givenAList("/server.met", "through socks");
        this.givenASocksProxyThatWantsAPassword();
        try {
            this.whenTheListIsFetched("/server.met");
            this.thenTheListCame("through socks");
            this.thenTheProxyWasAskedWithTheCredentialsForTheServer();
        } finally {
            ProxyRoute.install(() -> ProxySettings.NONE);
        }
    }

    private void givenAList(String path, String text) {
        this.responses.put(path, response(200, "", text.getBytes(StandardCharsets.US_ASCII)));
    }

    private void givenASocksProxyThatWantsAPassword() throws IOException {
        ServerSocket proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.proxies.add(proxy);
        Thread.ofVirtual().start(() -> this.socks5(proxy));
        ProxyRoute.install(
                () -> new ProxySettings(ProxySettings.Type.SOCKS, "127.0.0.1", proxy.getLocalPort(), "me", "secret"));
    }

    private void whenTheListIsFetched(String path) throws IOException {
        this.fetched = this.anyAddress.fetch(this.uri(path));
    }

    private void thenTheListCame(String text) {
        assertThat(new String(this.fetched, StandardCharsets.US_ASCII)).isEqualTo(text);
    }

    private void thenTheProxyWasAskedWithTheCredentialsForTheServer() {
        assertThat(this.proxySaw).containsExactly("auth me:secret", "connect 127.0.0.1:" + this.server.getLocalPort());
    }

    // A SOCKS 5 proxy (RFC 1928, user name and password per RFC 1929) that relays one connection both ways.
    private void socks5(ServerSocket proxy) {
        try (Socket client = proxy.accept()) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            in.readUnsignedByte();
            in.readFully(new byte[in.readUnsignedByte()]);
            out.write(new byte[] {5, 2});
            in.readUnsignedByte();
            String user = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
            String password = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
            this.proxySaw.add("auth " + user + ":" + password);
            out.write(new byte[] {1, 0});
            in.readFully(new byte[3]);
            int kind = in.readUnsignedByte();
            String host = kind == 3
                    ? new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII)
                    : InetAddress.getByAddress(in.readNBytes(kind == 1 ? 4 : 16))
                            .getHostAddress();
            int port = in.readUnsignedShort();
            this.proxySaw.add("connect " + host + ":" + port);
            try (Socket upstream = new Socket(host, port)) {
                out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                out.flush();
                Thread.ofVirtual().start(() -> relay(upstream, client));
                relay(client, upstream);
            }
        } catch (IOException e) {
            // The test closes everything at the end.
        }
    }

    private static void relay(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException e) {
            // One side closed.
        }
    }

    @Test
    void everyKindOfRedirectIsFollowed() throws Exception {
        byte[] list = "server list".getBytes(StandardCharsets.US_ASCII);
        this.responses.put("/server.met", response(200, "", list));
        for (int status : new int[] {303, 307, 308}) {
            this.responses.put("/moved" + status, response(status, "Location: /server.met\r\n", new byte[0]));

            assertThat(this.anyAddress.fetch(this.uri("/moved" + status)))
                    .as("HTTP %d", status)
                    .isEqualTo(list);
        }
    }

    @Test
    void addressesWithoutSchemeOrHostAndBadRedirectsAreRefused() {
        this.responses.put("/bad", response(302, "Location: http://bad host/list\r\n", new byte[0]));
        this.responses.put("/nowhere", response(302, "", new byte[0]));

        assertThatThrownBy(() -> this.anyAddress.fetch(URI.create("server.met")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("http and https");
        assertThatThrownBy(() -> this.anyAddress.fetch(URI.create("http:/server.met")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no host");
        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/bad")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("bad address");
        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/nowhere")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("without a location");
    }

    @Test
    void noTimeLeftOrAnInterruptedCallerFetchesNothing() throws Exception {
        this.responses.put("/server.met", response(200, "", new byte[] {1}));

        assertThatThrownBy(() -> new WebLists(a -> true, Duration.ZERO).fetch(this.uri("/server.met")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("in time");

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<Boolean> stillInterrupted = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                this.anyAddress.fetch(this.uri("/server.met"));
                failure.complete(null);
            } catch (IOException e) {
                failure.complete(e);
            }
            stillInterrupted.complete(Thread.currentThread().isInterrupted());
        });
        assertThat(failure.get(10, TimeUnit.SECONDS)).hasMessageContaining("interrupted");
        assertThat(stillInterrupted.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void listsThatCannotBeCutUnseenAreTakenAsTheyCome() throws Exception {
        byte[] noBreak = "1.2.3.4 - 1.2.3.5 , 100 , one".getBytes(StandardCharsets.US_ASCII);
        byte[] carriageReturn = "1.2.3.4 - 1.2.3.5 , 100 , one\r".getBytes(StandardCharsets.US_ASCII);
        this.responses.put("/packed.gz", untilClosed(gzip(noBreak)));
        this.responses.put("/cr.dat", untilClosed(carriageReturn));
        this.responses.put("/empty.dat", untilClosed(new byte[0]));
        this.responses.put("/sized.dat", response(200, "", noBreak));
        this.responses.put(
                "/chunked.dat",
                concat(
                        "HTTP/1.1 200 X\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII),
                        ("1d\r\n" + new String(noBreak, StandardCharsets.US_ASCII) + "\r\n0\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII)));

        assertThat(this.anyAddress.fetchText(this.uri("/packed.gz")))
                .as("gzip checks itself")
                .isEqualTo(noBreak);
        assertThat(this.anyAddress.fetchText(this.uri("/cr.dat"))).isEqualTo(carriageReturn);
        assertThat(this.anyAddress.fetchText(this.uri("/empty.dat"))).isEmpty();
        assertThat(this.anyAddress.fetchText(this.uri("/sized.dat")))
                .as("its length was given")
                .isEqualTo(noBreak);
        assertThat(this.anyAddress.fetchText(this.uri("/chunked.dat")))
                .as("chunks end on their own")
                .isEqualTo(noBreak);
    }

    @Test
    void bodiesThatOnlyLookPackedAreLeftAlone() throws Exception {
        byte[] almostGzip = {0x1F, 0x00, 'x'};
        byte[] almostZip = {'P', 'K', 1, 2, 'x'};
        byte[] almostZipToo = {'P', 'K', 3, 9, 'x'};
        byte[] tooShort = {'P'};
        this.responses.put("/a", response(200, "", almostGzip));
        this.responses.put("/b", response(200, "", almostZip));
        this.responses.put("/c", response(200, "", almostZipToo));
        this.responses.put("/d", response(200, "", tooShort));

        assertThat(this.anyAddress.fetch(this.uri("/a"))).isEqualTo(almostGzip);
        assertThat(this.anyAddress.fetch(this.uri("/b"))).isEqualTo(almostZip);
        assertThat(this.anyAddress.fetch(this.uri("/c"))).isEqualTo(almostZipToo);
        assertThat(this.anyAddress.fetch(this.uri("/d"))).isEqualTo(tooShort);
    }

    @Test
    void anArchiveWithOnlyFoldersOrABombIsRefused() throws Exception {
        ByteArrayOutputStream folders = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(folders)) {
            zip.putNextEntry(new ZipEntry("lists/"));
            zip.closeEntry();
        }
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bomb)) {
            byte[] zeros = new byte[1024 * 1024];
            for (long written = 0; written <= WebLists.MAX_SIZE; written += zeros.length) {
                gz.write(zeros);
            }
        }
        this.responses.put("/folders.zip", response(200, "", folders.toByteArray()));
        this.responses.put("/bomb.gz", response(200, "", bomb.toByteArray()));

        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/folders.zip")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty zip");
        assertThatThrownBy(() -> this.anyAddress.fetch(this.uri("/bomb.gz")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("larger than");
    }

    @Test
    void onlyPublicAddressesCount() throws Exception {
        for (String address : new String[] {
            "127.0.0.1",
            "10.1.2.3",
            "192.168.1.1",
            "172.16.0.1",
            "169.254.1.1",
            "100.64.0.1",
            "0.0.0.1",
            "224.0.0.1",
            "::1",
            "fd00::1",
            "fe80::1",
            "0.0.0.0",
            "::"
        }) {
            assertThat(WebLists.isPublic(InetAddress.getByName(address)))
                    .as(address)
                    .isFalse();
        }
        for (String address : new String[] {"8.8.8.8", "100.128.0.1", "2001:4860:4860::8888"}) {
            assertThat(WebLists.isPublic(InetAddress.getByName(address)))
                    .as(address)
                    .isTrue();
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + this.server.getLocalPort() + path);
    }

    private static byte[] response(int status, String headers, byte[] body) {
        String head = "HTTP/1.1 " + status + " X\r\n" + headers + "Content-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        byte[] start = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[start.length + body.length];
        System.arraycopy(start, 0, all, 0, start.length);
        System.arraycopy(body, 0, all, start.length, body.length);
        return all;
    }

    // A response whose body ends when the connection closes.
    private static byte[] untilClosed(byte[] body) {
        return concat("HTTP/1.1 200 X\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), body);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] zip(String name, byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(data);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
