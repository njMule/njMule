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
package org.njmule.core.social;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.engine.EngineLoop;
import org.njmule.core.net.TcpConnection;
import org.njmule.core.net.TcpConnector;
import org.njmule.core.protocol.ed2k.ChatMessages;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.FrameLimits;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.packet.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chats with other clients, one conversation per client address (its IP and listening port).
 * Messages go over the connection the client opened to us when there is one, otherwise over a
 * connection of our own, opened with a hello. Engine thread only, except where noted.
 */
public final class Messaging implements AutoCloseable {

    /** Who we chat with. */
    public record Correspondent(InetSocketAddress address, Hash16 userHash, Optional<String> nick) {
        public Correspondent {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(userHash, "userHash");
            Objects.requireNonNull(nick, "nick");
        }
    }

    /** What the conversations bring; called on the engine thread. */
    public interface Listener {
        void received(Correspondent from, String text);

        /** {@code from} wants a CAPTCHA solved before it shows our messages. */
        void captcha(Correspondent from, byte[] image);

        void captchaResult(Correspondent from, boolean solved);

        /** Messages to {@code to} could not be sent. */
        void failed(InetSocketAddress to, String reason);
    }

    private static final Logger logger = LoggerFactory.getLogger(Messaging.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IDLE = Duration.ofMinutes(10);

    private static final class Conversation {
        private Hello peer;
        private Consumer<Packet> sender;
        private BooleanSupplier open = () -> false;
        private TcpConnection own;
        private boolean connecting;
        // A CAPTCHA is asked for in answer to our messages: none is accepted before we write.
        private boolean wrote;
        private final List<String> pending = new ArrayList<>();

        boolean isOpen() {
            return this.peer != null && this.open.getAsBoolean();
        }
    }

    private final EngineLoop loop;
    private final Supplier<Hello> ourHello;
    private final SecureRandom random;
    private final Listener listener;
    private final Map<InetSocketAddress, Conversation> conversations = new HashMap<>();

    public Messaging(
            EngineLoop loop, Supplier<Hello> ourHello, SecureRandom random, Listener listener) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.ourHello = Objects.requireNonNull(ourHello, "ourHello");
        this.random = Objects.requireNonNull(random, "random");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Sends {@code text} to the client listening at {@code to}, connecting first if needed. */
    public void send(InetSocketAddress to, String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            return;
        }
        Conversation c = this.conversations.computeIfAbsent(to, a -> new Conversation());
        c.wrote = true;
        if (c.isOpen()) {
            c.sender.accept(ChatMessages.message(text, unicode(c.peer)));
            return;
        }
        c.pending.add(text);
        if (!c.connecting) {
            c.connecting = true;
            this.connect(to, c);
        }
    }

    /**
     * A chat packet that came on a connection opened for something else (an upload): the
     * conversation answers on it. Returns whether the packet was a chat packet.
     */
    public boolean incoming(
            InetSocketAddress connection,
            Hello peer,
            Consumer<Packet> reply,
            BooleanSupplier open,
            Packet packet) {
        if (!isChat(packet)) {
            return false;
        }
        InetSocketAddress address = new InetSocketAddress(connection.getAddress(), peer.tcpPort());
        Conversation c = this.conversations.computeIfAbsent(address, a -> new Conversation());
        if (!c.isOpen() || c.own == null) {
            c.peer = peer;
            c.sender = reply;
            c.open = open;
            this.flush(address, c);
        }
        this.handle(address, peer, packet);
        return true;
    }

    /** The clients we chat with now. */
    public int conversations() {
        return this.conversations.size();
    }

    @Override
    public void close() {
        for (Conversation c : this.conversations.values()) {
            if (c.own != null) {
                c.own.close();
            }
        }
        this.conversations.clear();
    }

    private void connect(InetSocketAddress to, Conversation c) {
        Thread.ofVirtual()
                .name("njmule-chat")
                .start(
                        () -> {
                            try {
                                TcpConnection connection =
                                        TcpConnector.connectToClient(
                                                to, Optional.empty(), CONNECT_TIMEOUT, this.random);
                                this.loop.post(() -> this.connected(to, c, connection));
                            } catch (IOException e) {
                                this.loop.post(
                                        () ->
                                                this.unreachable(
                                                        to, c, String.valueOf(e.getMessage())));
                            }
                        });
    }

    private void connected(InetSocketAddress to, Conversation c, TcpConnection connection) {
        if (this.conversations.get(to) != c) {
            connection.close();
            return;
        }
        c.own = connection;
        try {
            connection.setReadTimeout(IDLE);
        } catch (SocketException e) {
            connection.close();
            this.unreachable(to, c, String.valueOf(e.getMessage()));
            return;
        }
        connection.start(new Handler(to, c), Messaging::unpack);
        connection.send(this.ourHello.get().encode(false));
    }

    private void unreachable(InetSocketAddress to, Conversation c, String reason) {
        c.connecting = false;
        if (!c.pending.isEmpty()) {
            c.pending.clear();
            this.listener.failed(to, reason);
        }
        if (c.peer == null) {
            this.conversations.remove(to, c);
        }
    }

    private void flush(InetSocketAddress to, Conversation c) {
        for (String text : c.pending) {
            c.sender.accept(ChatMessages.message(text, unicode(c.peer)));
        }
        c.pending.clear();
        c.connecting = false;
        logger.debug("Chatting with {}", to);
    }

    private void handle(InetSocketAddress address, Hello peer, Packet packet) {
        Correspondent from = new Correspondent(address, peer.userHash(), peer.nick());
        try {
            if (packet.protocol() == Protocol.EDONKEY) {
                this.listener.received(from, ChatMessages.text(packet));
            } else if (packet.opcode() == Opcodes.Extended.CHATCAPTCHAREQ.code()) {
                Conversation c = this.conversations.get(address);
                if (c == null || !c.wrote) {
                    logger.debug("Ignoring a CAPTCHA {} asked for unasked", address);
                    return;
                }
                this.listener.captcha(from, ChatMessages.captcha(packet));
            } else {
                this.listener.captchaResult(from, ChatMessages.solved(packet));
            }
        } catch (DecodeException e) {
            logger.debug("Bad chat packet from {}: {}", address, e.getMessage());
        }
    }

    private static boolean isChat(Packet packet) {
        return (packet.protocol() == Protocol.EDONKEY
                        && packet.opcode() == Opcodes.Client.MESSAGE.code())
                || (packet.protocol() == Protocol.EMULE
                        && (packet.opcode() == Opcodes.Extended.CHATCAPTCHAREQ.code()
                                || packet.opcode() == Opcodes.Extended.CHATCAPTCHARES.code()));
    }

    private static boolean unicode(Hello peer) {
        return (peer.miscOptions1() & Hello.MISC1_UNICODE) != 0;
    }

    private static Packet unpack(Packet frame) throws DecodeException {
        return Packing.unpack(frame, Packing.MAX_CLIENT_UNPACKED);
    }

    /** Our own connection to a client: its hello answer first, then the chat. */
    private final class Handler implements TcpConnection.Handler {
        private final InetSocketAddress to;
        private final Conversation conversation;

        Handler(InetSocketAddress to, Conversation conversation) {
            this.to = to;
            this.conversation = conversation;
        }

        @Override
        public void onPacket(TcpConnection connection, Packet packet) {
            if (packet.protocol() == Protocol.EDONKEY
                    && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                connection.setFrameLimit(FrameLimits.CLIENT);
            }
            Messaging.this.loop.post(() -> this.received(connection, packet));
        }

        private void received(TcpConnection connection, Packet packet) {
            Conversation c = this.conversation;
            try {
                if (packet.protocol() == Protocol.EDONKEY
                        && packet.opcode() == Opcodes.Client.HELLOANSWER.code()) {
                    c.peer = Hello.decode(packet);
                    c.sender = connection::send;
                    c.open = connection::isOpen;
                    Messaging.this.flush(this.to, c);
                } else if (c.peer != null && isChat(packet)) {
                    Messaging.this.handle(this.to, c.peer, packet);
                }
            } catch (DecodeException e) {
                logger.debug("Bad hello from {}: {}", this.to, e.getMessage());
                connection.close();
            }
        }

        @Override
        public void onClosed(TcpConnection connection, Optional<IOException> cause) {
            Messaging.this.loop.post(
                    () -> {
                        Conversation c = this.conversation;
                        if (c.own == connection) {
                            c.own = null;
                            c.peer = null;
                            Messaging.this.unreachable(this.to, c, "the connection closed");
                        }
                    });
        }
    }
}
