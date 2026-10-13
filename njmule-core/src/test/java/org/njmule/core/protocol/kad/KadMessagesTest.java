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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Ipv4;

class KadMessagesTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final UInt128 ID = UInt128.parseHex("00112233445566778899AABBCCDDEEFF");
    private static final UInt128 OTHER = UInt128.parseHex("FFEEDDCCBBAA99887766554433221100");

    @Test
    void contactsCarryIdsByWordsAndAddressesInHostOrder() throws DecodeException {
        KadMessages.Contact contact = new KadMessages.Contact(OTHER, Ipv4.of(1, 2, 3, 4), 4672, 4662, 10);
        KadMessages.Answer answer = new KadMessages.Answer(ID, List.of(contact));

        assertThat(HEX.formatHex(answer.encode().payload()))
                .isEqualTo("33221100"
                        + "77665544"
                        + "bbaa9988"
                        + "ffeeddcc"
                        + "01"
                        + "ccddeeff"
                        + "8899aabb"
                        + "44556677"
                        + "00112233"
                        + "04030201"
                        + "4012"
                        + "3612"
                        + "0a");
        assertThat(KadMessages.Answer.decode(answer.encode())).isEqualTo(answer);
    }

    @Test
    void requestsHelloesAndBootstrapsRoundTrip() throws DecodeException {
        KadMessages.Request request = new KadMessages.Request(11, ID, OTHER);
        assertThat(KadMessages.Request.decode(request.encode())).isEqualTo(request);

        KadMessages.Hello hello = new KadMessages.Hello(
                new KadMessages.Self(ID, 4662, KadMessages.VERSION), List.of(new Tag.IntTag(TagName.id(0xFC), 4672)));
        assertThat(KadMessages.Hello.decode(hello.encode(false))).isEqualTo(hello);
        assertThat(hello.encode(true).opcode()).isEqualTo(0x19);

        KadMessages.BootstrapAnswer bootstrap = new KadMessages.BootstrapAnswer(
                new KadMessages.Self(ID, 4662, 10),
                List.of(new KadMessages.Contact(OTHER, Ipv4.of(5, 6, 7, 8), 1, 2, 9)));
        assertThat(KadMessages.BootstrapAnswer.decode(bootstrap.encode())).isEqualTo(bootstrap);

        assertThat(KadMessages.decodeHelloAck(KadMessages.helloAck(ID))).isEqualTo(ID);
        assertThat(KadMessages.decodePong(KadMessages.pong(4672))).isEqualTo(4672);
        assertThat(KadMessages.decodeFirewallAnswer(KadMessages.firewallAnswer(Ipv4.of(9, 8, 7, 6))))
                .isEqualTo(Ipv4.of(9, 8, 7, 6));
        KadMessages.FirewallCheck check = new KadMessages.FirewallCheck(4662, OTHER, 1);
        assertThat(KadMessages.FirewallCheck.decode(check.encode())).isEqualTo(check);
    }

    @Test
    void searchesPublishesAndTheirAnswersRoundTrip() throws DecodeException {
        KadMessages.KeywordSearch keyword = new KadMessages.KeywordSearch(ID, 0, new byte[] {1, 3, 0, 'a', 'b', 'c'});
        KadMessages.KeywordSearch back = KadMessages.KeywordSearch.decode(keyword.encode());
        assertThat(back).isEqualTo(keyword);
        assertThat(HEX.formatHex(keyword.encode().payload())).contains("0080");

        KadMessages.SourceSearch sources = new KadMessages.SourceSearch(ID, 0, 5_000_000_000L);
        assertThat(KadMessages.SourceSearch.decode(sources.encode())).isEqualTo(sources);

        List<Tag> tags = List.of(new Tag.StringTag(TagName.id(0x01), "a file name.iso"));
        KadMessages.Entry entry = new KadMessages.Entry(OTHER, tags);
        KadMessages.KeywordPublish publish = new KadMessages.KeywordPublish(ID, List.of(entry));
        assertThat(KadMessages.KeywordPublish.decode(publish.encode())).isEqualTo(publish);
        KadMessages.SourcePublish source = new KadMessages.SourcePublish(ID, OTHER, tags);
        assertThat(KadMessages.SourcePublish.decode(source.encode())).isEqualTo(source);
        KadMessages.PublishAnswer published = new KadMessages.PublishAnswer(ID, 7);
        assertThat(KadMessages.PublishAnswer.decode(published.encode())).isEqualTo(published);

        List<Packet> answers =
                KadMessages.SearchAnswer.split(OTHER, ID, java.util.Collections.nCopies(100, entry), 1420);
        assertThat(answers).hasSizeGreaterThan(1);
        int total = 0;
        for (Packet p : answers) {
            assertThat(p.length() + 2).isLessThanOrEqualTo(1420);
            KadMessages.SearchAnswer a = KadMessages.SearchAnswer.decode(p);
            assertThat(a.target()).isEqualTo(ID);
            total += a.results().size();
        }
        assertThat(total).isEqualTo(100);
    }

    @Test
    void refusesAnswersWithMoreContactsThanBytes() {
        byte[] payload = HEX.parseHex("00".repeat(16) + "02" + "00".repeat(25));
        assertThatThrownBy(() -> KadMessages.Answer.decode(Packet.of(Protocol.KAD, 0x29, payload)))
                .isInstanceOf(DecodeException.class);
        assertThatThrownBy(() ->
                        KadMessages.Request.decode(Packet.of(Protocol.KAD, 0x21, HEX.parseHex("00" + "00".repeat(32)))))
                .isInstanceOf(DecodeException.class);
    }

    @Property
    void garbageOnlyFailsWithDecodeException(@ForAll @Size(max = 120) byte[] data) {
        Packet packet = Packet.of(Protocol.KAD, 0x29, data);
        for (Decoder d : List.<Decoder>of(
                () -> KadMessages.Answer.decode(packet),
                () -> KadMessages.Request.decode(packet),
                () -> KadMessages.Hello.decode(packet),
                () -> KadMessages.BootstrapAnswer.decode(packet),
                () -> KadMessages.SearchAnswer.decode(packet),
                () -> KadMessages.KeywordPublish.decode(packet),
                () -> KadMessages.SourcePublish.decode(packet),
                () -> KadMessages.KeywordSearch.decode(packet),
                () -> KadMessages.SourceSearch.decode(packet),
                () -> KadMessages.PublishAnswer.decode(packet),
                () -> KadMessages.FirewallCheck.decode(packet))) {
            try {
                d.decode();
            } catch (DecodeException expected) {
                // malformed
            }
        }
    }

    private interface Decoder {
        void decode() throws DecodeException;
    }
}
