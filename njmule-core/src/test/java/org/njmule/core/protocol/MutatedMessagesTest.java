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
package org.njmule.core.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.njmule.core.Mutations;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.ed2k.ChatMessages;
import org.njmule.core.protocol.ed2k.ClientMessages;
import org.njmule.core.protocol.ed2k.ClientReasks;
import org.njmule.core.protocol.ed2k.FileDescription;
import org.njmule.core.protocol.ed2k.FileEntry;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.ed2k.LowIdMessages;
import org.njmule.core.protocol.ed2k.ServerDatagrams;
import org.njmule.core.protocol.ed2k.ServerMessages;
import org.njmule.core.protocol.ed2k.SourceExchange;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Packing;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.search.SearchParser;
import org.njmule.core.search.SearchTreeCodec;
import org.njmule.core.util.Bytes;

/**
 * Messages of the network made right, then changed as a broken or hostile peer or server could change them: their
 * decoders only refuse them with a {@link DecodeException}, never with another exception.
 */
class MutatedMessagesTest {

    private static final Hash16 FILE = Hash16.parseHex("0123456789abcdef0123456789abcdef");
    private static final Hash16 USER = Hash16.parseHex("00112233445566778899aabbccddeeff");
    private static final UInt128 ID = UInt128.parseHex("8899aabbccddeeff0011223344556677");
    private static final UInt128 TARGET = UInt128.parseHex("ffeeddccbbaa99887766554433221100");
    private static final int IP = 0x0A00_0001;

    @FunctionalInterface
    private interface Decoder {
        Object decode(Packet packet) throws DecodeException;
    }

    private record Sample(String name, Packet packet, Decoder decoder) {
        @Override
        public String toString() {
            return this.name;
        }
    }

    private Decoder decoder;
    private Packet packet;
    private Throwable exception;

    @ParameterizedTest
    @MethodSource("samples")
    void shouldDecodeEverySampleAsItIsMade(Sample sample) {
        givenTheSample(sample);

        whenItsDecoderReadsIt();

        thenNoExceptionOccurred();
    }

    @Property(tries = 5000)
    void shouldOnlyRefuseAChangedMessage(@ForAll("anySample") Sample sample, @ForAll long seed) {
        givenTheSampleChanged(sample, seed);

        whenItsDecoderReadsIt();

        thenNothingButADecodeExceptionOccurred();
    }

    @Provide
    Arbitrary<Sample> anySample() {
        return Arbitraries.of(samples());
    }

    private void givenTheSample(Sample sample) {
        this.decoder = sample.decoder();
        this.packet = sample.packet();
    }

    private void givenTheSampleChanged(Sample sample, long seed) {
        this.decoder = sample.decoder();
        this.packet = Packet.of(
                sample.packet().protocol(),
                sample.packet().opcode(),
                Mutations.changed(sample.packet().payload(), seed));
    }

    private void whenItsDecoderReadsIt() {
        this.exception = catchThrowable(() -> this.decoder.decode(this.packet));
    }

    private void thenNoExceptionOccurred() {
        assertThat(this.exception).isNull();
    }

    private void thenNothingButADecodeExceptionOccurred() {
        if (this.exception != null) {
            assertThat(this.exception).isInstanceOf(DecodeException.class);
        }
    }

    static List<Sample> samples() {
        List<Sample> all = new ArrayList<>();
        all.addAll(serverSamples());
        all.addAll(clientSamples());
        all.addAll(kadSamples());
        all.addAll(packedSamples());
        return all;
    }

    // Packed with zlib: a change reaches the inflater, which has a cap, before the decoder.
    private static List<Sample> packedSamples() {
        FileEntry entry = new FileEntry(FILE, IP, 4662, fileTags());
        List<FileEntry> entries = List.of(entry, entry, entry, entry, entry, entry, entry, entry);
        KadMessages.Entry kadEntry = new KadMessages.Entry(TARGET, kadTags());
        SourceExchange.Source source = new SourceExchange.Source(IP, 0, 4662, IP + 1, 4661, Optional.of(USER), 0x01);
        return List.of(
                new Sample(
                        "packed search results",
                        Packing.pack(new ServerMessages.SearchResults(entries, false).encode()),
                        p -> ServerMessages.SearchResults.decode(Packing.unpackFromServer(p))),
                new Sample(
                        "packed source exchange answer",
                        Packing.pack(
                                new SourceExchange.Answer(4, FILE, List.of(source, source, source, source)).encode()),
                        p -> SourceExchange.Answer.decode(Packing.unpack(p, Packing.MAX_CLIENT_UNPACKED))),
                new Sample(
                        "packed kad search answer",
                        Packing.pack(new KadMessages.SearchAnswer(ID, TARGET, List.of(kadEntry, kadEntry, kadEntry))
                                .encode()),
                        p -> KadMessages.SearchAnswer.decode(Packing.unpack(p, Packing.MAX_CLIENT_UNPACKED))));
    }

    private static List<Sample> serverSamples() {
        FileEntry entry = new FileEntry(FILE, IP, 4662, fileTags());
        return List.of(
                new Sample(
                        "login",
                        new ServerMessages.Login(USER, 0, 4662, "nick", 0x31D).encode(),
                        ServerMessages.Login::decode),
                new Sample(
                        "id change",
                        new ServerMessages.IdChange(IP, 0x19, IP).encode(),
                        ServerMessages.IdChange::decode),
                new Sample(
                        "server status",
                        new ServerMessages.ServerStatus(1200, 34_000).encode(),
                        ServerMessages.ServerStatus::decode),
                new Sample(
                        "server ident",
                        new ServerMessages.ServerIdent(FILE, IP, 4661, Optional.of("name"), Optional.of("words"))
                                .encode(),
                        ServerMessages.ServerIdent::decode),
                new Sample(
                        "search results",
                        new ServerMessages.SearchResults(List.of(entry, entry), true).encode(),
                        ServerMessages.SearchResults::decode),
                new Sample(
                        "found sources",
                        new ServerMessages.FoundSources(
                                        FILE,
                                        List.of(
                                                new ServerMessages.Source(IP, 4662, 0, Optional.empty()),
                                                new ServerMessages.Source(
                                                        IP + 1,
                                                        4663,
                                                        ServerMessages.Source.HAS_USER_HASH,
                                                        Optional.of(USER))))
                                .encode(),
                        ServerMessages.FoundSources::decode),
                new Sample("server message", ServerMessages.message("hello\nthere"), ServerMessages::decodeMessage),
                new Sample(
                        "server list",
                        ServerMessages.serverList(List.of(
                                new ServerMessages.Endpoint(IP, 4661), new ServerMessages.Endpoint(IP + 1, 4242))),
                        ServerMessages::decodeServerList),
                new Sample(
                        "server status datagram",
                        new ServerDatagrams.Status(0x55AA_0001L, 10, 20, 30, 40, 50, 0x3FF, 5, 4672, 4661, 0x1234L)
                                .encode(),
                        ServerDatagrams.Status::decode),
                new Sample(
                        "server description",
                        new ServerDatagrams.Description(
                                        ServerDatagrams.descriptionChallenge(7),
                                        List.of(new Tag.StringTag(TagName.id(0x01), "name")))
                                .encode(),
                        ServerDatagrams.Description::decode),
                new Sample(
                        "global search results",
                        ServerDatagrams.searchResults(List.of(entry)),
                        ServerDatagrams::decodeSearchResults),
                new Sample(
                        "global found sources",
                        ServerDatagrams.foundSources(List.of(new ServerMessages.FoundSources(
                                FILE, List.of(new ServerMessages.Source(IP, 4662, 0, Optional.empty()))))),
                        ServerDatagrams::decodeFoundSources));
    }

    private static List<Sample> clientSamples() {
        BitSet parts = new BitSet();
        parts.set(0, 3);
        parts.set(7);
        ClientMessages.PartStatus status = new ClientMessages.PartStatus(9, parts);
        return List.of(
                new Sample("hello", new Hello(USER, IP, 4662, helloTags(), IP + 1, 4661).encode(false), Hello::decode),
                new Sample("hello answer", new Hello(USER, IP, 4662, helloTags(), 0, 0).encode(true), Hello::decode),
                new Sample(
                        "file name",
                        new ClientMessages.FileName(FILE, "a file.mkv").encode(),
                        ClientMessages.FileName::decode),
                new Sample(
                        "file status",
                        new ClientMessages.FileStatus(FILE, 9, parts).encode(),
                        ClientMessages.FileStatus::decode),
                new Sample(
                        "hash set",
                        new ClientMessages.HashSet(FILE, List.of(USER, FILE, USER)).encode(),
                        ClientMessages.HashSet::decode),
                new Sample(
                        "request parts",
                        new ClientMessages.RequestParts(
                                        FILE,
                                        List.of(
                                                new ClientMessages.Range(0, 184_320),
                                                new ClientMessages.Range(184_320, 368_640),
                                                new ClientMessages.Range(9_728_000, 9_900_000)))
                                .encode(false),
                        ClientMessages.RequestParts::decode),
                new Sample(
                        "request parts, 64 bit",
                        new ClientMessages.RequestParts(
                                        FILE,
                                        List.of(
                                                new ClientMessages.Range(5_000_000_000L, 5_000_184_320L),
                                                new ClientMessages.Range(5_000_184_320L, 5_000_368_640L),
                                                new ClientMessages.Range(0, 184_320)))
                                .encode(true),
                        ClientMessages.RequestParts::decode),
                new Sample(
                        "sending part",
                        new ClientMessages.SendingPart(FILE, 100, 120, new byte[20]).encode(false),
                        ClientMessages.SendingPart::decode),
                new Sample(
                        "compressed part",
                        new ClientMessages.CompressedPart(FILE, 0, 64, new byte[24]).encode(true),
                        ClientMessages.CompressedPart::decode),
                new Sample(
                        "source exchange answer",
                        new SourceExchange.Answer(
                                        4,
                                        FILE,
                                        List.of(
                                                new SourceExchange.Source(
                                                        IP, 0, 4662, IP + 1, 4661, Optional.of(USER), 0x01),
                                                new SourceExchange.Source(
                                                        0, 1234, 4662, IP + 1, 4661, Optional.empty(), 0)))
                                .encode(),
                        SourceExchange.Answer::decode),
                new Sample("source exchange request", SourceExchange.request(FILE), SourceExchange.Request::decode),
                new Sample(
                        "reask ping",
                        new ClientReasks.Ping(FILE, status, 3).encode(4),
                        p -> ClientReasks.Ping.decode(p, 4)),
                new Sample(
                        "reask ack",
                        new ClientReasks.Ack(Optional.of(status), 12).encode(4),
                        p -> ClientReasks.Ack.decode(p, 4)),
                new Sample(
                        "reask through a buddy",
                        new ClientReasks.Ping(FILE, status, 3).encodeThroughBuddy(USER, 4),
                        ClientReasks.ThroughBuddy::decode),
                new Sample(
                        "callback",
                        new LowIdMessages.Callback(ID, TARGET, IP, 4672).encode(),
                        LowIdMessages.Callback::decode),
                new Sample(
                        "direct callback request",
                        new LowIdMessages.DirectCallbackRequest(4662, USER, 0x03).encode(),
                        LowIdMessages.DirectCallbackRequest::decode),
                new Sample(
                        "udp check request",
                        new LowIdMessages.UdpCheckRequest(4672, 4673, 0x1234_5678).encode(),
                        LowIdMessages.UdpCheckRequest::decode),
                new Sample(
                        "file comment",
                        FileDescription.encode(new FileComment("good copy", 4)),
                        FileDescription::decode),
                new Sample("chat message", ChatMessages.message("hi there", true), ChatMessages::text));
    }

    private static List<Sample> kadSamples() {
        KadMessages.Self self = new KadMessages.Self(ID, 4662, 9);
        List<KadMessages.Contact> contacts = List.of(
                new KadMessages.Contact(ID, IP, 4672, 4662, 9), new KadMessages.Contact(TARGET, IP + 1, 4673, 0, 8));
        KadMessages.Entry entry = new KadMessages.Entry(TARGET, kadTags());
        PacketWriter expression = new PacketWriter();
        SearchTreeCodec.write(expression, SearchParser.parse("linux iso OR ubuntu NOT beta @size>700m"), true);
        byte[] tree = expression.toByteArray();
        return List.of(
                new Sample(
                        "bootstrap answer",
                        new KadMessages.BootstrapAnswer(self, contacts).encode(),
                        KadMessages.BootstrapAnswer::decode),
                new Sample(
                        "kad hello",
                        new KadMessages.Hello(self, List.of(new Tag.IntTag(TagName.id(0xF8), 2))).encode(false),
                        KadMessages.Hello::decode),
                new Sample(
                        "kad request", new KadMessages.Request(11, TARGET, ID).encode(), KadMessages.Request::decode),
                new Sample("kad answer", new KadMessages.Answer(TARGET, contacts).encode(), KadMessages.Answer::decode),
                new Sample(
                        "keyword search and its expression",
                        new KadMessages.KeywordSearch(TARGET, 0, tree).encode(),
                        p -> {
                            KadMessages.KeywordSearch search = KadMessages.KeywordSearch.decode(p);
                            return search.expression().length == 0
                                    ? search
                                    : SearchTreeCodec.read(PacketReader.of(search.expression()));
                        }),
                new Sample(
                        "source search",
                        new KadMessages.SourceSearch(TARGET, 0, 700_000_000L).encode(),
                        KadMessages.SourceSearch::decode),
                new Sample(
                        "notes search",
                        new KadMessages.NotesSearch(TARGET, 700_000_000L).encode(),
                        KadMessages.NotesSearch::decode),
                new Sample(
                        "search answer",
                        new KadMessages.SearchAnswer(ID, TARGET, List.of(entry, entry)).encode(),
                        KadMessages.SearchAnswer::decode),
                new Sample(
                        "keyword publish",
                        new KadMessages.KeywordPublish(TARGET, List.of(entry)).encode(),
                        KadMessages.KeywordPublish::decode),
                new Sample(
                        "source publish",
                        new KadMessages.SourcePublish(TARGET, ID, List.of(new Tag.IntTag(TagName.id(0xFD), IP)))
                                .encode(),
                        KadMessages.SourcePublish::decode),
                new Sample(
                        "notes publish",
                        new KadMessages.NotesPublish(TARGET, ID, List.of(new Tag.StringTag(TagName.id(0x01), "a")))
                                .encode(),
                        KadMessages.NotesPublish::decode),
                new Sample(
                        "publish answer",
                        new KadMessages.PublishAnswer(TARGET, 40).encode(),
                        KadMessages.PublishAnswer::decode),
                new Sample(
                        "firewall check",
                        new KadMessages.FirewallCheck(4662, ID, 0x03).encode(),
                        KadMessages.FirewallCheck::decode),
                new Sample("firewall answer", KadMessages.firewallAnswer(IP), KadMessages::decodeFirewallAnswer),
                new Sample(
                        "find buddy",
                        new KadMessages.FindBuddy(TARGET, ID, 4662).encode(),
                        KadMessages.FindBuddy::decode),
                new Sample(
                        "buddy offer",
                        new KadMessages.BuddyOffer(TARGET, ID, 4662, OptionalInt.of(3)).encode(),
                        KadMessages.BuddyOffer::decode),
                new Sample(
                        "kad callback request",
                        new KadMessages.CallbackRequest(TARGET, ID, 4662).encode(),
                        KadMessages.CallbackRequest::decode),
                new Sample(
                        "udp firewall answer",
                        new KadMessages.FirewallUdp(0, 4672).encode(),
                        KadMessages.FirewallUdp::decode),
                new Sample("pong", KadMessages.pong(4672), KadMessages::decodePong));
    }

    private static List<Tag> fileTags() {
        return List.of(
                new Tag.StringTag(TagName.id(0x01), "film.mkv"),
                new Tag.IntTag(TagName.id(0x02), 734_003_200L),
                new Tag.IntTag(TagName.id(0x15), 12),
                new Tag.StringTag(TagName.id(0x03), "Video"),
                new Tag.BlobTag(TagName.of("custom"), Bytes.of(new byte[] {1, 2, 3})));
    }

    private static List<Tag> kadTags() {
        return List.of(
                new Tag.StringTag(TagName.id(0x01), "film.mkv"),
                new Tag.IntTag(TagName.id(0x02), 734_003_200L),
                new Tag.IntTag(TagName.id(0x15), 12),
                new Tag.FloatTag(TagName.id(0xD3), 1.5f));
    }

    private static List<Tag> helloTags() {
        return List.of(
                new Tag.StringTag(TagName.id(0x01), "nick"),
                new Tag.IntTag(TagName.id(0x11), 0x3C),
                new Tag.IntTag(TagName.id(0x0F), 4662),
                new Tag.IntTag(TagName.id(0xFA), 0x2D3F_1F1EL));
    }
}
