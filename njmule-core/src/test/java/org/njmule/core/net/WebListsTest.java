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

import com.google.common.base.Splitter;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class WebListsTest {

    // Path -> the whole response, status line to body.
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    private ServerSocket server;
    private final WebLists anyAddress = new WebLists(a -> true);

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
                } catch (IOException e) {
                    // Closed by the test.
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        this.server.close();
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
            "fe80::1"
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
