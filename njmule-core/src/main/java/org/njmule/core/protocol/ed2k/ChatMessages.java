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
package org.njmule.core.protocol.ed2k;

import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.TagCodec;

/**
 * Chat between clients: a message is {@code <u16 length><text>}; a client that wants proof of a
 * human sends a CAPTCHA picture ({@code <u8 tag count>[tags]<BMP>}), whose solution comes back as a
 * message, and answers with a status byte.
 */
public final class ChatMessages {

    /** Characters a message has at most; longer ones are cut. */
    public static final int MAX_LENGTH = 450;

    /** CAPTCHA status: solved. */
    public static final int SOLVED = 0;

    private static final int MIN_IMAGE = 129;
    private static final int MAX_IMAGE = 4095;
    private static final int MAX_TAGS = 16;

    private ChatMessages() {}

    /** A message; {@code unicode} when the peer announced Unicode support. */
    public static Packet message(String text, boolean unicode) {
        String cut = text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
        return Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.MESSAGE.code(),
                new PacketWriter()
                        .string16(
                                cut,
                                unicode ? StringEncoding.UTF8 : StringEncoding.UTF8_BOM_IF_NEEDED));
    }

    /** The text of a message, cut to {@link #MAX_LENGTH} characters. */
    public static String text(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        String text = in.string16();
        if (in.remaining() != 0) {
            throw new DecodeException("a message with " + in.remaining() + " bytes after its text");
        }
        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }

    /** The picture of a CAPTCHA request, a BMP file of a plausible size. */
    public static byte[] captcha(Packet packet) throws DecodeException {
        PacketReader in = packet.reader();
        TagCodec.readEd2kList(in, in.u8(), MAX_TAGS);
        int size = in.remaining();
        if (size < MIN_IMAGE || size > MAX_IMAGE) {
            throw new DecodeException("a CAPTCHA picture of " + size + " bytes");
        }
        return in.bytes(size);
    }

    /** Whether a CAPTCHA result says solved. */
    public static boolean solved(Packet packet) throws DecodeException {
        return packet.reader().u8() == SOLVED;
    }

    /** A CAPTCHA request with {@code image}, as a peer sends it. */
    public static Packet captchaRequest(byte[] image) {
        return Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.CHATCAPTCHAREQ.code(),
                new PacketWriter().u8(0).bytes(image));
    }

    /** A CAPTCHA result, as a peer sends it. */
    public static Packet captchaResult(int status) {
        return Packet.of(
                Protocol.EMULE,
                Opcodes.Extended.CHATCAPTCHARES.code(),
                new PacketWriter().u8(status));
    }
}
