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
package org.njmule.core.protocol.kad;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.io.BaseEncoding;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/** The two Kad messages of notes, byte for byte. */
class NotesMessagesTest {

    private static final UInt128 FILE = new UInt128(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
    private static final UInt128 PUBLISHER = new UInt128(0x1112131415161718L, 0x191A1B1C1D1E1F20L);

    private KadMessages.NotesPublish publish;
    private Packet packet;
    private KadMessages.NotesPublish decoded;

    @Test
    void shouldWriteANotePublishAsFilePublisherAndTags() throws Exception {
        givenANotePublish(List.of(
                new Tag.StringTag(TagName.id(FileTag.FILENAME), "a"),
                new Tag.IntTag(TagName.id(FileTag.FILERATING), 5)));

        whenItIsWrittenAndReadBack();

        thenThePacketIs(
                Opcodes.Kad.PUBLISH_NOTES_REQ,
                "0403020108070605" + "0c0b0a09100f0e0d"
                        + "1413121118171615" + "1c1b1a19201f1e1d"
                        + "02" + "02010001010061" + "090100f705");
        thenTheReadNoteIsTheWrittenOne();
    }

    @Test
    void shouldWriteANotesSearchAsFileAndSize() {
        whenANotesSearchIsWritten(1_000);

        thenThePacketIs(Opcodes.Kad.SEARCH_NOTES_REQ, "0403020108070605" + "0c0b0a09100f0e0d" + "e803000000000000");
    }

    private void givenANotePublish(List<Tag> tags) {
        this.publish = new KadMessages.NotesPublish(FILE, PUBLISHER, tags);
    }

    private void whenItIsWrittenAndReadBack() throws DecodeException {
        this.packet = this.publish.encode();
        this.decoded = KadMessages.NotesPublish.decode(this.packet);
    }

    private void whenANotesSearchIsWritten(long size) {
        this.packet = new KadMessages.NotesSearch(FILE, size).encode();
    }

    private void thenThePacketIs(Opcodes.Kad opcode, String payload) {
        assertThat(this.packet.opcode()).isEqualTo(opcode.code());
        assertThat(BaseEncoding.base16().lowerCase().encode(this.packet.payload()))
                .isEqualTo(payload);
    }

    private void thenTheReadNoteIsTheWrittenOne() {
        assertThat(this.decoded).isEqualTo(this.publish);
    }
}
