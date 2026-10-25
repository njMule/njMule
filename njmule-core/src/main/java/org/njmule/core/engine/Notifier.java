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

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.NotificationResult;
import org.njmule.core.config.EventCommands;
import org.njmule.core.config.Notifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells of engine events by e-mail and through a web hook, as the notification preferences say. Each message goes on a
 * thread of its own, a few at once, and at most {@value #BURST} in {@link #WINDOW}: a flood of chat messages does not
 * become a flood of e-mails.
 */
final class Notifier implements Flow.Subscriber<EngineEvent> {

    /** Sends one message through one channel. */
    @FunctionalInterface
    interface Channel {
        void send(Notifications settings, String title, String text) throws IOException, InterruptedException;
    }

    /** Messages told in {@link #WINDOW} at most; those beyond are dropped. */
    static final int BURST = 10;

    static final Duration WINDOW = Duration.ofMinutes(10);

    private static final Logger logger = LoggerFactory.getLogger(Notifier.class);
    private static final int MAX_SENDING = 2;
    private static final Duration NETWORK_TIME = Duration.ofSeconds(30);
    private static final String EMAIL = "email";
    private static final String WEBHOOK = "webhook";

    private final Supplier<Notifications> settings;
    private final Channel mail;
    private final Channel hook;
    private final Executor executor;
    private final LongSupplier ticker;
    private final Semaphore sending = new Semaphore(MAX_SENDING);
    // When the latest messages were told, oldest first.
    private final Deque<Long> told = new ArrayDeque<>();
    private boolean dropping;

    /**
     * @param executor sends each message, on a thread that may wait for the network
     * @param ticker monotonic nanoseconds
     */
    Notifier(Supplier<Notifications> settings, Channel mail, Channel hook, Executor executor, LongSupplier ticker) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.mail = Objects.requireNonNull(mail, "mail");
        this.hook = Objects.requireNonNull(hook, "hook");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** A notifier that sends e-mails through Jakarta Mail and web hook requests through an HTTP client of their own. */
    static Notifier create(Supplier<Notifications> settings) {
        return new Notifier(
                settings,
                Notifier::sendMail,
                (n, title, text) -> {
                    try (HttpClient http =
                            HttpClient.newBuilder().connectTimeout(NETWORK_TIME).build()) {
                        callHook(http, n, title, text);
                    }
                },
                task -> Thread.ofVirtual().name("njmule-notify").start(task),
                System::nanoTime);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(EngineEvent event) {
        handle(event);
    }

    @Override
    public void onError(Throwable throwable) {
        logger.debug("No more events for the notifications: {}", throwable.toString());
    }

    @Override
    public void onComplete() {
        // The engine stopped: messages on their way go on by themselves.
    }

    /** Tells of {@code event}, when it is one the preferences choose. */
    void handle(EngineEvent event) {
        switch (event) {
            case EngineEvent.DownloadCompleted e ->
                tell(
                        EventCommands.Event.DOWNLOAD_COMPLETED,
                        "Download completed",
                        e.download().name() + " (" + mebibytes(e.download().size()) + ") is complete:\n"
                                + e.file().toAbsolutePath());
            case EngineEvent.DownloadFailed e ->
                tell(
                        EventCommands.Event.DOWNLOAD_FAILED,
                        "Download failed",
                        e.download().name() + ":\n" + e.reason());
            case EngineEvent.MessageReceived e ->
                tell(EventCommands.Event.CHAT_MESSAGE, "Chat message", e.nick().orElse(e.address()) + ":\n" + e.text());
            case EngineEvent.DiskSpace e
            when !e.enough() ->
                tell(
                        EventCommands.Event.DISK_SPACE_SHORT,
                        "Disk space short",
                        "The downloads wait: " + e.directory().toAbsolutePath() + " is short of space.");
            default -> {
                // Nothing is told of the other events.
            }
        }
    }

    /** Sends a test message through each channel that is on; empty when none is. */
    List<NotificationResult> test() throws InterruptedException {
        Notifications n = this.settings.get();
        List<NotificationResult> results = new ArrayList<>();
        String text = "This is a test of the notifications of njMule.";
        if (n.email()) {
            results.add(attempt(EMAIL, this.mail, n, text));
        }
        if (n.webhook()) {
            results.add(attempt(WEBHOOK, this.hook, n, text));
        }
        return results;
    }

    private static NotificationResult attempt(String channel, Channel through, Notifications n, String text)
            throws InterruptedException {
        try {
            through.send(n, title("Test"), text);
            return new NotificationResult(channel, Optional.empty());
        } catch (IOException | RuntimeException e) {
            return new NotificationResult(channel, Optional.of(reason(e)));
        }
    }

    private void tell(EventCommands.Event event, String title, String text) {
        Notifications n = this.settings.get();
        if (!n.tells(event) || !(n.email() || n.webhook()) || !admit()) {
            return;
        }
        if (n.email()) {
            send(EMAIL, this.mail, n, title(title), text);
        }
        if (n.webhook()) {
            send(WEBHOOK, this.hook, n, title(title), text);
        }
    }

    private void send(String channel, Channel through, Notifications n, String title, String text) {
        if (!this.sending.tryAcquire()) {
            logger.warn(
                    "{} notifications are still on their way: the {} of \"{}\" is dropped",
                    MAX_SENDING,
                    channel,
                    title);
            return;
        }
        try {
            this.executor.execute(() -> {
                try {
                    through.send(n, title, text);
                } catch (IOException | RuntimeException e) {
                    logger.warn("Cannot send the {} of \"{}\": {}", channel, title, reason(e));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    this.sending.release();
                }
            });
        } catch (RejectedExecutionException e) {
            this.sending.release();
        }
    }

    // At most BURST messages in WINDOW; the first one dropped says so.
    private synchronized boolean admit() {
        long now = this.ticker.getAsLong();
        while (!this.told.isEmpty() && now - this.told.peekFirst() >= WINDOW.toNanos()) {
            this.told.pollFirst();
        }
        if (this.told.size() >= BURST) {
            if (!this.dropping) {
                logger.warn(
                        "More than {} notifications in {} minutes: the next ones are dropped",
                        BURST,
                        WINDOW.toMinutes());
                this.dropping = true;
            }
            return false;
        }
        this.dropping = false;
        this.told.addLast(now);
        return true;
    }

    // A refused connection, for one, has no message of its own.
    private static String reason(Exception e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }

    private static String title(String what) {
        return "njMule: " + what;
    }

    private static String mebibytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    // Never in the clear: STARTTLS is required, or TLS from the start, and the server's name is checked.
    static void sendMail(Notifications n, String title, String text) throws IOException {
        List<String> to = n.recipients();
        if (n.smtpHost().isEmpty() || to.isEmpty()) {
            throw new IOException("Set the SMTP server and the addresses to send to.");
        }
        boolean tls = n.security() == Notifications.SmtpSecurity.TLS;
        String protocol = tls ? "smtps" : "smtp";
        String prefix = "mail." + protocol + ".";
        Properties properties = new Properties();
        properties.setProperty(prefix + "host", n.smtpHost());
        properties.setProperty(prefix + "port", String.valueOf(n.smtpPort()));
        properties.setProperty(prefix + "auth", String.valueOf(!n.smtpUser().isEmpty()));
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        // The greeting names an address literal rather than this computer, whose name may be its owner's.
        properties.setProperty(prefix + "localhost", "[127.0.0.1]");
        String millis = String.valueOf(NETWORK_TIME.toMillis());
        properties.setProperty(prefix + "connectiontimeout", millis);
        properties.setProperty(prefix + "timeout", millis);
        properties.setProperty(prefix + "writetimeout", millis);
        if (!tls) {
            properties.setProperty(prefix + "starttls.enable", "true");
            properties.setProperty(prefix + "starttls.required", "true");
        }
        Session session = Session.getInstance(properties);
        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(n.mailFrom().isEmpty() ? to.getFirst() : n.mailFrom(), true));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(n.mailTo(), true));
            message.setSubject(title, StandardCharsets.UTF_8.name());
            message.setText(text, StandardCharsets.UTF_8.name());
            message.setSentDate(Date.from(Instant.now()));
            try (Transport transport = session.getTransport(protocol)) {
                if (n.smtpUser().isEmpty()) {
                    transport.connect();
                } else {
                    transport.connect(n.smtpHost(), n.smtpPort(), n.smtpUser(), n.smtpPassword());
                }
                transport.sendMessage(message, message.getAllRecipients());
            }
        } catch (MessagingException e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
    }

    static void callHook(HttpClient http, Notifications n, String title, String text)
            throws IOException, InterruptedException {
        if (n.webhookUrl().isEmpty()) {
            throw new IOException("Set the address of the web hook.");
        }
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(n.webhookUrl())).timeout(NETWORK_TIME);
        switch (n.webhookBody()) {
            case TEXT ->
                request.header("Content-Type", "text/plain; charset=utf-8")
                        .header("Title", title)
                        .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
            case JSON ->
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                JsonMapper.shared().writeValueAsString(Map.of("title", title, "message", text)),
                                StandardCharsets.UTF_8));
        }
        HttpResponse<Void> answer = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (answer.statusCode() / 100 != 2) {
            throw new IOException("The web hook answered HTTP " + answer.statusCode() + ".");
        }
    }
}
