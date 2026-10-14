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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class ChatMessagesTest {

    private static final String EMOJI = "😀";

    @Test
    void aCutMessageNeverEndsInHalfACharacter() throws DecodeException {
        String text = "a".repeat(ChatMessages.MAX_LENGTH - 1) + EMOJI + "tail";

        for (boolean unicode : new boolean[] {true, false}) {
            String sent = ChatMessages.text(ChatMessages.message(text, unicode));
            assertThat(sent).isEqualTo("a".repeat(ChatMessages.MAX_LENGTH - 1));
        }
        Packet received = Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.MESSAGE.code(),
                new PacketWriter().string16(text, StringEncoding.UTF8));
        assertThat(ChatMessages.text(received)).isEqualTo("a".repeat(ChatMessages.MAX_LENGTH - 1));
    }

    @Test
    void messagesUpToTheLimitPassWhole() throws DecodeException {
        String text = "b".repeat(ChatMessages.MAX_LENGTH - 2) + EMOJI;

        assertThat(ChatMessages.text(ChatMessages.message(text, true))).isEqualTo(text);
        assertThat(ChatMessages.text(ChatMessages.message("x".repeat(451), true)))
                .hasSize(ChatMessages.MAX_LENGTH);
    }

    @Test
    void captchaPicturesHaveAPlausibleSizeAndDimensions() throws DecodeException {
        assertThat(ChatMessages.captcha(ChatMessages.captchaRequest(bmp(129, 150, 50))))
                .hasSize(129);
        assertThat(ChatMessages.captcha(ChatMessages.captchaRequest(bmp(4095, 200, -100))))
                .hasSize(4095);
        for (byte[] bad : new byte[][] {
            bmp(128, 150, 50),
            bmp(4096, 150, 50),
            bmp(200, 201, 50),
            bmp(200, 0, 50),
            bmp(200, 150, 101),
            bmp(200, 150, Integer.MIN_VALUE)
        }) {
            assertThatThrownBy(() -> ChatMessages.captcha(ChatMessages.captchaRequest(bad)))
                    .isInstanceOf(DecodeException.class);
        }
        byte[] notBmp = bmp(200, 150, 50);
        notBmp[0] = 'X';
        assertThatThrownBy(() -> ChatMessages.captcha(ChatMessages.captchaRequest(notBmp)))
                .isInstanceOf(DecodeException.class);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 300) byte[] data) {
        Packet packet = Packet.of(Protocol.EMULE, Opcodes.Extended.CHATCAPTCHAREQ.code(), data);
        try {
            ChatMessages.captcha(packet);
        } catch (DecodeException expected) {
            // malformed
        }
        try {
            ChatMessages.text(packet);
        } catch (DecodeException expected) {
            // malformed
        }
    }

    private static byte[] bmp(int size, int width, int height) {
        byte[] image = new byte[size];
        image[0] = 'B';
        image[1] = 'M';
        ByteBuffer header = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(18, width);
        header.putInt(22, height);
        return image;
    }
}
