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
package org.njmule.core.net;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import org.njmule.core.protocol.packet.Packet;

/** A client connection as one session sees it: the download from a client and the upload to it may share one. */
public interface PeerChannel {

    /** Queues a packet; {@code false} if the connection is closed or this session is done with it. */
    boolean send(Packet packet);

    /** As {@link #send(Packet)}, for file data, which waits behind the other packets. */
    default boolean sendData(Packet packet) {
        return send(packet);
    }

    /** Bytes of file data queued and not written yet. */
    default long queuedData() {
        return 0;
    }

    InetSocketAddress remoteAddress();

    boolean isOpen();

    /** Fails reads that wait longer than {@code timeout}, the longest any session sharing the connection asks for. */
    void setReadTimeout(Duration timeout) throws SocketException;

    /** This session is done with the connection, which closes once no session uses it. */
    void close();

    /** Closes the connection for every session, e.g. because the client broke the protocol. */
    void disconnect();
}
