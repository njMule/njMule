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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/** What the HTTP server itself tells callers: nothing of its software, and no stack trace when a request is bad. */
@Timeout(60)
class WebUiServerTest {

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private WebUi ui;
    private String answer;

    @AfterEach
    void stop() {
        if (this.ui != null) {
            this.ui.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void anAnswerNamesNoServerSoftware() throws Exception {
        this.givenTheWebInterface();
        this.whenAsked("GET /login HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        this.thenTheAnswerIs("200");
        this.thenTheAnswerNamesNoServerSoftware();
    }

    @Test
    void aBadRequestGetsNoStackTrace() throws Exception {
        this.givenTheWebInterface();
        this.whenAsked("GET /%2e%2e/%2e%2e/secret HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        this.thenTheAnswerIs("400");
        this.thenTheAnswerNamesNoServerSoftware();
    }

    private void givenTheWebInterface() throws IOException {
        AppDirectories dirs = AppDirectories.underRoot(this.dir.resolve("njmule"), true);
        Files.createDirectories(dirs.config());
        Files.writeString(
                dirs.config().resolve("njmule.properties"),
                "net.tcpPort=" + freePort() + "\nnet.ed2k.enabled=false\nnet.kad.enabled=false\n");
        this.engine = NjMuleEngine.start(new EngineConfig(dirs));
        String hash = Passwords.hash("correct horse".toCharArray(), new byte[16], 1000);
        this.ui = WebUi.start(this.engine, new WebUiSettings(true, freePort(), false, hash));
    }

    // Raw bytes, as a caller may send what no well-behaved client would.
    private void whenAsked(String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), this.ui.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            this.answer = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private void thenTheAnswerIs(String status) {
        assertThat(this.answer).startsWith("HTTP/1.1 " + status + " ");
    }

    private void thenTheAnswerNamesNoServerSoftware() {
        String lower = this.answer.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("server:").doesNotContain("jetty").doesNotContain("at org.");
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
