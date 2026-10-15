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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;

class ChatMessagesTest {

    private static final String EMOJI = "😀";

    private String text;
    private Packet packet;
    private String textRead;
    private byte[] image;
    private byte[] picture;
    private Throwable exception;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldNeverEndACutOutgoingMessageInHalfACharacter(boolean unicode) throws DecodeException {
        givenTheText("a".repeat(ChatMessages.MAX_LENGTH - 1) + EMOJI + "tail");

        whenTheTextIsSentAsAMessage(unicode);

        thenTheMessageCarries("a".repeat(ChatMessages.MAX_LENGTH - 1));
    }

    @Test
    void shouldNeverEndACutReceivedMessageInHalfACharacter() throws DecodeException {
        givenTheText("a".repeat(ChatMessages.MAX_LENGTH - 1) + EMOJI + "tail");
        givenAPeerSentItUncut();

        whenTheReceivedMessageIsRead();

        thenTheTextReadIs("a".repeat(ChatMessages.MAX_LENGTH - 1));
    }

    @Test
    void shouldPassAMessageUpToTheLimitWhole() throws DecodeException {
        givenTheText("b".repeat(ChatMessages.MAX_LENGTH - 2) + EMOJI);

        whenTheTextIsSentAsAMessage(true);

        thenTheMessageCarries("b".repeat(ChatMessages.MAX_LENGTH - 2) + EMOJI);
    }

    @Test
    void shouldCutALongerMessageToTheLimit() throws DecodeException {
        givenTheText("x".repeat(451));

        whenTheTextIsSentAsAMessage(true);

        thenTheMessageCarriesCharacters(ChatMessages.MAX_LENGTH);
    }

    @ParameterizedTest
    @CsvSource({"129, 150, 50", "4095, 200, -100"})
    void shouldAcceptACaptchaPictureOfPlausibleSizeAndDimensions(int size, int width, int height) {
        givenABitmap(size, width, height);

        whenTheCaptchaRequestIsRead();

        thenThePictureHasSize(size);
    }

    @ParameterizedTest
    @CsvSource({"128, 150, 50", "4096, 150, 50", "200, 201, 50", "200, 0, 50", "200, 150, 101", "200, 150, -2147483648"
    })
    void shouldRefuseACaptchaPictureOfImplausibleSizeOrDimensions(int size, int width, int height) {
        givenABitmap(size, width, height);

        whenTheCaptchaRequestIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Test
    void shouldRefuseACaptchaPictureThatIsNotABitmap() {
        givenABitmap(200, 150, 50);
        givenItsFirstByteIs('X');

        whenTheCaptchaRequestIsRead();

        thenExceptionOccurred(DecodeException.class);
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbageReadAsACaptcha(@ForAll @Size(max = 300) byte[] data) {
        givenACaptchaRequestCarrying(data);

        whenItIsReadAsACaptcha();

        thenItIsReadOrRefusedAsMalformed();
    }

    @Property
    void shouldOnlyFailWithDecodeExceptionOnGarbageReadAsAMessage(@ForAll @Size(max = 300) byte[] data) {
        givenACaptchaRequestCarrying(data);

        whenItIsReadAsAMessage();

        thenItIsReadOrRefusedAsMalformed();
    }

    private void givenTheText(String message) {
        this.text = message;
    }

    private void givenAPeerSentItUncut() {
        this.packet = Packet.of(
                Protocol.EDONKEY,
                Opcodes.Client.MESSAGE.code(),
                new PacketWriter().string16(this.text, StringEncoding.UTF8));
    }

    private void givenABitmap(int size, int width, int height) {
        this.image = bmp(size, width, height);
    }

    private void givenItsFirstByteIs(char first) {
        this.image[0] = (byte) first;
    }

    private void givenACaptchaRequestCarrying(byte[] data) {
        this.packet = Packet.of(Protocol.EMULE, Opcodes.Extended.CHATCAPTCHAREQ.code(), data);
    }

    private void whenTheTextIsSentAsAMessage(boolean unicode) {
        this.packet = ChatMessages.message(this.text, unicode);
    }

    private void whenTheReceivedMessageIsRead() throws DecodeException {
        this.textRead = ChatMessages.text(this.packet);
    }

    private void whenTheCaptchaRequestIsRead() {
        byte[] request = this.image;
        this.exception =
                catchThrowable(() -> this.picture = ChatMessages.captcha(ChatMessages.captchaRequest(request)));
    }

    private void whenItIsReadAsACaptcha() {
        Packet received = this.packet;
        this.exception = catchThrowable(() -> ChatMessages.captcha(received));
    }

    private void whenItIsReadAsAMessage() {
        Packet received = this.packet;
        this.exception = catchThrowable(() -> ChatMessages.text(received));
    }

    private void thenTheMessageCarries(String expected) throws DecodeException {
        assertThat(ChatMessages.text(this.packet)).isEqualTo(expected);
    }

    private void thenTheMessageCarriesCharacters(int length) throws DecodeException {
        assertThat(ChatMessages.text(this.packet)).hasSize(length);
    }

    private void thenTheTextReadIs(String expected) {
        assertThat(this.textRead).isEqualTo(expected);
    }

    private void thenThePictureHasSize(int size) {
        assertThat(this.exception).isNull();
        assertThat(this.picture).hasSize(size);
    }

    private void thenExceptionOccurred(Class<? extends Exception> type) {
        assertThat(this.exception).isInstanceOf(type);
    }

    private void thenItIsReadOrRefusedAsMalformed() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
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
