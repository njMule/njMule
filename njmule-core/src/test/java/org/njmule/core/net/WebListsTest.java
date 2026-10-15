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
import java.net.UnknownHostException;
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
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.config.ProxySettings;

@Timeout(60)
class WebListsTest {

    private static final byte[] SERVER_LIST = "server list".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] IP_FILTER = "1.2.3.4 - 1.2.3.5 , 100 , bad".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TWO_LINES =
            "1.2.3.4 - 1.2.3.5 , 100 , one\n2.2.3.4 - 2.2.3.5 , 100 , two\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CUT = Arrays.copyOf(TWO_LINES, 40);
    private static final byte[] NO_BREAK = "1.2.3.4 - 1.2.3.5 , 100 , one".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CARRIAGE_RETURN = "1.2.3.4 - 1.2.3.5 , 100 , one\r".getBytes(StandardCharsets.US_ASCII);

    // Path -> the whole response, status line to body.
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    // Paths whose response stops there, the connection left open.
    private final Set<String> stalls = ConcurrentHashMap.newKeySet();
    private ServerSocket server;
    private final WebLists anyAddress = new WebLists(a -> true);
    private final List<ServerSocket> proxies = new CopyOnWriteArrayList<>();
    private final List<String> proxySaw = new CopyOnWriteArrayList<>();

    private WebLists lists = this.anyAddress;
    private boolean proxyInstalled;
    private byte[] body;
    private URI redirected;
    private String literal;
    private InetAddress address;
    private boolean isPublic;
    private boolean callerStillInterrupted;
    private Throwable exception;

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
    void stopAndRemoveTheProxy() throws IOException {
        this.server.close();
        for (ServerSocket proxy : this.proxies) {
            proxy.close();
        }
        if (this.proxyInstalled) {
            ProxyRoute.install(() -> ProxySettings.NONE);
            Authenticator.setDefault(null);
        }
    }

    @Test
    void shouldFetchAList() {
        givenTheResponse("/server.met", response(200, "", SERVER_LIST));

        whenTheListIsFetchedFrom("/server.met");

        thenTheBodyIs(SERVER_LIST);
    }

    @Test
    void shouldFollowARedirect() {
        givenTheResponse("/server.met", response(200, "", SERVER_LIST));
        givenTheResponse("/moved", response(302, "Location: /server.met\r\n", new byte[0]));

        whenTheListIsFetchedFrom("/moved");

        thenTheBodyIs(SERVER_LIST);
    }

    @Test
    void shouldUnpackAGzippedList() throws IOException {
        givenTheResponse("/list.gz", response(200, "", gzip(SERVER_LIST)));

        whenTheListIsFetchedFrom("/list.gz");

        thenTheBodyIs(SERVER_LIST);
    }

    @Test
    void shouldUnpackAZippedList() throws IOException {
        givenTheResponse("/list.zip", response(200, "", zip("nodes.dat", SERVER_LIST)));

        whenTheListIsFetchedFrom("/list.zip");

        thenTheBodyIs(SERVER_LIST);
    }

    @Test
    void shouldRefuseLocalAddresses() {
        givenListsReachingOnlyPublicAddresses();

        whenTheListIsFetchedFrom("/server.met");

        thenExceptionOccurred(IOException.class, "local or private");
    }

    @Test
    void shouldRefuseOtherSchemes() {
        whenTheAddressIsFetched("ftp://example.org/server.met");

        thenTheFetchFailsSaying("http and https");
    }

    @Test
    void shouldFailOnAnHttpError() {
        whenTheListIsFetchedFrom("/missing");

        thenTheFetchFailsSaying("HTTP 404");
    }

    @Test
    void shouldRefuseARedirectLoop() {
        givenTheResponse("/loop", response(302, "Location: /loop\r\n", new byte[0]));

        whenTheListIsFetchedFrom("/loop");

        thenTheFetchFailsSaying("too many redirects");
    }

    @Test
    void shouldCheckARedirectLikeTheFirstAddress() {
        givenTheResponse("/away", response(301, "Location: ftp://example.org/server.met\r\n", new byte[0]));

        whenTheListIsFetchedFrom("/away");

        thenTheFetchFailsSaying("http and https", "a redirect is checked like the first address");
    }

    @Test
    void shouldFailWithinTheDeadlineWhenTheBodyStopsComing() {
        givenAStalledResponse("/stalled", "HTTP/1.1 200 X\r\nContent-Length: 1000\r\n\r\n0123456789");
        givenListsWithTheDeadline(Duration.ofSeconds(2));

        whenTheListIsFetchedWithin(Duration.ofSeconds(15), "/stalled");

        thenExceptionOccurred(IOException.class);
    }

    @Test
    void shouldTakeTheListAnArchiveHoldsRatherThanItsFirstFile() throws IOException {
        givenTheResponse(
                "/lists.zip",
                response(
                        200,
                        "",
                        zip(
                                "README.txt",
                                "read me".getBytes(StandardCharsets.US_ASCII),
                                "lists/ipfilter.dat",
                                IP_FILTER)));

        whenTheListIsFetchedFrom("/lists.zip");

        thenTheBodyIs(IP_FILTER);
    }

    @Test
    void shouldTakeTheFirstFileOfAnArchiveWithoutAList() throws IOException {
        givenTheResponse("/other.zip", response(200, "", zip("only.txt", IP_FILTER)));

        whenTheListIsFetchedFrom("/other.zip");

        thenTheBodyIs(IP_FILTER, "else the first");
    }

    @Test
    void shouldResolveARelativeRedirect() {
        whenRedirected("https://lists.example/ipfilter.zip", "/other.zip");

        thenTheRedirectGoesTo("https://lists.example/other.zip");
    }

    @Test
    void shouldLetARedirectGoFromHttpToHttps() {
        whenRedirected("http://lists.example/a", "https://mirror.example/a");

        thenTheRedirectGoesTo("https://mirror.example/a");
    }

    @Test
    void shouldNotLetARedirectLeaveHttpsForHttp() {
        whenRedirected("https://lists.example/ipfilter.zip", "http://mirror.example/ipfilter.zip");

        thenExceptionOccurred(IOException.class, "https");
    }

    @Test
    void shouldRefuseABodyShorterThanItsLength() {
        givenTheResponse(
                "/short",
                concat(
                        "HTTP/1.1 200 X\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII),
                        CUT));

        whenTheListIsFetchedFrom("/short");

        thenExceptionOccurred(IOException.class);
    }

    @Test
    void shouldTakeAWholeListOfLines() {
        givenTheResponse("/whole.dat", untilClosed(TWO_LINES));

        whenTheTextIsFetchedFrom("/whole.dat");

        thenTheBodyIs(TWO_LINES);
    }

    @Test
    void shouldRefuseAListOfLinesCutShort() {
        givenTheResponse("/cut.dat", untilClosed(CUT));

        whenTheTextIsFetchedFrom("/cut.dat");

        thenExceptionOccurred(IOException.class, "cut short");
    }

    @Test
    void shouldTakeABodyCutShortThatIsNotAListOfLines() {
        givenTheResponse("/cut.dat", untilClosed(CUT));

        whenTheListIsFetchedFrom("/cut.dat");

        thenTheBodyIs(CUT, "not a list of lines");
    }

    @Test
    void shouldFetchListsThroughTheHttpProxyOfThePreferences() {
        // A proxy gets the whole address; nothing listens at the address itself.
        givenTheResponse(
                "http://localhost:9/server.met",
                response(200, "", "through the proxy".getBytes(StandardCharsets.US_ASCII)));
        givenTheServerIsTheHttpProxyOfThePreferences();

        whenTheAddressIsFetched("http://localhost:9/server.met");

        thenTheBodyIs("through the proxy".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void shouldFetchListsThroughTheSocksProxyOfThePreferencesWithItsCredentials() throws IOException {
        givenTheResponse("/server.met", response(200, "", "through socks".getBytes(StandardCharsets.US_ASCII)));
        givenASocksProxyOfThePreferencesThatWantsAPassword();

        whenTheListIsFetchedFrom("/server.met");

        thenTheBodyIs("through socks".getBytes(StandardCharsets.US_ASCII));
        thenTheProxyWasAskedWithTheCredentialsForTheServer();
    }

    @ParameterizedTest
    @ValueSource(ints = {303, 307, 308})
    void shouldFollowEveryKindOfRedirect(int status) {
        givenTheResponse("/server.met", response(200, "", SERVER_LIST));
        givenTheResponse("/moved" + status, response(status, "Location: /server.met\r\n", new byte[0]));

        whenTheListIsFetchedFrom("/moved" + status);

        thenTheBodyIs(SERVER_LIST, "HTTP " + status);
    }

    @Test
    void shouldRefuseAnAddressWithoutScheme() {
        whenTheAddressIsFetched("server.met");

        thenExceptionOccurred(IOException.class, "http and https");
    }

    @Test
    void shouldRefuseAnAddressWithoutHost() {
        whenTheAddressIsFetched("http:/server.met");

        thenExceptionOccurred(IOException.class, "no host");
    }

    @Test
    void shouldRefuseARedirectToABadAddress() {
        givenTheResponse("/bad", response(302, "Location: http://bad host/list\r\n", new byte[0]));

        whenTheListIsFetchedFrom("/bad");

        thenExceptionOccurred(IOException.class, "bad address");
    }

    @Test
    void shouldRefuseARedirectWithoutLocation() {
        givenTheResponse("/nowhere", response(302, "", new byte[0]));

        whenTheListIsFetchedFrom("/nowhere");

        thenExceptionOccurred(IOException.class, "without a location");
    }

    @Test
    void shouldFetchNothingWithoutTimeLeft() {
        givenTheResponse("/server.met", response(200, "", new byte[] {1}));
        givenListsWithTheDeadline(Duration.ZERO);

        whenTheListIsFetchedFrom("/server.met");

        thenExceptionOccurred(IOException.class, "in time");
    }

    @Test
    void shouldFetchNothingForAnInterruptedCaller() throws Exception {
        givenTheResponse("/server.met", response(200, "", new byte[] {1}));

        whenAnInterruptedCallerFetches("/server.met");

        thenTheFetchFailsSaying("interrupted");
        thenTheCallerIsStillInterrupted();
    }

    @Test
    void shouldTakeAGzippedListAsItComesSinceGzipChecksItself() throws IOException {
        givenTheResponse("/packed.gz", untilClosed(gzip(NO_BREAK)));

        whenTheTextIsFetchedFrom("/packed.gz");

        thenTheBodyIs(NO_BREAK, "gzip checks itself");
    }

    @Test
    void shouldTakeAListEndingInACarriageReturnAsItComes() {
        givenTheResponse("/cr.dat", untilClosed(CARRIAGE_RETURN));

        whenTheTextIsFetchedFrom("/cr.dat");

        thenTheBodyIs(CARRIAGE_RETURN);
    }

    @Test
    void shouldTakeAnEmptyListAsItComes() {
        givenTheResponse("/empty.dat", untilClosed(new byte[0]));

        whenTheTextIsFetchedFrom("/empty.dat");

        thenTheBodyIsEmpty();
    }

    @Test
    void shouldTakeAListWhoseLengthWasGivenAsItComes() {
        givenTheResponse("/sized.dat", response(200, "", NO_BREAK));

        whenTheTextIsFetchedFrom("/sized.dat");

        thenTheBodyIs(NO_BREAK, "its length was given");
    }

    @Test
    void shouldTakeAChunkedListAsItComes() {
        givenTheResponse("/chunked.dat", chunked("1d", NO_BREAK));

        whenTheTextIsFetchedFrom("/chunked.dat");

        thenTheBodyIs(NO_BREAK, "chunks end on their own");
    }

    @ParameterizedTest
    @MethodSource("bodiesThatOnlyLookPacked")
    void shouldLeaveAloneBodiesThatOnlyLookPacked(byte[] looksPacked) {
        givenTheResponse("/a", response(200, "", looksPacked));

        whenTheListIsFetchedFrom("/a");

        thenTheBodyIs(looksPacked);
    }

    @Test
    void shouldRefuseAnArchiveWithOnlyFolders() throws IOException {
        givenTheResponse("/folders.zip", response(200, "", zipOfFolders("lists/")));

        whenTheListIsFetchedFrom("/folders.zip");

        thenExceptionOccurred(IOException.class, "empty zip");
    }

    @Test
    void shouldRefuseAGzipBomb() throws IOException {
        givenTheResponse("/bomb.gz", response(200, "", gzippedZerosBeyond(WebLists.MAX_SIZE)));

        whenTheListIsFetchedFrom("/bomb.gz");

        thenExceptionOccurred(IOException.class, "larger than");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
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
            })
    void shouldNotCountAPrivateOrSpecialAddressAsPublic(String literal) throws UnknownHostException {
        givenTheAddress(literal);

        whenItIsAskedWhetherItIsPublic();

        thenItIsPublic(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "100.128.0.1", "2001:4860:4860::8888"})
    void shouldCountAPublicAddress(String literal) throws UnknownHostException {
        givenTheAddress(literal);

        whenItIsAskedWhetherItIsPublic();

        thenItIsPublic(true);
    }

    private void givenTheResponse(String path, byte[] response) {
        this.responses.put(path, response);
    }

    private void givenAStalledResponse(String path, String response) {
        this.responses.put(path, response.getBytes(StandardCharsets.US_ASCII));
        this.stalls.add(path);
    }

    private void givenListsReachingOnlyPublicAddresses() {
        this.lists = WebLists.create();
    }

    private void givenListsWithTheDeadline(Duration deadline) {
        this.lists = new WebLists(a -> true, deadline);
    }

    private void givenTheServerIsTheHttpProxyOfThePreferences() {
        int port = this.server.getLocalPort();
        this.proxyInstalled = true;
        ProxyRoute.install(() -> new ProxySettings(ProxySettings.Type.HTTP, "127.0.0.1", port, "", ""));
        this.lists = new WebLists(a -> true);
    }

    private void givenASocksProxyOfThePreferencesThatWantsAPassword() throws IOException {
        ServerSocket proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.proxies.add(proxy);
        Thread.ofVirtual().start(() -> socks5(proxy));
        this.proxyInstalled = true;
        ProxyRoute.install(
                () -> new ProxySettings(ProxySettings.Type.SOCKS, "127.0.0.1", proxy.getLocalPort(), "me", "secret"));
    }

    private void givenTheAddress(String text) throws UnknownHostException {
        this.literal = text;
        this.address = InetAddress.getByName(text);
    }

    private void whenTheListIsFetchedFrom(String path) {
        fetch(uri(path), false);
    }

    private void whenTheTextIsFetchedFrom(String path) {
        fetch(uri(path), true);
    }

    private void whenTheAddressIsFetched(String uri) {
        fetch(URI.create(uri), false);
    }

    private void whenTheListIsFetchedWithin(Duration limit, String path) {
        assertTimeoutPreemptively(limit, () -> fetch(uri(path), false));
    }

    private void whenRedirected(String from, String location) {
        try {
            this.redirected = WebLists.redirect(URI.create(from), location);
        } catch (IOException e) {
            this.exception = e;
        }
    }

    private void whenAnInterruptedCallerFetches(String path) throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<Boolean> stillInterrupted = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                this.anyAddress.fetch(uri(path));
                failure.complete(null);
            } catch (IOException e) {
                failure.complete(e);
            }
            stillInterrupted.complete(Thread.currentThread().isInterrupted());
        });
        this.exception = failure.get(10, TimeUnit.SECONDS);
        this.callerStillInterrupted = stillInterrupted.get(10, TimeUnit.SECONDS);
    }

    private void whenItIsAskedWhetherItIsPublic() {
        this.isPublic = WebLists.isPublic(this.address);
    }

    private void thenTheBodyIs(byte[] expected) {
        assertThat(this.exception).isNull();
        assertThat(this.body).isEqualTo(expected);
    }

    private void thenTheBodyIs(byte[] expected, String because) {
        assertThat(this.exception).isNull();
        assertThat(this.body).as(because).isEqualTo(expected);
    }

    private void thenTheProxyWasAskedWithTheCredentialsForTheServer() {
        assertThat(this.proxySaw).containsExactly("auth me:secret", "connect 127.0.0.1:" + this.server.getLocalPort());
    }

    private void thenTheBodyIsEmpty() {
        assertThat(this.exception).isNull();
        assertThat(this.body).isEmpty();
    }

    private void thenTheRedirectGoesTo(String expected) {
        assertThat(this.redirected).isEqualTo(URI.create(expected));
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type, String messagePart) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(messagePart);
    }

    private void thenTheFetchFailsSaying(String messagePart) {
        assertThat(this.exception).hasMessageContaining(messagePart);
    }

    private void thenTheFetchFailsSaying(String messagePart, String because) {
        assertThat(this.exception).as(because).hasMessageContaining(messagePart);
    }

    private void thenTheCallerIsStillInterrupted() {
        assertThat(this.callerStillInterrupted).isTrue();
    }

    private void thenItIsPublic(boolean expected) {
        assertThat(this.isPublic).as(this.literal).isEqualTo(expected);
    }

    private void fetch(URI uri, boolean lines) {
        try {
            this.body = lines ? this.lists.fetchText(uri) : this.lists.fetch(uri);
        } catch (IOException | RuntimeException e) {
            this.exception = e;
        }
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

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + this.server.getLocalPort() + path);
    }

    private static Stream<Arguments> bodiesThatOnlyLookPacked() {
        return Stream.of(
                Arguments.of(Named.of("almost gzip", new byte[] {0x1F, 0x00, 'x'})),
                Arguments.of(Named.of("almost zip", new byte[] {'P', 'K', 1, 2, 'x'})),
                Arguments.of(Named.of("almost zip too", new byte[] {'P', 'K', 3, 9, 'x'})),
                Arguments.of(Named.of("too short", new byte[] {'P'})));
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

    // A response of one chunk of {@code hexSize} bytes.
    private static byte[] chunked(String hexSize, byte[] chunk) {
        return concat(
                "HTTP/1.1 200 X\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII),
                (hexSize + "\r\n" + new String(chunk, StandardCharsets.US_ASCII) + "\r\n0\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
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

    private static byte[] gzippedZerosBeyond(long size) throws IOException {
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bomb)) {
            byte[] zeros = new byte[1024 * 1024];
            for (long written = 0; written <= size; written += zeros.length) {
                gz.write(zeros);
            }
        }
        return bomb.toByteArray();
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

    private static byte[] zip(String firstName, byte[] firstData, String secondName, byte[] secondData)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(firstData);
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(secondData);
        }
        return out.toByteArray();
    }

    private static byte[] zipOfFolders(String... folders) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String folder : folders) {
                zip.putNextEntry(new ZipEntry(folder));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
