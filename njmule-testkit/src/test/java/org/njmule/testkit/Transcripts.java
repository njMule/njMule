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
package org.njmule.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.njmule.core.protocol.ed2k.Hello;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.packet.Opcodes;
import org.njmule.core.protocol.packet.Packet;
import org.njmule.core.protocol.packet.Protocol;
import org.njmule.core.protocol.tag.ClientTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.testkit.Transcript.Received;
import org.njmule.testkit.Transcript.Receiver;

/**
 * Checks what the fakes of a test received against the transcript recorded for it, in
 * {@code transcripts/<class>/<test>.txt}; {@code -Pnjmule.transcripts.write=true} records it instead. Another engine
 * driven through the same flows must send the same packets.
 *
 * <p>A transcript is made comparable: each connection's packets in hex, each told once in the order it first came (an
 * engine repeats some on its own timers), with what changes from one run to the next masked: the engine's user hash and
 * port, the version it tells, random challenges, the ranges a download picks at random.
 */
final class Transcripts implements BeforeEachCallback, AfterEachCallback {

    private static final Path SOURCES = Path.of("src", "test", "resources", "transcripts");
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> CHANNELS = List.of("tcp", "udp", "udp-obfuscated");
    private static final Set<Integer> VERSION_TAGS = Set.of(ClientTag.EMULE_VERSION, ClientTag.MOD_VERSION);

    private record Span(int from, int to, String name) {}

    @Override
    public void beforeEach(ExtensionContext context) {
        Transcript.takeStarted();
    }

    @Override
    public void afterEach(ExtensionContext context) throws IOException {
        List<Transcript> transcripts = Transcript.takeStarted();
        // A test that failed or was skipped tells nothing of the packets.
        if (context.getExecutionException().isPresent()) {
            return;
        }
        String name = context.getRequiredTestClass().getSimpleName() + "/"
                + context.getRequiredTestMethod().getName() + ".txt";
        String text = text(transcripts);
        if (Boolean.getBoolean("njmule.transcripts.write")) {
            Files.createDirectories(SOURCES.resolve(name).getParent());
            Files.writeString(SOURCES.resolve(name), text);
            return;
        }
        assertThat(text)
                .as("packets against transcripts/%s (-Pnjmule.transcripts.write=true records them)", name)
                .isEqualTo(recorded(name));
    }

    private static String recorded(String name) throws IOException {
        try (InputStream in = Transcripts.class.getResourceAsStream("/transcripts/" + name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String text(List<Transcript> transcripts) {
        StringBuilder text = new StringBuilder();
        Map<Receiver, Integer> counts = new TreeMap<>();
        for (Transcript transcript : transcripts) {
            int number = counts.merge(transcript.receiver(), 1, Integer::sum);
            text.append("# ")
                    .append(transcript.receiver().name().toLowerCase(Locale.ROOT))
                    .append(' ')
                    .append(number)
                    .append('\n');
            lines(transcript).forEach(line -> text.append(line).append('\n'));
        }
        return text.toString();
    }

    // Per channel and connection, each line once in the order it first came.
    private static List<String> lines(Transcript transcript) {
        Map<Received, Set<String>> groups =
                new TreeMap<>(Comparator.<Received>comparingInt(r -> CHANNELS.indexOf(r.channel()))
                        .thenComparingInt(Received::connection));
        for (Received received : transcript.received()) {
            Received group = new Received(received.channel(), received.connection(), null);
            groups.computeIfAbsent(group, g -> new LinkedHashSet<>()).add(line(transcript.receiver(), received));
        }
        List<String> lines = new ArrayList<>();
        groups.values().forEach(lines::addAll);
        return lines;
    }

    private static String line(Receiver receiver, Received received) {
        Packet packet = received.packet();
        String where = received.channel().equals("tcp") ? "tcp " + received.connection() : received.channel();
        return where + " " + HEX.toHexDigits((byte) packet.protocol()) + " " + HEX.toHexDigits((byte) packet.opcode())
                + " " + masked(packet.payload(), spans(receiver, received.channel(), packet));
    }

    private static List<Span> spans(Receiver receiver, String channel, Packet packet) {
        byte[] payload = packet.payload();
        int opcode = packet.opcode();
        try {
            if (receiver == Receiver.SERVER && channel.equals("tcp") && packet.protocol() == Protocol.EDONKEY) {
                if (opcode == Opcodes.Server.LOGINREQUEST.code()) {
                    return login(payload);
                }
            } else if (receiver == Receiver.SERVER && packet.protocol() == Protocol.EDONKEY) {
                if (opcode == Opcodes.ServerUdp.GLOBSERVSTATREQ.code()) {
                    return List.of(new Span(0, 2, "challenge"));
                }
                if (opcode == Opcodes.ServerUdp.SERVER_DESC_REQ.code()) {
                    return List.of(new Span(2, 4, "challenge"));
                }
            } else if (receiver == Receiver.PEER && packet.protocol() == Protocol.EDONKEY) {
                if (opcode == Opcodes.Client.HELLO.code()) {
                    return hello(payload, 1);
                }
                if (opcode == Opcodes.Client.HELLOANSWER.code()) {
                    return hello(payload, 0);
                }
                if (opcode == Opcodes.Client.REQUESTPARTS.code()) {
                    return List.of(new Span(16, payload.length, "ranges"));
                }
            } else if (receiver == Receiver.PEER
                    && packet.protocol() == Protocol.EMULE
                    && opcode == Opcodes.Extended.REQUESTPARTS_I64.code()) {
                return List.of(new Span(16, payload.length, "ranges"));
            }
        } catch (DecodeException e) {
            // Not what it should be: compared as it came.
        }
        return List.of();
    }

    // A login: <user hash 16><client id 4><port 2><count 4><tags>.
    private static List<Span> login(byte[] payload) throws DecodeException {
        List<Span> spans = new ArrayList<>();
        spans.add(new Span(0, 16, "user"));
        spans.add(new Span(20, 22, "port"));
        tags(PacketReader.of(payload), 22, spans);
        return spans;
    }

    // A hello from the user hash at {@code at}: a login's fields, then the server we are on <ip 4><port 2>. Its
    // client id is the address of the machine's network the tests reach the engine on, as is the server's.
    private static List<Span> hello(byte[] payload, int at) throws DecodeException {
        List<Span> spans = new ArrayList<>();
        spans.add(new Span(at, at + 16, "user"));
        spans.add(new Span(at + 16, at + 20, "id"));
        spans.add(new Span(at + 20, at + 22, "port"));
        PacketReader in = PacketReader.of(payload);
        tags(in, at + 22, spans);
        spans.add(new Span(in.position(), payload.length, "server"));
        return spans;
    }

    private static void tags(PacketReader in, int at, List<Span> spans) throws DecodeException {
        in.skip(at);
        long count = in.u32();
        for (long i = 0; i < count; i++) {
            int from = in.position();
            Tag tag = TagCodec.readEd2k(in);
            TagName name = tag.name();
            if (name.isId() && VERSION_TAGS.contains(name.id())) {
                spans.add(new Span(from, in.position(), "version"));
            } else if (name.isId() && name.id() == ClientTag.MOD_YOUR_IP) {
                spans.add(new Span(from, in.position(), "address"));
            } else if (name.isId() && name.id() == ClientTag.MOD_MISCOPTIONS && tag instanceof Tag.IntTag options) {
                // Router traversal is told once its sockets are up, which a hello at the start may come before.
                long bits = options.value() & ~(Hello.MOD_MISC_UTP_TRAVERSAL | Hello.MOD_MISC_QUIC_TRAVERSAL);
                spans.add(new Span(from, in.position(), "options " + Long.toHexString(bits)));
            }
        }
    }

    private static String masked(byte[] payload, List<Span> spans) {
        StringBuilder hex = new StringBuilder();
        int at = 0;
        for (Span span : spans) {
            hex.append(HEX.formatHex(payload, at, span.from()))
                    .append('{')
                    .append(span.name())
                    .append('}');
            at = span.to();
        }
        return hex.append(HEX.formatHex(payload, at, payload.length)).toString();
    }
}
