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

import com.google.common.base.Splitter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Downloads of a file to disk from a small server on the loopback that answers ranges as the test chooses. */
@Timeout(60)
class WebListsDownloadTest {

    private static final byte[] FILE =
            "the package of a new version, ".repeat(400).getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    private final List<String> ranges = new CopyOnWriteArrayList<>();
    private final List<Long> progress = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private volatile Behaviour behaviour = Behaviour.RANGES;
    private volatile long announced = FILE.length;
    private long expected = FILE.length;
    private volatile int sent = FILE.length;
    private boolean redirect;
    private Path part;
    private Exception failure;

    /** How the server answers a request for a range. */
    private enum Behaviour {
        RANGES,
        WHOLE,
        UNSATISFIABLE_ONCE
    }

    @BeforeEach
    void start() throws IOException {
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.part = this.dir.resolve("njmule.zip.part");
        Thread.ofVirtual().start(() -> {
            while (!this.server.isClosed()) {
                try (Socket client = this.server.accept()) {
                    answer(client);
                } catch (IOException | RuntimeException e) {
                    // Closed by the test, or a connection with no request: the next one is served all the same.
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        this.server.close();
    }

    @Test
    void shouldDownloadAFileIntoItsPart() {
        whenDownloaded();

        thenNoFailureOccurred();
        thenThePartHolds(FILE);
        thenTheProgressEndedAt(FILE.length);
        thenTheRangesAskedWere();
    }

    @Test
    void shouldGoOnFromTheBytesThePartHolds() throws IOException {
        givenThePartHolds(1000);

        whenDownloaded();

        thenNoFailureOccurred();
        thenThePartHolds(FILE);
        thenTheRangesAskedWere("bytes=1000-");
    }

    @Test
    void shouldFollowARedirectWithTheRange() throws IOException {
        givenThePartHolds(1000);
        givenTheFileMoved();

        whenDownloaded();

        thenNoFailureOccurred();
        thenThePartHolds(FILE);
        thenTheRangesAskedWere("bytes=1000-", "bytes=1000-");
    }

    @Test
    void shouldStartAgainWhenTheServerSendsTheWholeFile() throws IOException {
        givenThePartHolds(1000);
        givenTheServer(Behaviour.WHOLE);

        whenDownloaded();

        thenNoFailureOccurred();
        thenThePartHolds(FILE);
    }

    @Test
    void shouldStartAgainWhenTheRangeNoLongerFits() throws IOException {
        givenThePartHolds(1000);
        givenTheServer(Behaviour.UNSATISFIABLE_ONCE);

        whenDownloaded();

        thenNoFailureOccurred();
        thenThePartHolds(FILE);
        thenTheRangesAskedWere("bytes=1000-");
    }

    @Test
    void shouldNotAskForAFileThePartHoldsWhole() throws IOException {
        givenThePartHolds(FILE.length);

        whenDownloaded();

        thenNoFailureOccurred();
        thenNothingWasAsked();
    }

    @Test
    void shouldRefuseAFileThatSaysItIsLonger() {
        givenTheFileIsExpectedToHave(FILE.length - 1);

        whenDownloaded();

        thenItFailedWith("longer than");
    }

    @Test
    void shouldRefuseAFileThatTurnsOutLonger() {
        givenTheServerAnnouncesNoLength();
        givenTheFileIsExpectedToHave(FILE.length - 1);

        whenDownloaded();

        thenItFailedWith("longer than");
    }

    @Test
    void shouldLeaveWhatCameWhenTheFileIsCutShort() throws IOException {
        givenTheServerSendsOnly(1000);

        whenDownloaded();

        thenAFailureOccurred();
        thenThePartHoldsBytes(1000);
    }

    private void givenThePartHolds(int bytes) throws IOException {
        Files.write(this.part, Arrays.copyOf(FILE, bytes));
    }

    private void givenTheFileMoved() {
        this.redirect = true;
    }

    private void givenTheServer(Behaviour chosen) {
        this.behaviour = chosen;
    }

    private void givenTheFileIsExpectedToHave(long bytes) {
        this.expected = bytes;
    }

    private void givenTheServerAnnouncesNoLength() {
        this.announced = -1;
    }

    private void givenTheServerSendsOnly(int bytes) {
        this.sent = bytes;
    }

    private void whenDownloaded() {
        URI uri = URI.create("http://127.0.0.1:" + this.server.getLocalPort() + (this.redirect ? "/old" : "/file"));
        try {
            new WebLists(a -> true).download(uri, this.part, this.expected, this.progress::add);
        } catch (IOException e) {
            this.failure = e;
        }
    }

    private void thenNoFailureOccurred() {
        assertThat(this.failure).isNull();
    }

    private void thenAFailureOccurred() {
        assertThat(this.failure).isInstanceOf(IOException.class);
    }

    private void thenItFailedWith(String words) {
        assertThat(this.failure).isInstanceOf(IOException.class).hasMessageContaining(words);
    }

    private void thenThePartHolds(byte[] expectedBytes) {
        assertThat(this.part).hasBinaryContent(expectedBytes);
    }

    private void thenThePartHoldsBytes(long bytes) throws IOException {
        assertThat(Files.size(this.part)).isEqualTo(bytes);
    }

    private void thenTheProgressEndedAt(long bytes) {
        assertThat(this.progress).last().isEqualTo(bytes);
    }

    private void thenTheRangesAskedWere(String... asked) {
        assertThat(this.ranges).containsExactly(asked);
    }

    private void thenNothingWasAsked() {
        assertThat(this.ranges).isEmpty();
        assertThat(this.progress).containsExactly((long) FILE.length);
    }

    // One request a connection: the path and the range, then the answer the test chose.
    private void answer(Socket client) throws IOException {
        BufferedReader in =
                new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String request = in.readLine();
        if (request == null) {
            // The client opened the connection and closed it unused.
            return;
        }
        String path = Splitter.on(' ').splitToList(request).get(1);
        long from = 0;
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("range:")) {
                String range = line.substring(6).strip();
                this.ranges.add(range);
                from = Long.parseLong(range.substring("bytes=".length(), range.indexOf('-')));
            }
        }
        OutputStream out = client.getOutputStream();
        if (path.equals("/old")) {
            out.write("HTTP/1.1 302 Found\r\nLocation: /file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (from > 0 && this.behaviour == Behaviour.UNSATISFIABLE_ONCE) {
            this.behaviour = Behaviour.RANGES;
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + FILE.length
                            + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return;
        }
        boolean partial = from > 0 && this.behaviour == Behaviour.RANGES;
        int start = partial ? (int) from : 0;
        StringBuilder head = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        if (partial) {
            head.append("Content-Range: bytes ")
                    .append(start)
                    .append('-')
                    .append(FILE.length - 1)
                    .append('/')
                    .append(FILE.length)
                    .append("\r\n");
        }
        if (this.announced >= 0) {
            head.append("Content-Length: ").append(this.announced - start).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(FILE, start, Math.max(0, this.sent - start));
        out.flush();
    }
}
