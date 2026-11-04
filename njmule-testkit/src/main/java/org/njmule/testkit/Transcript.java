/*
 * njMule - New Jack Mule
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
package org.njmule.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.njmule.core.protocol.packet.Packet;

/** The packets a fake server or peer received, in the order they came: what an engine sent it. */
public final class Transcript {

    /** Who received the packets. */
    public enum Receiver {
        SERVER,
        PEER
    }

    /** A packet received on {@code channel} ({@code tcp}, {@code udp}...), on the {@code connection}th connection. */
    public record Received(String channel, int connection, Packet packet) {}

    // The transcripts of the fakes started since the last take, in the order they started.
    private static final List<Transcript> STARTED = new ArrayList<>();

    private final Receiver receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();

    private Transcript(Receiver receiver) {
        this.receiver = receiver;
    }

    static Transcript started(Receiver receiver) {
        Transcript transcript = new Transcript(receiver);
        synchronized (STARTED) {
            STARTED.add(transcript);
        }
        return transcript;
    }

    /** The transcripts of the fakes started since the last call, in the order they started. */
    public static List<Transcript> takeStarted() {
        synchronized (STARTED) {
            List<Transcript> taken = List.copyOf(STARTED);
            STARTED.clear();
            return taken;
        }
    }

    int newConnection() {
        return this.connections.incrementAndGet();
    }

    void add(String channel, int connection, Packet packet) {
        this.received.add(new Received(channel, connection, packet));
    }

    public Receiver receiver() {
        return this.receiver;
    }

    public List<Received> received() {
        return List.copyOf(this.received);
    }
}
