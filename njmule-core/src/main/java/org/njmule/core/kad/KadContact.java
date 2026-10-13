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

import java.util.Objects;
import org.njmule.core.persist.format.NodesDat;
import org.njmule.core.protocol.kad.KadMessages;

/**
 * A node of our routing table. Its type ages from 3 (new) to 0 (known for hours) while it answers and climbs to 4 (to
 * be removed) while it does not. Engine thread only.
 */
final class KadContact {

    /** Type of a new contact. */
    static final int NEW = 3;

    /** Type of a contact that stopped answering. */
    static final int DEAD = 4;

    private static final long MINUTE = 60_000_000_000L;
    private static final long HOUR = 60 * MINUTE;

    private final UInt128 id;
    private int ip;
    private int udpPort;
    private int tcpPort;
    private int version;
    private NodesDat.UdpKey udpKey;
    private boolean verified;
    private int type = NEW;
    private final long created;
    private long expires;
    private long typeSet;
    private int inUse;
    private boolean helloReceived;

    KadContact(UInt128 id, int ip, int udpPort, int tcpPort, int version, long now) {
        this.id = Objects.requireNonNull(id, "id");
        this.ip = ip;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.version = version;
        this.udpKey = NodesDat.UdpKey.NONE;
        this.created = now;
    }

    static KadContact of(KadMessages.Contact c, long now) {
        return new KadContact(c.id(), c.ip(), c.udpPort(), c.tcpPort(), c.version(), now);
    }

    UInt128 id() {
        return this.id;
    }

    int ip() {
        return this.ip;
    }

    int udpPort() {
        return this.udpPort;
    }

    int tcpPort() {
        return this.tcpPort;
    }

    int version() {
        return this.version;
    }

    NodesDat.UdpKey udpKey() {
        return this.udpKey;
    }

    boolean isVerified() {
        return this.verified;
    }

    int type() {
        return this.type;
    }

    long expires() {
        return this.expires;
    }

    boolean helloReceived() {
        return this.helloReceived;
    }

    boolean inUse() {
        return this.inUse > 0;
    }

    void use() {
        this.inUse++;
    }

    void release() {
        this.inUse = Math.max(0, this.inUse - 1);
    }

    void setVerified(boolean value) {
        this.verified = value;
    }

    void setUdpKey(NodesDat.UdpKey key) {
        this.udpKey = Objects.requireNonNull(key, "key");
    }

    void setHelloReceived() {
        this.helloReceived = true;
    }

    void moveTo(int newIp, int newUdpPort) {
        if (newIp != this.ip) {
            this.verified = false;
        }
        this.ip = newIp;
        this.udpPort = newUdpPort;
    }

    void update(int newTcpPort, int newVersion) {
        this.tcpPort = newTcpPort;
        this.version = Math.max(this.version, newVersion);
    }

    /** The contact answered: its type falls with its age. */
    void alive(long now) {
        long age = now - this.created;
        if (age < HOUR) {
            this.type = 2;
            this.expires = now + HOUR;
        } else if (age < 2 * HOUR) {
            this.type = 1;
            this.expires = now + HOUR + HOUR / 2;
        } else {
            this.type = 0;
            this.expires = now + 2 * HOUR;
        }
        this.typeSet = now;
    }

    /** We are about to check the contact: its type climbs until it answers. */
    void checking(long now) {
        if (now - this.typeSet < 10_000_000_000L || this.type == DEAD) {
            return;
        }
        this.typeSet = now;
        this.type++;
        this.expires = now + 2 * MINUTE;
    }

    boolean isExpired(long now) {
        return this.expires > 0 && now - this.expires >= 0;
    }

    void touchExpires(long now) {
        if (this.expires == 0) {
            this.expires = now;
        }
    }

    KadMessages.Contact message() {
        return new KadMessages.Contact(this.id, this.ip, this.udpPort, this.tcpPort, this.version);
    }

    NodesDat.Contact record() {
        return new NodesDat.Contact(
                this.id, this.ip, this.udpPort, this.tcpPort, this.version, this.type, this.udpKey, this.verified);
    }
}
