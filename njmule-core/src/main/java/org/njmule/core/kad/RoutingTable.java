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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntPredicate;
import org.njmule.core.persist.format.NodesDat;

/**
 * Our view of the Kad network: a binary tree of zones by distance from our id, whose leaves hold up to {@value #K}
 * contacts. The tree is complete down to {@value #KBASE} levels; deeper, only the zones closest to us split, so we know
 * our neighbourhood best. Old contacts are never pushed out by new ones. Engine thread only.
 */
final class RoutingTable {

    /** Contacts per leaf. */
    static final int K = 10;

    /** Levels down to which every zone may split. */
    static final int KBASE = 4;

    /** Zones with an index below this may split at any level. */
    static final int KK = 5;

    private static final int MAX_LEVEL = 127;
    private static final int PER_SUBNET = 10;
    private static final int PER_SUBNET_IN_BIN = 2;

    /** The outcome of adding a contact. */
    enum Outcome {
        ADDED,
        UPDATED,
        REFUSED
    }

    /** A zone: a leaf with a bin of contacts, or two halves. */
    static final class Zone {
        private final int level;
        private final UInt128 index;
        private final List<KadContact> bin = new ArrayList<>();
        private Zone near;
        private Zone far;
        private long nextBigTimer;

        Zone(int level, UInt128 index) {
            this.level = level;
            this.index = index;
        }

        boolean isLeaf() {
            return this.near == null;
        }

        int level() {
            return this.level;
        }

        UInt128 index() {
            return this.index;
        }

        List<KadContact> contacts() {
            return this.bin;
        }

        long nextBigTimer() {
            return this.nextBigTimer;
        }

        void setNextBigTimer(long when) {
            this.nextBigTimer = when;
        }
    }

    private final UInt128 own;
    private final IntPredicate lan;
    private final Zone root = new Zone(0, UInt128.ZERO);
    private final Map<UInt128, KadContact> byId = new HashMap<>();
    private final Map<Integer, KadContact> byIp = new HashMap<>();
    private final Map<Integer, Integer> perSubnet = new HashMap<>();

    /** @param lan addresses (host order) exempt from the subnet limits */
    RoutingTable(UInt128 own, IntPredicate lan) {
        this.own = Objects.requireNonNull(own, "own");
        this.lan = Objects.requireNonNull(lan, "lan");
    }

    UInt128 own() {
        return this.own;
    }

    int size() {
        return this.byId.size();
    }

    Optional<KadContact> get(UInt128 id) {
        return Optional.ofNullable(this.byId.get(id));
    }

    Optional<KadContact> byAddress(int ip) {
        return Optional.ofNullable(this.byIp.get(ip));
    }

    List<KadContact> all() {
        return List.copyOf(this.byId.values());
    }

    /**
     * Adds {@code contact}, or updates the known contact with its id when {@code update} is set; {@code senderKey} is
     * the key the contact gave us in this packet, if any.
     */
    Outcome add(KadContact contact, boolean update, NodesDat.UdpKey senderKey, int ourIp, long now) {
        if (contact.id().equals(this.own)
                || contact.version() <= 1
                || contact.udpPort() < 1
                || contact.udpPort() > 0xFFFF) {
            return Outcome.REFUSED;
        }
        if (contact.udpPort() == 53 && contact.version() <= 5) {
            return Outcome.REFUSED;
        }
        KadContact known = this.byId.get(contact.id());
        if (known != null) {
            return update ? this.update(known, contact, senderKey, ourIp, now) : Outcome.REFUSED;
        }
        Zone leaf = this.leaf(contact.id().xor(this.own));
        while (true) {
            if (leaf.bin.size() < K) {
                if (!this.fits(contact.ip(), leaf, null)) {
                    return Outcome.REFUSED;
                }
                if (senderKey.key() != 0) {
                    contact.setUdpKey(senderKey);
                }
                leaf.bin.add(contact);
                this.index(contact);
                return Outcome.ADDED;
            }
            if (!this.canSplit(leaf)) {
                return Outcome.REFUSED;
            }
            this.split(leaf);
            leaf = this.leaf(contact.id().xor(this.own));
        }
    }

    private Outcome update(KadContact known, KadContact fresh, NodesDat.UdpKey senderKey, int ourIp, long now) {
        // A key bound to our address proves who the contact is: another key is someone else.
        NodesDat.UdpKey stored = known.udpKey();
        if (stored.key() != 0 && stored.ip() == ourIp && senderKey.key() != stored.key()) {
            return Outcome.REFUSED;
        }
        if (known.version() < 6
                && known.helloReceived()
                && (known.ip() != fresh.ip()
                        || known.udpPort() != fresh.udpPort()
                        || known.tcpPort() != fresh.tcpPort()
                        || known.version() != fresh.version())) {
            return Outcome.REFUSED;
        }
        if (fresh.version() < known.version()) {
            return Outcome.REFUSED;
        }
        if (fresh.ip() != known.ip()) {
            Zone leaf = this.leaf(known.id().xor(this.own));
            if (!this.fits(fresh.ip(), leaf, known)) {
                return Outcome.REFUSED;
            }
            this.unindex(known);
            known.moveTo(fresh.ip(), fresh.udpPort());
            this.index(known);
        } else {
            known.moveTo(fresh.ip(), fresh.udpPort());
        }
        known.update(fresh.tcpPort(), fresh.version());
        if (senderKey.key() != 0) {
            known.setUdpKey(senderKey);
        }
        if (fresh.isVerified()) {
            known.setVerified(true);
        }
        this.alive(known, now);
        return Outcome.UPDATED;
    }

    /** The contact answered: it ages and moves to the end of its bin. */
    void alive(KadContact contact, long now) {
        contact.alive(now);
        Zone leaf = this.leaf(contact.id().xor(this.own));
        if (leaf.bin.remove(contact)) {
            leaf.bin.add(contact);
        }
    }

    void remove(KadContact contact) {
        Zone leaf = this.leaf(contact.id().xor(this.own));
        if (leaf.bin.remove(contact)) {
            this.unindex(contact);
        }
    }

    /** Up to {@code count} verified contacts of type at most {@code maxType}, closest first. */
    List<KadContact> closest(UInt128 target, int maxType, int count) {
        List<KadContact> candidates = new ArrayList<>();
        for (KadContact c : this.byId.values()) {
            if (c.isVerified() && c.type() <= maxType) {
                candidates.add(c);
            }
        }
        candidates.sort(Comparator.comparing(c -> c.id().xor(target)));
        return candidates.size() > count ? candidates.subList(0, count) : candidates;
    }

    /** Contacts to hand to a joining node: those of the zones near the root. */
    List<KadContact> bootstrapContacts(int count, SecureRandom random) {
        List<KadContact> contacts = new ArrayList<>();
        this.collect(this.root, 5, random, contacts);
        return contacts.size() > count ? contacts.subList(0, count) : contacts;
    }

    private void collect(Zone zone, int depth, SecureRandom random, List<KadContact> out) {
        if (zone.isLeaf()) {
            out.addAll(zone.bin);
            return;
        }
        if (depth == 0) {
            this.collect(random.nextBoolean() ? zone.near : zone.far, 0, random, out);
            return;
        }
        this.collect(zone.near, depth - 1, random, out);
        this.collect(zone.far, depth - 1, random, out);
    }

    List<Zone> leaves() {
        List<Zone> leaves = new ArrayList<>();
        this.leaves(this.root, leaves);
        return leaves;
    }

    private void leaves(Zone zone, List<Zone> out) {
        if (zone.isLeaf()) {
            out.add(zone);
        } else {
            this.leaves(zone.near, out);
            this.leaves(zone.far, out);
        }
    }

    /** Whether the zone deserves a random lookup to find more contacts for it. */
    boolean wantsContacts(Zone zone) {
        return isSmall(zone.index) || zone.level < KBASE || K - zone.bin.size() >= 8;
    }

    /** A random id inside {@code zone}. */
    UInt128 randomIdIn(Zone zone, SecureRandom random) {
        UInt128 distance = UInt128.random(random);
        if (zone.level > 0) {
            UInt128 prefix = shiftLeft(zone.index, UInt128.BITS - zone.level);
            UInt128 mask = shiftLeft(ones(), UInt128.BITS - zone.level);
            distance = new UInt128(
                    prefix.high() | (distance.high() & ~mask.high()), prefix.low() | (distance.low() & ~mask.low()));
        }
        return distance.xor(this.own);
    }

    /** Merges sibling leaves that together hold fewer than half a bin. */
    void consolidate() {
        this.consolidate(this.root);
    }

    private void consolidate(Zone zone) {
        if (zone.isLeaf()) {
            return;
        }
        this.consolidate(zone.near);
        this.consolidate(zone.far);
        if (zone.near.isLeaf() && zone.far.isLeaf() && zone.near.bin.size() + zone.far.bin.size() < K / 2) {
            zone.bin.addAll(zone.near.bin);
            zone.bin.addAll(zone.far.bin);
            zone.near = null;
            zone.far = null;
        }
    }

    private Zone leaf(UInt128 distance) {
        Zone zone = this.root;
        while (!zone.isLeaf()) {
            zone = distance.bit(zone.level) == 0 ? zone.near : zone.far;
        }
        return zone;
    }

    private boolean canSplit(Zone zone) {
        return zone.level < MAX_LEVEL && (isSmall(zone.index) || zone.level < KBASE) && zone.bin.size() == K;
    }

    private void split(Zone zone) {
        zone.near = new Zone(zone.level + 1, shiftLeftOr(zone.index, 0));
        zone.far = new Zone(zone.level + 1, shiftLeftOr(zone.index, 1));
        for (KadContact c : zone.bin) {
            UInt128 distance = c.id().xor(this.own);
            (distance.bit(zone.level) == 0 ? zone.near : zone.far).bin.add(c);
        }
        zone.bin.clear();
    }

    // One contact per address in the whole table, and few per /24 unless on a LAN.
    private boolean fits(int ip, Zone leaf, KadContact moving) {
        KadContact sameIp = this.byIp.get(ip);
        if (sameIp != null && sameIp != moving) {
            return false;
        }
        if (this.lan.test(ip)) {
            return true;
        }
        int subnet = ip & 0xFFFF_FF00;
        if (this.perSubnet.getOrDefault(subnet, 0) >= PER_SUBNET) {
            return false;
        }
        int inBin = 0;
        for (KadContact c : leaf.bin) {
            if (c != moving && (c.ip() & 0xFFFF_FF00) == subnet) {
                inBin++;
            }
        }
        return inBin < PER_SUBNET_IN_BIN;
    }

    private void index(KadContact contact) {
        this.byId.put(contact.id(), contact);
        this.byIp.put(contact.ip(), contact);
        this.perSubnet.merge(contact.ip() & 0xFFFF_FF00, 1, Integer::sum);
    }

    private void unindex(KadContact contact) {
        this.byId.remove(contact.id());
        this.byIp.remove(contact.ip(), contact);
        this.perSubnet.computeIfPresent(contact.ip() & 0xFFFF_FF00, (k, v) -> v > 1 ? v - 1 : null);
    }

    private static boolean isSmall(UInt128 index) {
        return index.high() == 0 && Long.compareUnsigned(index.low(), KK) < 0;
    }

    private static UInt128 shiftLeftOr(UInt128 value, int bit) {
        long high = value.high() << 1 | value.low() >>> 63;
        long low = value.low() << 1 | bit;
        return new UInt128(high, low);
    }

    private static UInt128 shiftLeft(UInt128 value, int bits) {
        if (bits >= 128) {
            return UInt128.ZERO;
        }
        if (bits >= 64) {
            return new UInt128(value.low() << (bits - 64), 0);
        }
        if (bits == 0) {
            return value;
        }
        return new UInt128(value.high() << bits | value.low() >>> (64 - bits), value.low() << bits);
    }

    private static UInt128 ones() {
        return new UInt128(-1L, -1L);
    }
}
