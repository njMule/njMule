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
package org.njmule.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.api.PartMap;
import org.njmule.core.config.BindInterface;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.Notifications;
import org.njmule.core.config.ProxySettings;
import org.njmule.core.net.HttpRoute;
import org.njmule.core.net.ProxyRoute;
import org.njmule.core.net.TcpConnector;

@Timeout(30)
class NotifierTest {

    private static final Hash16 HASH = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    private static final String NAME = "debian.iso";

    private final List<String> mailed = new CopyOnWriteArrayList<>();
    private final List<String> hooked = new CopyOnWriteArrayList<>();
    private Notifications settings = on(true, true, Notifications.NONE.events());
    private Notifier.Channel mail = (n, title, text) -> this.mailed.add(title + "|" + firstPart(text));
    private List<NotificationResult> results = List.of();
    private ServerSocket smtp;
    private final AtomicBoolean dataSent = new AtomicBoolean();
    private final List<String> greetings = new CopyOnWriteArrayList<>();
    private final AtomicBoolean reached = new AtomicBoolean();
    private ServerSocket proxy;
    private final List<String> proxySaw = new CopyOnWriteArrayList<>();
    private ServerSocket hook;
    private final List<String> hookGot = new CopyOnWriteArrayList<>();
    private Throwable exception;

    @AfterEach
    void stop() throws IOException {
        if (this.smtp != null) {
            this.smtp.close();
        }
        if (this.proxy != null) {
            this.proxy.close();
        }
        if (this.hook != null) {
            this.hook.close();
        }
        ProxyRoute.install(() -> ProxySettings.NONE);
        Authenticator.setDefault(null);
        TcpConnector.useBinding(BindInterface.Binding.Any::new);
    }

    @Test
    void shouldTellACompletedDownloadThroughEachChannelOn() {
        whenTold(completed());

        thenTheMailsAre("njMule: Download completed|" + NAME);
        thenTheHookCallsAre("njMule: Download completed|" + NAME);
    }

    @Test
    void shouldTellNothingOfAnEventNotChosen() {
        whenTold(new EngineEvent.MessageReceived("192.0.2.1:4662", HASH, Optional.of("ann"), "hello"));

        thenTheMailsAre();
        thenTheHookCallsAre();
    }

    @Test
    void shouldTellOnlyThroughTheChannelsOn() {
        givenTheSettings(on(false, true, Set.of(EventCommands.Event.CHAT_MESSAGE)));

        whenTold(new EngineEvent.MessageReceived("192.0.2.1:4662", HASH, Optional.of("ann"), "hello"));

        thenTheMailsAre();
        thenTheHookCallsAre("njMule: Chat message|ann:\nhello");
    }

    @Test
    void shouldDropTheMessagesBeyondTheBurst() {
        whenToldTimes(completed(), Notifier.BURST + 3);

        thenTheHookWasCalled(Notifier.BURST);
    }

    @Test
    void shouldTellHowTheTestWentOnEachChannel() throws Exception {
        givenTheMailFails("the server refused the password");

        whenTested();

        thenTheResultsAre(
                new NotificationResult("email", Optional.of("the server refused the password")),
                new NotificationResult("webhook", Optional.empty()));
    }

    @Test
    void shouldNeverSendTheMailInTheClearToAServerWithoutStartTls() throws Exception {
        givenAnSmtpServerWithoutStartTls();

        whenMailed();

        thenExceptionOccurred(IOException.class, "STARTTLS");
        thenNoMessageWasSent();
        thenTheGreetingNamedNoComputer();
    }

    @Test
    void shouldCallTheWebHookThroughTheHttpProxyOfThePreferences() throws Exception {
        givenAnHttpProxyOfThePreferences();
        givenTheSettings(channels(false, 25, true, "https://192.0.2.80/notify"));

        whenTestedForReal();

        thenTheProxySaw("CONNECT 192.0.2.80:443 HTTP/1.1");
    }

    @Test
    void shouldSendTheMailThroughTheSocksProxyOfThePreferencesWithItsCredentials() throws Exception {
        givenASocksProxyOfThePreferencesBeforeAnSmtpServerWithoutStartTls("me", "secret");

        whenMailedTo("192.0.2.25", 587);

        thenExceptionOccurred(IOException.class, "STARTTLS");
        thenTheProxySaw("auth me:secret", "connect 192.0.2.25:587");
        thenNoMessageWasSent();
    }

    @Test
    void shouldSendTheMailThroughTheHttpProxyOfThePreferences() throws Exception {
        givenAnHttpProxyOfThePreferencesBeforeAnSmtpServerWithoutStartTls();

        whenMailedTo("192.0.2.25", 587);

        thenExceptionOccurred(IOException.class, "STARTTLS");
        thenTheProxySaw("CONNECT 192.0.2.25:587 HTTP/1.1");
    }

    @Test
    void shouldSendTheMailFromTheChosenInterfaceOnly() throws Exception {
        givenAnSmtpServerWithoutStartTls();
        givenTheChosenInterfaceHasAnAddressThisComputerLacks();

        whenMailedTo("127.0.0.1", this.smtp.getLocalPort());

        thenTheMailFailed();
        thenNothingReachedTheServer();
    }

    @Test
    void shouldPostTheTextWithItsTitleToTheWebHook() throws Exception {
        givenAWebHookAnswering(200);

        whenPostedAs(Notifications.WebhookFormat.TEXT);

        thenNoExceptionOccurred();
        thenTheWebHookGot("POST /notify HTTP/1.1", "Title: njMule: Test", "text/plain", "test");
    }

    @Test
    void shouldPostTheTitleAndTheMessageAsJson() throws Exception {
        givenAWebHookAnswering(200);

        whenPostedAs(Notifications.WebhookFormat.JSON);

        thenNoExceptionOccurred();
        thenTheWebHookGot("application/json", "\"title\":\"njMule: Test\"", "\"message\":\"test\"");
    }

    @Test
    void shouldAskForTheAddressOfTheWebHook() throws Exception {
        givenTheSettings(channels(false, 25, true, ""));

        whenTestedForReal();

        thenTheResultsAre(new NotificationResult("webhook", Optional.of("Set the address of the web hook.")));
    }

    @Test
    void shouldFailOnAWebHookThatAnswersAnError() throws Exception {
        givenAWebHookAnswering(500);

        whenPostedAs(Notifications.WebhookFormat.TEXT);

        thenExceptionOccurred(IOException.class, "answered HTTP 500");
    }

    @Test
    void shouldSendNothingWhileTheChosenInterfaceIsGone() throws Exception {
        givenAnSmtpServerWithoutStartTls();
        givenTheChosenInterfaceIsGone("wg0");
        givenTheSettings(
                channels(true, this.smtp.getLocalPort(), true, "https://127.0.0.1:" + this.smtp.getLocalPort()));

        whenTestedForReal();

        thenEachResultFailedFor("wg0");
        thenNothingReachedTheServer();
    }

    private void givenTheSettings(Notifications value) {
        this.settings = value;
    }

    private void givenTheMailFails(String why) {
        this.mail = (n, title, text) -> {
            throw new IOException(why);
        };
    }

    private void givenAnSmtpServerWithoutStartTls() throws IOException {
        this.smtp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket client = this.smtp.accept()) {
                this.reached.set(true);
                talkSmtpWithoutStartTls(client);
            } catch (IOException e) {
                // The client went away, or the test closed the server.
            }
        });
    }

    // Notes the tunnel asked for and refuses it: where the hook was to be reached is all the test needs to see.
    private void givenAnHttpProxyOfThePreferences() throws IOException {
        this.proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = this.proxy.getLocalPort();
        ProxyRoute.install(() -> new ProxySettings(ProxySettings.Type.HTTP, "127.0.0.1", port, "", ""));
        Thread.ofVirtual().start(() -> {
            try (Socket client = this.proxy.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {
                this.proxySaw.add(in.readLine());
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    // The headers.
                }
                client.getOutputStream()
                        .write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // The client went away, or the test closed the proxy.
            }
        });
    }

    // Asks for the user name and password, then takes the connection as the SMTP server it was asked for.
    private void givenASocksProxyOfThePreferencesBeforeAnSmtpServerWithoutStartTls(String user, String password)
            throws IOException {
        this.proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = this.proxy.getLocalPort();
        ProxyRoute.install(() -> new ProxySettings(ProxySettings.Type.SOCKS, "127.0.0.1", port, user, password));
        Thread.ofVirtual().start(() -> {
            try (Socket client = this.proxy.accept()) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                OutputStream out = client.getOutputStream();
                in.readUnsignedByte();
                in.readFully(new byte[in.readUnsignedByte()]);
                out.write(new byte[] {5, 2});
                in.readUnsignedByte();
                String name = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                String secret = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                this.proxySaw.add("auth " + name + ":" + secret);
                out.write(new byte[] {1, 0});
                in.readFully(new byte[3]);
                int kind = in.readUnsignedByte();
                String host = kind == 3
                        ? new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII)
                        : InetAddress.getByAddress(in.readNBytes(kind == 1 ? 4 : 16))
                                .getHostAddress();
                this.proxySaw.add("connect " + host + ":" + in.readUnsignedShort());
                out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                talkSmtpWithoutStartTls(client);
            } catch (IOException e) {
                // The client went away, or the test closed the proxy.
            }
        });
    }

    // Takes the tunnel asked for, then the connection as the SMTP server it was asked for.
    private void givenAnHttpProxyOfThePreferencesBeforeAnSmtpServerWithoutStartTls() throws IOException {
        this.proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = this.proxy.getLocalPort();
        ProxyRoute.install(() -> new ProxySettings(ProxySettings.Type.HTTP, "127.0.0.1", port, "", ""));
        Thread.ofVirtual().start(() -> {
            try (Socket client = this.proxy.accept()) {
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                this.proxySaw.add(in.readLine());
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    // The headers; the client says nothing more before the server greets it.
                }
                client.getOutputStream()
                        .write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                talkSmtpWithoutStartTls(client);
            } catch (IOException e) {
                // The client went away, or the test closed the proxy.
            }
        });
    }

    // Notes the request, its headers and its body, and answers with {@code status}.
    private void givenAWebHookAnswering(int status) throws IOException {
        this.hook = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket client = this.hook.accept()) {
                BufferedReader in =
                        new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                int length = 0;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    this.hookGot.add(line);
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(
                                line.substring(line.indexOf(':') + 1).strip());
                    }
                }
                char[] body = new char[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                this.hookGot.add(new String(body, 0, read));
                client.getOutputStream()
                        .write(("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // The client went away, or the test closed the server.
            }
        });
    }

    // 192.0.2.1 is kept for documentation: no computer has it.
    private void givenTheChosenInterfaceHasAnAddressThisComputerLacks() throws IOException {
        Inet4Address address = (Inet4Address) InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1});
        TcpConnector.useBinding(() -> new BindInterface.Binding.Bound(address));
    }

    private void givenTheChosenInterfaceIsGone(String name) {
        TcpConnector.useBinding(() -> new BindInterface.Binding.Missing(name));
    }

    private void whenTold(EngineEvent event) {
        notifier().handle(event);
    }

    private void whenToldTimes(EngineEvent event, int times) {
        Notifier notifier = notifier();
        for (int i = 0; i < times; i++) {
            notifier.handle(event);
        }
    }

    private void whenTested() throws InterruptedException {
        this.results = notifier().test();
    }

    private void whenTestedForReal() throws InterruptedException {
        Notifications n = this.settings;
        this.results = Notifier.create(() -> n).test();
    }

    private void whenPostedAs(Notifications.WebhookFormat body) throws IOException {
        URI address = URI.create("http://127.0.0.1:" + this.hook.getLocalPort() + "/notify");
        HttpClient http = HttpRoute.start();
        try {
            this.exception = catchThrowable(() -> Notifier.post(http, address, body, "njMule: Test", "test"));
        } finally {
            HttpRoute.stop(http);
        }
    }

    private void whenMailed() {
        whenMailedTo("127.0.0.1", this.smtp.getLocalPort());
    }

    private void whenMailedTo(String host, int port) {
        Notifications n = new Notifications(
                true,
                host,
                port,
                Notifications.SmtpSecurity.STARTTLS,
                "",
                "",
                "njmule@example.org",
                "me@example.org",
                false,
                "",
                Notifications.WebhookFormat.TEXT,
                Notifications.NONE.events());
        this.exception = catchThrowable(() -> Notifier.sendMail(n, "njMule: Test", "test"));
    }

    private void thenTheMailsAre(String... mails) {
        assertThat(this.mailed).containsExactly(mails);
    }

    private void thenTheHookCallsAre(String... calls) {
        assertThat(this.hooked).containsExactly(calls);
    }

    private void thenTheHookWasCalled(int times) {
        assertThat(this.hooked).hasSize(times);
    }

    private void thenTheResultsAre(NotificationResult... expected) {
        assertThat(this.results).containsExactly(expected);
    }

    private void thenExceptionOccurred(Class<? extends Throwable> type, String part) {
        assertThat(this.exception).isInstanceOf(type).hasMessageContaining(part);
    }

    private void thenTheGreetingNamedNoComputer() {
        assertThat(this.greetings).containsOnly("EHLO [127.0.0.1]");
    }

    private void thenNoMessageWasSent() {
        assertThat(this.dataSent).isFalse();
    }

    private void thenTheProxySaw(String... lines) {
        assertThat(this.proxySaw).containsExactly(lines);
    }

    private void thenEachResultFailedFor(String part) {
        assertThat(this.results).extracting(NotificationResult::channel).containsExactly("email", "webhook");
        assertThat(this.results)
                .allSatisfy(result -> assertThat(result.error())
                        .hasValueSatisfying(error -> assertThat(error).contains(part)));
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenTheMailFailed() {
        assertThat(this.exception).isInstanceOf(IOException.class);
    }

    private void thenTheWebHookGot(String... parts) {
        assertThat(String.join("\n", this.hookGot)).contains(parts);
    }

    private void thenNothingReachedTheServer() {
        assertThat(this.reached).isFalse();
    }

    private Notifier notifier() {
        Notifications n = this.settings;
        List<Long> ticks = new ArrayList<>();
        return new Notifier(
                () -> n,
                (s, title, text) -> this.mail.send(s, title, text),
                (s, title, text) -> this.hooked.add(title + "|" + firstPart(text)),
                Runnable::run,
                () -> {
                    ticks.add(0L);
                    return ticks.size();
                });
    }

    // Greets and answers EHLO without offering STARTTLS; it takes anything that follows as the message.
    private void talkSmtpWithoutStartTls(Socket client) throws IOException {
        BufferedReader in =
                new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
        OutputStream out = client.getOutputStream();
        out.write("220 test ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
        String line;
        while ((line = in.readLine()) != null) {
            String command = line.toUpperCase(Locale.ROOT);
            if (command.startsWith("EHLO")) {
                this.greetings.add(line);
                out.write("250 test\r\n".getBytes(StandardCharsets.US_ASCII));
            } else if (command.startsWith("DATA")) {
                this.dataSent.set(true);
                out.write("354 go on\r\n".getBytes(StandardCharsets.US_ASCII));
            } else if (command.startsWith("QUIT")) {
                out.write("221 bye\r\n".getBytes(StandardCharsets.US_ASCII));
                return;
            } else {
                out.write("250 ok\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    private static Notifications channels(boolean email, int smtpPort, boolean webhook, String hook) {
        return new Notifications(
                email,
                "127.0.0.1",
                smtpPort,
                Notifications.SmtpSecurity.STARTTLS,
                "",
                "",
                "",
                "me@example.org",
                webhook,
                hook,
                Notifications.WebhookFormat.TEXT,
                Notifications.NONE.events());
    }

    // The completed download's text goes on with its size and file, which the test leaves out.
    private static String firstPart(String text) {
        int cut = text.indexOf(" (");
        return cut < 0 ? text : text.substring(0, cut);
    }

    private static EngineEvent completed() {
        DownloadInfo download = new DownloadInfo(
                HASH,
                NAME,
                1234,
                1234,
                DownloadInfo.Status.COMPLETE,
                DownloadPriority.NORMAL,
                0,
                0,
                0,
                0,
                PartMap.EMPTY);
        return new EngineEvent.DownloadCompleted(download, Path.of("incoming", NAME));
    }

    private static Notifications on(boolean email, boolean webhook, Set<EventCommands.Event> events) {
        return new Notifications(
                email,
                "smtp.example.org",
                Notifications.DEFAULT_SMTP_PORT,
                Notifications.SmtpSecurity.STARTTLS,
                "",
                "",
                "",
                "me@example.org",
                webhook,
                "https://ntfy.example.org/njmule",
                Notifications.WebhookFormat.TEXT,
                events);
    }
}
