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
package org.njmule.gui.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.njmule.core.api.Hash16;

/** The conversations of this session, one per client address. FX thread only. */
public final class Chats {

    /** Who wrote a line: us, the other client, or njMule about the conversation. */
    public enum Author {
        US,
        THEM,
        NOTICE
    }

    /**
     * One line of a conversation; a notice's text is the key of its translation, with {@code
     * detail} to fill in.
     */
    public record Line(Instant time, Author author, String text, String detail) {}

    /** The conversation with the client at {@code address} (IP and listening port). */
    public static final class Chat {
        private final String address;
        private final StringProperty nick = new SimpleStringProperty("");
        private final ObjectProperty<Hash16> userHash = new SimpleObjectProperty<>();
        private final ObservableList<Line> lines = FXCollections.observableArrayList();
        private final ObjectProperty<byte[]> captcha = new SimpleObjectProperty<>();

        Chat(String address) {
            this.address = address;
        }

        public String address() {
            return this.address;
        }

        /** The other client's name, empty until it writes. */
        public ReadOnlyStringProperty nickProperty() {
            return this.nick;
        }

        public Optional<Hash16> userHash() {
            return Optional.ofNullable(this.userHash.get());
        }

        public ObservableList<Line> lines() {
            return this.lines;
        }

        /** The CAPTCHA picture to solve now (a BMP), or null. */
        public ReadOnlyObjectProperty<byte[]> captchaProperty() {
            return this.captcha;
        }

        void add(Author author, String text, String detail) {
            this.lines.add(new Line(Instant.now(), author, text, detail));
        }
    }

    /** Lines a conversation keeps at most. */
    static final int MAX_LINES = 1_000;

    private final ObservableList<Chat> chats = FXCollections.observableArrayList();
    private Consumer<Chat> arrived = chat -> {};

    public ObservableList<Chat> all() {
        return this.chats;
    }

    /** {@code sink} learns of every message that arrives. */
    public void onMessage(Consumer<Chat> sink) {
        this.arrived = Objects.requireNonNull(sink, "sink");
    }

    /** The conversation with {@code address}, started if new. */
    public Chat with(String address) {
        for (Chat chat : this.chats) {
            if (chat.address.equals(address)) {
                return chat;
            }
        }
        Chat chat = new Chat(address);
        this.chats.add(chat);
        return chat;
    }

    public void close(Chat chat) {
        this.chats.remove(chat);
    }

    void received(String address, Hash16 userHash, Optional<String> nick, String text) {
        Chat chat = this.with(address);
        chat.userHash.set(userHash);
        nick.filter(n -> !n.isBlank()).ifPresent(chat.nick::set);
        chat.add(Author.THEM, text, "");
        trim(chat);
        this.arrived.accept(chat);
    }

    void sent(String address, String text) {
        Chat chat = this.with(address);
        chat.add(Author.US, text, "");
        chat.captcha.set(null);
        trim(chat);
    }

    void notice(String address, String key, String detail) {
        this.with(address).add(Author.NOTICE, key, detail);
    }

    void captcha(String address, Hash16 userHash, byte[] image) {
        Chat chat = this.with(address);
        chat.userHash.set(userHash);
        chat.captcha.set(image);
    }

    private static void trim(Chat chat) {
        if (chat.lines.size() > MAX_LINES) {
            chat.lines.remove(0, chat.lines.size() - MAX_LINES);
        }
    }
}
