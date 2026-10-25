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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.njmule.core.api.DownloadInfo;
import org.njmule.core.api.DownloadPriority;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.api.PartMap;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.Notifications;

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
    private Throwable exception;

    @AfterEach
    void stop() throws IOException {
        if (this.smtp != null) {
            this.smtp.close();
        }
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

    private void givenTheSettings(Notifications value) {
        this.settings = value;
    }

    private void givenTheMailFails(String why) {
        this.mail = (n, title, text) -> {
            throw new IOException(why);
        };
    }

    // Greets and answers EHLO without offering STARTTLS; it takes anything that follows as the message.
    private void givenAnSmtpServerWithoutStartTls() throws IOException {
        this.smtp = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket client = this.smtp.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))) {
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
            } catch (IOException e) {
                // The client went away.
            }
        });
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

    private void whenMailed() {
        Notifications n = new Notifications(
                true,
                "127.0.0.1",
                this.smtp.getLocalPort(),
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
