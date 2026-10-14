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
package org.njmule.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.njmule.core.api.Hash16;
import org.njmule.core.crypto.Rsa;
import org.njmule.core.peer.IdentityHandshake;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.packet.FrameLimit;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.packet.TcpFraming;

/** A hand-made client's side of the secure identification with an engine, honest or not. */
final class IdentityProof {

    /** What a client announces in its hello to take part. */
    static final int MISC1 = IdentityHandshake.SUPPORTED << 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<Integer> OPCODES = Set.of(
            Opcodes.Extended.SECIDENTSTATE.code(),
            Opcodes.Extended.PUBLICKEY.code(),
            Opcodes.Extended.SIGNATURE.code());

    private IdentityProof() {}

    /** Whether {@code packet} belongs to the identification. */
    static boolean isIdentification(Packet packet) {
        return packet.protocol() == Protocol.EMULE && OPCODES.contains(packet.opcode());
    }

    /**
     * Proves a new key to the engine whose hello answer is {@code engine}, over 127.0.0.1, until our signature is sent;
     * other packets read meanwhile are dropped.
     */
    static void prove(Hello engine, InputStream in, OutputStream out) throws IOException {
        IdentityHandshake.Identity own = IdentityHandshake.Identity.of(Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM));
        List<Packet> outgoing = new ArrayList<>();
        IdentityHandshake handshake = new IdentityHandshake(
                own,
                engine,
                Inet4Address.ofLiteral("127.0.0.1"),
                Optional.empty(),
                Optional.empty(),
                outgoing::add,
                new IdentityHandshake.Listener() {
                    @Override
                    public void identified(Hash16 userHash, byte[] publicKey) {}

                    @Override
                    public void failed(Hash16 userHash) {}
                });
        handshake.start(RANDOM);
        boolean signed = false;
        while (true) {
            for (Packet p : outgoing) {
                out.write(TcpFraming.encode(p));
                signed |= p.opcode() == Opcodes.Extended.SIGNATURE.code();
            }
            out.flush();
            outgoing.clear();
            if (signed) {
                return;
            }
            Packet packet = TcpFraming.read(in, FrameLimit.fixed(1 << 20));
            if (isIdentification(packet)) {
                try {
                    handshake.received(packet);
                } catch (DecodeException e) {
                    throw new IOException(e);
                }
            }
        }
    }

    /** A key and then a signature that cannot be right: the engine fails the client. */
    static void fake(OutputStream out) throws IOException {
        byte[] key = IdentityHandshake.Identity.of(Rsa.generate(Rsa.NETWORK_KEY_BITS, RANDOM))
                .publicKey();
        out.write(TcpFraming.encode(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.PUBLICKEY.code(),
                new PacketWriter().u8(key.length).bytes(key))));
        out.write(TcpFraming.encode(Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.SIGNATURE.code(),
                new PacketWriter().u8(48).bytes(new byte[48]))));
        out.flush();
    }
}
