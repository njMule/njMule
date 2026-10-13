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
package org.njmule.core.kad;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.njmule.core.protocol.kad.KadMessages;

/**
 * One iterative lookup towards a target: the closest contacts we know are asked for closer ones, three at a time, and
 * the nodes that answered and are close enough get the actual request (a search or a publish). Engine thread only.
 */
final class Lookup {

    /** The purpose of a lookup, with the contacts it asks for and how long it lives. */
    enum Kind {
        NODE(11, Duration.ofSeconds(45), 0, false),
        NODE_COMPLETE(11, Duration.ofSeconds(45), 0, false),
        SOURCES(2, Duration.ofSeconds(45), 300, true),
        KEYWORD(2, Duration.ofSeconds(45), 300, true),
        STORE_SOURCE(4, Duration.ofSeconds(140), 10, true),
        STORE_KEYWORD(4, Duration.ofSeconds(140), 10, true);

        private final int count;
        private final Duration lifetime;
        private final int maxAnswers;
        private final boolean value;

        Kind(int count, Duration lifetime, int maxAnswers, boolean value) {
            this.count = count;
            this.lifetime = lifetime;
            this.maxAnswers = maxAnswers;
            this.value = value;
        }

        int count() {
            return this.count;
        }

        Duration lifetime() {
            return this.lifetime;
        }
    }

    /** What a lookup sends. */
    interface Requests {
        /** Asks {@code contact} for {@code count} contacts closer to the target. */
        void ask(Lookup lookup, KadMessages.Contact contact, int count);

        /** Sends the search or publish to a node close to the target. */
        void request(Lookup lookup, KadMessages.Contact contact);
    }

    /** Ids within this distance of the target, in their top 32 bits, get the request. */
    static final long TOLERANCE = 0x0100_0000L;

    private static final int ALPHA = 3;
    private static final int START_CONTACTS = 50;
    private static final long STALL_NANOS = 3_000_000_000L;
    private static final int RE_ASK_COUNT = 11;

    private final UInt128 target;
    private final Kind kind;
    private final Requests requests;
    private final long started;
    private final TreeMap<UInt128, KadMessages.Contact> possible = new TreeMap<>();
    private final Map<UInt128, KadMessages.Contact> tried = new HashMap<>();
    private final Set<UInt128> responded = new HashSet<>();
    private final Set<UInt128> requested = new HashSet<>();
    private long lastAnswer;
    private int answers;
    private boolean reAsked;
    private boolean stopped;

    Lookup(UInt128 target, Kind kind, Requests requests, long now) {
        this.target = Objects.requireNonNull(target, "target");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.started = now;
        this.lastAnswer = now;
    }

    UInt128 target() {
        return this.target;
    }

    Kind kind() {
        return this.kind;
    }

    boolean isStopped() {
        return this.stopped;
    }

    /** Values (search results or publish answers) received so far. */
    int answers() {
        return this.answers;
    }

    /** Starts with the contacts of the routing table closest to the target. */
    void start(RoutingTable table) {
        for (KadContact c : table.closest(this.target, KadContact.NEW, START_CONTACTS)) {
            this.possible.put(c.id().xor(this.target), c.message());
        }
        int first = this.kind == Kind.NODE ? 1 : ALPHA;
        for (KadMessages.Contact c : List.copyOf(this.possible.values())) {
            if (this.tried.size() >= first) {
                break;
            }
            this.ask(c, this.kind.count());
        }
        if (this.tried.isEmpty()) {
            this.stopped = true;
        }
    }

    /** Whether {@code from} is a node we asked and whose answer we wait for. */
    boolean asked(int ip, int port) {
        for (KadMessages.Contact c : this.tried.values()) {
            if (c.ip() == ip && c.udpPort() == port) {
                return true;
            }
        }
        return false;
    }

    /** Contacts sent by a node we asked. */
    void answered(int ip, int port, List<KadMessages.Contact> contacts, long now) {
        KadMessages.Contact responder = null;
        for (KadMessages.Contact c : this.tried.values()) {
            if (c.ip() == ip && c.udpPort() == port) {
                responder = c;
                break;
            }
        }
        if (responder == null || this.stopped) {
            return;
        }
        int allowed = this.reAsked ? RE_ASK_COUNT : this.kind.count();
        if (contacts.size() > allowed) {
            return;
        }
        this.lastAnswer = now;
        this.responded.add(responder.id());
        if (this.kind == Kind.NODE) {
            this.stopped = true;
            return;
        }
        UInt128 responderDistance = responder.id().xor(this.target);
        Set<Integer> ips = new HashSet<>();
        Map<Integer, Integer> subnets = new HashMap<>();
        for (KadMessages.Contact c : contacts) {
            UInt128 distance = c.id().xor(this.target);
            if (this.tried.containsKey(c.id())
                    || this.possible.containsKey(distance)
                    || c.ip() == responder.ip()
                    || !ips.add(c.ip())
                    || subnets.merge(c.ip() & 0xFFFF_FF00, 1, Integer::sum) > 2) {
                continue;
            }
            this.possible.put(distance, c);
            if (distance.compareTo(responderDistance) < 0 && this.isAmongBest(distance)) {
                this.ask(c, this.kind.count());
            }
        }
    }

    /** A value (result or publish answer) for this lookup arrived. */
    void valueReceived(int count) {
        this.answers += count;
        if (this.kind.maxAnswers > 0 && this.answers >= this.kind.maxAnswers) {
            this.stopped = true;
        }
    }

    /** Runs every second: moves on when the lookup stalls, and ends it when its time is up. */
    void tick(long now) {
        if (this.stopped) {
            return;
        }
        if (now - this.started > this.kind.lifetime.toNanos()) {
            this.stopped = true;
            return;
        }
        if (now - this.lastAnswer < STALL_NANOS) {
            return;
        }
        if (this.possible.isEmpty()) {
            this.stopped = true;
            return;
        }
        if (this.kind.value && !this.reAsked && this.tried.size() >= 6) {
            this.reAskIfClosestSilent();
        }
        for (Map.Entry<UInt128, KadMessages.Contact> e : List.copyOf(this.possible.entrySet())) {
            KadMessages.Contact c = e.getValue();
            if (!this.tried.containsKey(c.id())) {
                this.ask(c, this.kind.count());
                return;
            }
            if (this.responded.contains(c.id())) {
                if (this.kind.value && e.getKey().top32() <= TOLERANCE && this.requested.add(c.id())) {
                    this.requests.request(this, c);
                }
                this.possible.remove(e.getKey());
            }
        }
        if (!this.kind.value) {
            this.stopped = this.possible.isEmpty();
        }
    }

    // When neither of the two closest asked nodes answered, the closest that did is asked again.
    private void reAskIfClosestSilent() {
        int seen = 0;
        for (KadMessages.Contact c : this.possible.values()) {
            if (!this.tried.containsKey(c.id())) {
                continue;
            }
            if (this.responded.contains(c.id())) {
                if (seen < 2) {
                    return;
                }
                this.reAsked = true;
                this.requests.ask(this, c, RE_ASK_COUNT);
                return;
            }
            seen++;
        }
    }

    private boolean isAmongBest(UInt128 distance) {
        return this.possible.headMap(distance).size() < ALPHA;
    }

    private void ask(KadMessages.Contact contact, int count) {
        this.tried.put(contact.id(), contact);
        this.requests.ask(this, contact, count);
    }
}
