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
package org.njmule.core.config;

import com.google.common.base.Splitter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Where njMule tells of what happens besides its own windows: an e-mail through the user's SMTP server, never in the
 * clear, and an HTTPS request to a web hook such as an ntfy topic or a Gotify server; each on the events chosen.
 *
 * @param smtpPort 587 for STARTTLS, 465 for TLS from the start
 * @param mailTo the addresses the e-mails go to, apart by commas
 * @param webhookUrl an https address; empty for none
 * @param webhookBody what the web hook receives
 */
public record Notifications(
        boolean email,
        String smtpHost,
        int smtpPort,
        SmtpSecurity security,
        String smtpUser,
        String smtpPassword,
        String mailFrom,
        String mailTo,
        boolean webhook,
        String webhookUrl,
        WebhookFormat webhookBody,
        Set<EventCommands.Event> events) {

    /** Characters of a host, a user, a password or the addresses at most. */
    public static final int MAX_TEXT = 255;

    /** Characters of the web hook's address at most. */
    public static final int MAX_URL = 2048;

    public static final int DEFAULT_SMTP_PORT = 587;

    private static final Splitter COMMAS = Splitter.on(',').trimResults().omitEmptyStrings();

    /** How the connection to the SMTP server is encrypted. */
    public enum SmtpSecurity {
        /** Plain at first, then TLS before anything is sent; the connection fails without it. */
        STARTTLS,
        /** TLS from the start. */
        TLS
    }

    /** What the web hook receives. */
    public enum WebhookFormat {
        /** The text, with the title in a Title header, as ntfy reads it. */
        TEXT,
        /** JSON with "title" and "message", as Gotify reads it. */
        JSON
    }

    /** Nothing is sent; once on, the downloads completed and failed and a disk short of space are told. */
    public static final Notifications NONE = new Notifications(
            false,
            "",
            DEFAULT_SMTP_PORT,
            SmtpSecurity.STARTTLS,
            "",
            "",
            "",
            "",
            false,
            "",
            WebhookFormat.TEXT,
            EnumSet.of(
                    EventCommands.Event.DOWNLOAD_COMPLETED,
                    EventCommands.Event.DOWNLOAD_FAILED,
                    EventCommands.Event.DISK_SPACE_SHORT));

    public Notifications {
        smtpHost = text("smtpHost", smtpHost);
        smtpUser = text("smtpUser", smtpUser);
        Objects.requireNonNull(smtpPassword, "smtpPassword");
        if (smtpPassword.length() > MAX_TEXT || smtpPassword.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "the SMTP password must be at most " + MAX_TEXT + " printable characters");
        }
        mailFrom = text("mailFrom", mailFrom);
        mailTo = text("mailTo", mailTo);
        if (smtpPort < 1 || smtpPort > 65_535) {
            throw new IllegalArgumentException("smtpPort must be in [1, 65535]: " + smtpPort);
        }
        Objects.requireNonNull(security, "security");
        webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl").strip();
        requireWebhookUrl(webhookUrl);
        Objects.requireNonNull(webhookBody, "webhookBody");
        EnumSet<EventCommands.Event> chosen = EnumSet.noneOf(EventCommands.Event.class);
        chosen.addAll(Objects.requireNonNull(events, "events"));
        events = Collections.unmodifiableSet(chosen);
    }

    /** Whether {@code event} is told. */
    public boolean tells(EventCommands.Event event) {
        return this.events.contains(event);
    }

    /** The addresses the e-mails go to. */
    public List<String> recipients() {
        return COMMAS.splitToList(this.mailTo);
    }

    /** The events of a comma-separated list of their ids, such as {@code downloadCompleted,chatMessage}. */
    public static Set<EventCommands.Event> events(String ids) {
        EnumSet<EventCommands.Event> events = EnumSet.noneOf(EventCommands.Event.class);
        for (String wanted : COMMAS.split(ids)) {
            events.add(Arrays.stream(EventCommands.Event.values())
                    .filter(e -> e.id().equalsIgnoreCase(wanted))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown event: " + wanted)));
        }
        return events;
    }

    private static String text(String name, String value) {
        String v = Objects.requireNonNull(value, name).strip();
        if (v.length() > MAX_TEXT || v.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be at most " + MAX_TEXT + " printable characters");
        }
        return v;
    }

    /**
     * {@code url} without the spaces around it, when it is empty or an https address.
     *
     * @throws IllegalArgumentException when it is neither
     */
    // Only HTTPS: the texts name files and the other users who write.
    public static String requireWebhookUrl(String url) {
        if (url.isBlank()) {
            return "";
        }
        if (url.length() > MAX_URL) {
            throw new IllegalArgumentException("the web hook address must be at most " + MAX_URL + " characters");
        }
        try {
            URI uri = new URI(url.strip());
            if (!"https".equals(String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT)) || uri.getHost() == null) {
                throw new IllegalArgumentException("the web hook address must be an https address: " + url);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("not a web hook address: " + url, e);
        }
        return url.strip();
    }
}
