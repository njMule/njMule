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
package org.njmule.core.remote;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.njmule.core.api.EngineEvent;
import org.njmule.core.api.Hash16;

/**
 * The chats of this run, kept for the front ends that come and go: each client's lines, the nick and user hash it gave,
 * and the CAPTCHA it asks to solve before it reads our messages.
 */
public final class Conversations {

    static final int MAX_LINES = 1000;

    // Clients that write and are never answered are forgotten first, so that spam cannot fill the memory.
    static final int MAX_STRANGERS = 50;

    /** Who a line is from; a notice tells what happened to our messages. */
    public enum Author {
        US,
        THEM,
        NOTICE
    }

    /** What a notice tells. */
    public enum Notice {
        CAPTCHA_PASSED,
        CAPTCHA_WRONG,
        NOT_DELIVERED
    }

    /** A line of a chat; a notice's text is the reason a message was not delivered, or empty. */
    public record Line(long sequence, Instant time, Author author, String text, Optional<Notice> notice) {}

    /** A chat as it stands. */
    public record Chat(
            String address,
            Optional<Hash16> userHash,
            Optional<String> nick,
            List<Line> lines,
            Optional<Captcha> captcha) {}

    /** A CAPTCHA picture to solve, a BMP. */
    public static final class Captcha {
        private final byte[] image;

        Captcha(byte[] image) {
            this.image = image.clone();
        }

        public byte[] image() {
            return this.image.clone();
        }
    }

    private static final class Open {
        final Deque<Line> lines = new ArrayDeque<>();
        Optional<Hash16> userHash = Optional.empty();
        Optional<String> nick = Optional.empty();
        Optional<Captcha> captcha = Optional.empty();
        // We wrote or answered: kept however many strangers write.
        boolean ours;
    }

    private final Clock clock;
    // By address, the chat with the newest line last.
    private final Map<String, Open> chats = new LinkedHashMap<>();
    private long sequence;

    Conversations() {
        this(Clock.systemUTC());
    }

    Conversations(Clock clock) {
        this.clock = clock;
    }

    synchronized void received(EngineEvent.MessageReceived m) {
        Open chat = touch(m.address());
        chat.userHash = Optional.of(m.userHash());
        if (m.nick().isPresent()) {
            chat.nick = m.nick();
        }
        add(chat, Author.THEM, m.text(), Optional.empty());
        forgetStrangers();
    }

    synchronized void captcha(EngineEvent.CaptchaRequested c) {
        Open chat = touch(c.address());
        chat.userHash = Optional.of(c.userHash());
        chat.captcha = Optional.of(new Captcha(c.image()));
    }

    synchronized void answered(EngineEvent.CaptchaAnswered a) {
        add(
                touch(a.address()),
                Author.NOTICE,
                "",
                Optional.of(a.solved() ? Notice.CAPTCHA_PASSED : Notice.CAPTCHA_WRONG));
    }

    synchronized void failed(EngineEvent.MessageFailed f) {
        add(touch(f.address()), Author.NOTICE, f.reason(), Optional.of(Notice.NOT_DELIVERED));
    }

    /** Our message to {@code address}, which answers a CAPTCHA it asked for. */
    synchronized void sent(String address, String text) {
        Open chat = touch(address);
        chat.ours = true;
        chat.captcha = Optional.empty();
        add(chat, Author.US, text, Optional.empty());
    }

    /** Forgets the chat with {@code address}; false when there is none. */
    public synchronized boolean close(String address) {
        return this.chats.remove(address) != null;
    }

    /** The chats, the one with the newest line first. */
    public synchronized List<Chat> all() {
        List<Chat> all = new ArrayList<>();
        this.chats.forEach(
                (address, c) -> all.addFirst(new Chat(address, c.userHash, c.nick, List.copyOf(c.lines), c.captcha)));
        return all;
    }

    // The chat with address, moved to the end as the newest.
    private Open touch(String address) {
        Open chat = this.chats.remove(address);
        if (chat == null) {
            chat = new Open();
        }
        this.chats.put(address, chat);
        return chat;
    }

    private void add(Open chat, Author author, String text, Optional<Notice> notice) {
        chat.lines.addLast(new Line(++this.sequence, this.clock.instant(), author, text, notice));
        if (chat.lines.size() > MAX_LINES) {
            chat.lines.removeFirst();
        }
    }

    private void forgetStrangers() {
        long strangers = this.chats.values().stream().filter(c -> !c.ours).count();
        var oldest = this.chats.values().iterator();
        while (strangers > MAX_STRANGERS && oldest.hasNext()) {
            if (!oldest.next().ours) {
                oldest.remove();
                strangers--;
            }
        }
    }
}
