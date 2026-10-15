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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.njmule.core.api.EngineConfig;
import org.njmule.core.api.NjMuleEngine;
import org.njmule.core.config.AppDirectories;

/**
 * What the HTTP server itself does for callers: it names no software, shows no stack trace for a bad request, and gets
 * the answer to a refused request across whenever its body comes.
 */
@Timeout(60)
class WebUiServerTest {

    private static final String LOGIN_BODY = "password=correct+horse";
    private static final Pattern CONTENT_LENGTH = Pattern.compile("(?i)\r\ncontent-length: *(\\d+)\r\n");

    @TempDir
    Path dir;

    private NjMuleEngine engine;
    private WebUi ui;
    private Socket connection;
    private String refusal;
    private String answer;

    @AfterEach
    void stop() throws IOException {
        if (this.connection != null) {
            this.connection.close();
        }
        if (this.ui != null) {
            this.ui.close();
        }
        if (this.engine != null) {
            this.engine.close();
        }
    }

    @Test
    void shouldNameNoServerSoftwareInAnAnswer() throws Exception {
        givenTheWebInterface();
        whenAsked("GET /login HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        thenTheAnswerIs("200");
        thenTheAnswerNamesNoServerSoftware();
    }

    @Test
    void shouldAnswerABadRequestWithoutAStackTrace() throws Exception {
        givenTheWebInterface();
        whenAsked("GET /%2e%2e/%2e%2e/secret HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        thenTheAnswerIs("400");
        thenTheAnswerNamesNoServerSoftware();
    }

    @Test
    void shouldReadTheBodyOfARefusedPostThatComesAfterTheAnswer() throws Exception {
        givenTheWebInterface();
        givenACrossSitePostAnsweredBeforeItsBody();
        whenTheBodyComesWithAnotherRequest();
        thenTheRefusalIs("403");
        thenTheAnswerIs("200");
    }

    @Test
    void shouldSendNoContinueAfterRefusingAPostWaitingForIt() throws Exception {
        givenTheWebInterface();
        whenACrossSitePostWaitingForContinueIsSent();
        thenTheAnswerIs("403");
        thenTheAnswerHoldsOneStatus();
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

    // The body is sent only once the answer is read, the order a busy computer may give any client.
    private void givenACrossSitePostAnsweredBeforeItsBody() throws IOException {
        this.connection = new Socket(InetAddress.getLoopbackAddress(), this.ui.port());
        send(crossSitePost(""));
        this.refusal = readAnswer();
    }

    private void whenACrossSitePostWaitingForContinueIsSent() throws IOException {
        whenAsked(crossSitePost("Expect: 100-continue\r\n"));
    }

    private void whenTheBodyComesWithAnotherRequest() throws IOException {
        send(LOGIN_BODY + "GET /login HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
        this.answer = new String(this.connection.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }

    private void thenTheRefusalIs(String status) {
        assertThat(this.refusal).startsWith("HTTP/1.1 " + status + " ");
    }

    private void thenTheAnswerIs(String status) {
        assertThat(this.answer).startsWith("HTTP/1.1 " + status + " ");
    }

    private void thenTheAnswerHoldsOneStatus() {
        assertThat(this.answer.split("HTTP/1.1 ", -1)).hasSize(2);
    }

    private static String crossSitePost(String headers) {
        return "POST /login HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://elsewhere.example\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: " + LOGIN_BODY.length()
                + "\r\n" + headers + "\r\n";
    }

    private void send(String bytes) throws IOException {
        OutputStream out = this.connection.getOutputStream();
        out.write(bytes.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    // One answer of the connection: its head, then as many bytes as its Content-Length says.
    private String readAnswer() throws IOException {
        InputStream in = this.connection.getInputStream();
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            assertThat(b).as("an answer ending early: %s", head).isNotNegative();
            head.append((char) b);
        }
        Matcher length = CONTENT_LENGTH.matcher(head);
        assertThat(length.find()).as("a Content-Length in %s", head).isTrue();
        byte[] body = in.readNBytes(Integer.parseInt(length.group(1)));
        return head + new String(body, StandardCharsets.ISO_8859_1);
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
