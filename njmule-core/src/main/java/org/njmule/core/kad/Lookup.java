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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;
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
        NOTES(2, Duration.ofSeconds(45), 50, true),
        STORE_SOURCE(4, Duration.ofSeconds(140), 10, true),
        STORE_KEYWORD(4, Duration.ofSeconds(140), 10, true),
        STORE_NOTES(4, Duration.ofSeconds(100), 10, true),
        // Finds helpers for the UDP firewall test: its answers are never asked or added, see KadNode.
        UDP_HELPERS(11, Duration.ofSeconds(45), 0, false),
        // The value counted is each buddy request sent.
        FIND_BUDDY(4, Duration.ofSeconds(100), 10, true);

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

        // Publishes run in the background: they wait the full time for silent nodes.
        boolean stores() {
            return this == STORE_SOURCE || this == STORE_KEYWORD || this == STORE_NOTES;
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
    private static final long STALL_NANOS = ResponseTimes.MAX_WAIT.toNanos();
    // Once no node is left to ask, or it has all it wants, a search or publish waits this long for its last answers.
    private static final long LATE_ANSWER_NANOS = 15_000_000_000L;
    // A search or publish sends nothing new in the last seconds of its life, which its last answers need.
    private static final long LAST_SENDING_NANOS = 20_000_000_000L;
    private static final int RE_ASK_COUNT = 11;
    // Contacts a search asks each node for; publishes ask for more.
    private static final int FIND_VALUE = 2;
    private static final int PER_SUBNET = 2;
    // Nodes that must place a contact at the same address before it gets the request ahead of the others.
    private static final int VOUCHERS = 2;

    private final UInt128 target;
    private final Kind kind;
    private final Requests requests;
    private final IntPredicate lan;
    private final ResponseTimes times;
    private final long started;
    // Both by distance to the target.
    private final TreeMap<UInt128, KadMessages.Contact> possible = new TreeMap<>();
    private final TreeMap<UInt128, KadMessages.Contact> tried = new TreeMap<>();
    private final Set<UInt128> responded = new HashSet<>();
    // When each node was asked, by distance, until it answers.
    private final Map<UInt128, Long> askedAt = new HashMap<>();
    private final Set<UInt128> requested = new HashSet<>();
    // Addresses sent the search or publish: only they may answer it.
    private final Set<Integer> requestedIps = new HashSet<>();
    // By distance: the addresses of the nodes that told of each contact, and the contacts of our own table.
    private final Map<UInt128, Set<Integer>> referrers = new HashMap<>();
    private final Set<UInt128> fromTable = new HashSet<>();
    // Contacts two nodes placed at different addresses: one of them lies, and neither is believed.
    private final Set<UInt128> disputed = new HashSet<>();
    private long lastAnswer;
    private int answers;
    private long loadSum;
    private int loads;
    private KadMessages.Contact reAsked;
    private boolean stopped;
    private boolean stopping;
    private long stopAt;

    Lookup(UInt128 target, Kind kind, Requests requests, IntPredicate lan, long now) {
        this(target, kind, requests, lan, new ResponseTimes(), now);
    }

    /** A lookup that waits for answers as long as {@code times} says nodes take. */
    Lookup(UInt128 target, Kind kind, Requests requests, IntPredicate lan, ResponseTimes times, long now) {
        this.target = Objects.requireNonNull(target, "target");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.lan = Objects.requireNonNull(lan, "lan");
        this.times = Objects.requireNonNull(times, "times");
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

    /** Ends the lookup at once. */
    void stop() {
        this.stopped = true;
    }

    /** Whether the node at {@code ip} was sent the search or publish. */
    boolean requestedFrom(int ip) {
        return this.requestedIps.contains(ip);
    }

    /** Values (search results or publish answers) received so far. */
    int answers() {
        return this.answers;
    }

    /** A node that stores what this lookup publishes reported its load, 0 to 100. */
    void loadReported(int load) {
        this.loadSum += load;
        this.loads++;
    }

    /** The mean of the loads reported, 0 when none was. */
    int load() {
        return this.loads == 0 ? 0 : (int) (this.loadSum / this.loads);
    }

    /** Starts with the contacts of the routing table closest to the target. */
    void start(RoutingTable table) {
        for (KadContact c : table.closest(this.target, KadContact.NEW, START_CONTACTS)) {
            UInt128 distance = c.id().xor(this.target);
            this.possible.put(distance, c.message());
            this.fromTable.add(distance);
        }
        int first = this.kind == Kind.NODE ? 1 : ALPHA;
        for (KadMessages.Contact c : List.copyOf(this.possible.values())) {
            if (this.tried.size() >= first) {
                break;
            }
            ask(c, this.kind.count(), this.started);
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

    /** The id of the node we asked at {@code ip:port}, as the contact that told us of it gave it. */
    Optional<UInt128> askedId(int ip, int port) {
        for (KadMessages.Contact c : this.tried.values()) {
            if (c.ip() == ip && c.udpPort() == port) {
                return Optional.of(c.id());
            }
        }
        return Optional.empty();
    }

    /** How many contacts the node we asked at {@code ip:port} may send. */
    int expectedContacts(int ip, int port) {
        KadMessages.Contact again = this.reAsked;
        return again != null && again.ip() == ip && again.udpPort() == port ? RE_ASK_COUNT : this.kind.count();
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
        if (contacts.size() > expectedContacts(ip, port)) {
            return;
        }
        this.lastAnswer = now;
        this.responded.add(responder.id());
        Long asked = this.askedAt.remove(responder.id().xor(this.target));
        if (asked != null) {
            this.times.answered(ip, now - asked);
        }
        if (this.kind == Kind.NODE) {
            this.stopped = true;
            return;
        }
        if (this.stopping) {
            return;
        }
        UInt128 responderDistance = responder.id().xor(this.target);
        // The responder may not send itself, and its own /24 counts against the limit.
        Set<Integer> ips = new HashSet<>();
        ips.add(responder.ip());
        Map<Integer, Integer> subnets = new HashMap<>();
        subnets.put(responder.ip() & 0xFFFF_FF00, 1);
        for (KadMessages.Contact c : contacts) {
            UInt128 distance = c.id().xor(this.target);
            KadMessages.Contact known =
                    this.tried.containsKey(distance) ? this.tried.get(distance) : this.possible.get(distance);
            if (known != null || this.disputed.contains(distance) || distance.equals(responderDistance)) {
                if (known != null && !distance.equals(responderDistance)) {
                    referred(distance, known, c, responder.ip());
                }
                continue;
            }
            if (!ips.add(c.ip())
                    || (!this.lan.test(c.ip()) && subnets.merge(c.ip() & 0xFFFF_FF00, 1, Integer::sum) > PER_SUBNET)) {
                continue;
            }
            this.possible.put(distance, c);
            this.referrers.computeIfAbsent(distance, d -> new HashSet<>()).add(responder.ip());
            if (distance.compareTo(responderDistance) < 0 && isAmongBest(distance)) {
                ask(c, this.kind.count(), now);
            }
        }
    }

    // Another node told of a contact we know: at the same address it vouches for it; elsewhere, unless our own table
    // has it, the contact is believed no more.
    private void referred(UInt128 distance, KadMessages.Contact known, KadMessages.Contact told, int referrer) {
        if (known.ip() == told.ip() && known.udpPort() == told.udpPort()) {
            this.referrers.computeIfAbsent(distance, d -> new HashSet<>()).add(referrer);
        } else if (!this.fromTable.contains(distance)) {
            this.disputed.add(distance);
            this.possible.remove(distance);
        }
    }

    /** A value (result or publish answer) for this lookup arrived; with enough of them it stops sending. */
    void valueReceived(int count, long now) {
        this.answers += count;
        if (this.kind.maxAnswers > 0 && this.answers >= this.kind.maxAnswers) {
            prepareToStop(now);
        }
    }

    private void prepareToStop(long now) {
        if (!this.stopping) {
            this.stopping = true;
            this.stopAt = now + LATE_ANSWER_NANOS;
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
        if (this.kind.value
                && !this.stopping
                && now - this.started >= this.kind.lifetime.toNanos() - LAST_SENDING_NANOS) {
            requestWaiting();
            prepareToStop(now);
        }
        if (this.stopping) {
            this.stopped = now - this.stopAt >= 0;
            return;
        }
        if (now - this.lastAnswer < (this.kind.stores() ? STALL_NANOS : this.times.waitNanos())) {
            return;
        }
        if (nothingLeftToAsk()) {
            if (this.kind.value) {
                requestWaiting();
                prepareToStop(now);
            } else {
                this.stopped = true;
            }
            return;
        }
        if (this.kind.count() == FIND_VALUE && this.reAsked == null && this.tried.size() >= 3 * FIND_VALUE) {
            reAskIfClosestSilent();
        }
        for (Map.Entry<UInt128, KadMessages.Contact> e : List.copyOf(this.possible.entrySet())) {
            KadMessages.Contact c = e.getValue();
            if (!this.tried.containsKey(e.getKey())) {
                ask(c, this.kind.count(), now);
                return;
            }
            if (this.responded.contains(c.id())) {
                if (isWaiting(e.getKey(), c)) {
                    continue;
                }
                if (this.kind.value && e.getKey().top32() <= TOLERANCE && !this.requested.contains(c.id())) {
                    request(c);
                    if (this.stopped) {
                        return;
                    }
                }
                this.possible.remove(e.getKey());
            }
        }
        if (!this.kind.value) {
            this.stopped = nothingLeftToAsk();
        }
    }

    // Asked nodes that stay silent are kept for a late answer, and nodes only one other knew of for the end; neither
    // brings the lookup further.
    private boolean nothingLeftToAsk() {
        for (Map.Entry<UInt128, KadMessages.Contact> e : this.possible.entrySet()) {
            if (!this.tried.containsKey(e.getKey())
                    || (this.responded.contains(e.getValue().id()) && !isWaiting(e.getKey(), e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    // A node close enough for the request that only one other knew of waits for a second one, or the end.
    private boolean isWaiting(UInt128 distance, KadMessages.Contact c) {
        return this.kind.value
                && distance.top32() <= TOLERANCE
                && !this.requested.contains(c.id())
                && !this.fromTable.contains(distance)
                && !this.lan.test(c.ip())
                && this.referrers.getOrDefault(distance, Set.of()).size() < VOUCHERS;
    }

    // Before the lookup stops sending, the nodes still waiting get the request, closest first.
    private void requestWaiting() {
        for (Map.Entry<UInt128, KadMessages.Contact> e : List.copyOf(this.possible.entrySet())) {
            KadMessages.Contact c = e.getValue();
            if (this.responded.contains(c.id()) && isWaiting(e.getKey(), c)) {
                request(c);
                if (this.stopped) {
                    return;
                }
            }
        }
    }

    private void request(KadMessages.Contact c) {
        this.requested.add(c.id());
        this.requestedIps.add(c.ip());
        this.requests.request(this, c);
    }

    // A search whose two closest asked nodes are silent asks the closest one that answered for more contacts.
    private void reAskIfClosestSilent() {
        int seen = 0;
        for (KadMessages.Contact c : this.tried.values()) {
            if (this.responded.contains(c.id())) {
                if (seen < FIND_VALUE) {
                    return;
                }
                this.reAsked = c;
                this.requests.ask(this, c, RE_ASK_COUNT);
                return;
            }
            seen++;
        }
    }

    private boolean isAmongBest(UInt128 distance) {
        return this.possible.headMap(distance).size() < ALPHA;
    }

    private void ask(KadMessages.Contact contact, int count, long now) {
        UInt128 distance = contact.id().xor(this.target);
        this.tried.put(distance, contact);
        this.askedAt.put(distance, now);
        this.requests.ask(this, contact, count);
    }
}
