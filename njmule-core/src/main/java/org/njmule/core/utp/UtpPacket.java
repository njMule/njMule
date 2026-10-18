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
package org.njmule.core.utp;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.util.Bytes;

/**
 * A uTP packet (BEP 29, version 1): a 20-byte header in network order, the extensions, then the payload. Only the
 * selective acknowledgement extension is read; others are skipped.
 *
 * @param selectiveAck the bitmask of packets received past {@code ack + 1}, bit 0 of byte 0 for {@code ack + 2}
 */
public record UtpPacket(
        Type type,
        int connectionId,
        int timestamp,
        int timestampDifference,
        long window,
        int seq,
        int ack,
        Optional<Bytes> selectiveAck,
        Bytes payload) {

    /** The packet types, by their code. */
    public enum Type {
        DATA(0),
        FIN(1),
        STATE(2),
        RESET(3),
        SYN(4);

        private final int code;

        Type(int code) {
            this.code = code;
        }

        public int code() {
            return this.code;
        }

        static Optional<Type> of(int code) {
            for (Type type : values()) {
                if (type.code == code) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    public static final int VERSION = 1;
    public static final int HEADER = 20;
    private static final int SELECTIVE_ACK = 1;

    public UtpPacket {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(selectiveAck, "selectiveAck");
        Objects.requireNonNull(payload, "payload");
        if (selectiveAck.isPresent()
                && (selectiveAck.get().length() < 4 || selectiveAck.get().length() % 4 != 0)) {
            throw new IllegalArgumentException(
                    "a selective ack of " + selectiveAck.get().length() + " bytes");
        }
    }

    public byte[] encode() {
        int extensions = this.selectiveAck.map(mask -> 2 + mask.length()).orElse(0);
        ByteBuffer out = ByteBuffer.allocate(HEADER + extensions + this.payload.length());
        out.put((byte) (this.type.code() << 4 | VERSION))
                .put((byte) (this.selectiveAck.isPresent() ? SELECTIVE_ACK : 0))
                .putShort((short) this.connectionId)
                .putInt(this.timestamp)
                .putInt(this.timestampDifference)
                .putInt((int) this.window)
                .putShort((short) this.seq)
                .putShort((short) this.ack);
        this.selectiveAck.ifPresent(
                mask -> out.put((byte) 0).put((byte) mask.length()).put(mask.toByteArray()));
        out.put(this.payload.toByteArray());
        return out.array();
    }

    public static UtpPacket decode(byte[] data, int offset, int length) throws DecodeException {
        if (length < HEADER) {
            throw new DecodeException("a uTP packet of " + length + " bytes");
        }
        ByteBuffer in = ByteBuffer.wrap(data, offset, length);
        int first = in.get() & 0xFF;
        int typeCode = first >>> 4;
        Optional<Type> type = Type.of(typeCode);
        if ((first & 0x0F) != VERSION || type.isEmpty()) {
            throw new DecodeException("not a uTP packet of version 1");
        }
        int extension = in.get() & 0xFF;
        int connectionId = in.getShort() & 0xFFFF;
        int timestamp = in.getInt();
        int difference = in.getInt();
        long window = Integer.toUnsignedLong(in.getInt());
        int seq = in.getShort() & 0xFFFF;
        int ack = in.getShort() & 0xFFFF;
        Optional<Bytes> selectiveAck = Optional.empty();
        while (extension != 0) {
            if (in.remaining() < 2) {
                throw new DecodeException("a uTP extension cut short");
            }
            int next = in.get() & 0xFF;
            int size = in.get() & 0xFF;
            if (in.remaining() < size) {
                throw new DecodeException("a uTP extension cut short");
            }
            byte[] value = new byte[size];
            in.get(value);
            if (extension == SELECTIVE_ACK && size >= 4 && size % 4 == 0) {
                selectiveAck = Optional.of(Bytes.of(value));
            }
            extension = next;
        }
        byte[] payload = new byte[in.remaining()];
        in.get(payload);
        return new UtpPacket(
                type.get(), connectionId, timestamp, difference, window, seq, ack, selectiveAck, Bytes.of(payload));
    }
}
