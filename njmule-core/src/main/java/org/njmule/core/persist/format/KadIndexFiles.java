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
package org.njmule.core.persist.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.njmule.core.api.Hash20;
import org.njmule.core.kad.UInt128;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketReader;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;

/**
 * The Kad index a node keeps for others: {@code load_index.dat} (publish load per key), {@code src_index.dat} (sources
 * per file) and {@code key_index.dat} (files per keyword, with publish tracking). Each file starts with a version and a
 * time; entries carry their own lifetime.
 */
public final class KadIndexFiles {

    private static final int MAX_ITEMS = 10_000_000;

    /** {@code <version 1><save time><count>{<key><time>}}. */
    public record LoadIndex(long saveTime, List<Load> loads) {
        public LoadIndex {
            loads = List.copyOf(loads);
        }
    }

    public record Load(UInt128 key, long time) {}

    /** An indexed entry: when it expires and its Kad tags (name, size, IPs, ports, …). */
    public record Entry(long lifetime, List<Tag> tags) {
        public Entry {
            tags = List.copyOf(tags);
        }
    }

    public record Source(UInt128 id, List<Entry> entries) {
        public Source {
            Objects.requireNonNull(id, "id");
            entries = List.copyOf(entries);
        }
    }

    public record IndexKey(UInt128 key, List<Source> sources) {
        public IndexKey {
            Objects.requireNonNull(key, "key");
            sources = List.copyOf(sources);
        }
    }

    /** {@code src_index.dat}: version 2, valid until {@code expiry} (epoch seconds). */
    public record SourceIndex(int version, long expiry, List<IndexKey> keys) {
        public SourceIndex {
            keys = List.copyOf(keys);
        }
    }

    /** A file name published for a keyword with its popularity. */
    public record PublishedName(String name, long popularity) {}

    /** A publisher; {@code aichIndex} points into {@link Tracking#aichHashes} or is 0xFFFF. */
    public record Publisher(int ip, long time, int aichIndex) {}

    public record Tracking(List<Hash20> aichHashes, List<PublishedName> names, List<Publisher> publishers) {
        public static final Tracking NONE = new Tracking(List.of(), List.of(), List.of());

        public Tracking {
            aichHashes = List.copyOf(aichHashes);
            names = List.copyOf(names);
            publishers = List.copyOf(publishers);
        }
    }

    public record KeywordEntry(long lifetime, Tracking tracking, List<Tag> tags) {
        public KeywordEntry {
            Objects.requireNonNull(tracking, "tracking");
            tags = List.copyOf(tags);
        }
    }

    public record KeywordSource(UInt128 id, List<KeywordEntry> entries) {
        public KeywordSource {
            Objects.requireNonNull(id, "id");
            entries = List.copyOf(entries);
        }
    }

    public record Keyword(UInt128 key, List<KeywordSource> sources) {
        public Keyword {
            Objects.requireNonNull(key, "key");
            sources = List.copyOf(sources);
        }
    }

    /** {@code key_index.dat}: version 4, valid until {@code expiry}, for the node {@code ownId}. */
    public record KeywordIndex(int version, long expiry, UInt128 ownId, List<Keyword> keywords) {
        public KeywordIndex {
            Objects.requireNonNull(ownId, "ownId");
            keywords = List.copyOf(keywords);
        }
    }

    private KadIndexFiles() {}

    public static LoadIndex readLoad(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        long version = in.u32();
        if (version >= 2) {
            throw new DecodeException("unsupported load_index.dat version " + version);
        }
        long saveTime = in.u32();
        int n = in.count(in.u32(), 20, MAX_ITEMS);
        List<Load> loads = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            loads.add(new Load(UInt128.read(in), in.u32()));
        }
        return new LoadIndex(saveTime, loads);
    }

    public static byte[] writeLoad(LoadIndex index) {
        PacketWriter out = new PacketWriter();
        out.u32(1).u32(index.saveTime()).u32(index.loads().size());
        for (Load l : index.loads()) {
            l.key().write(out);
            out.u32(l.time());
        }
        return out.toByteArray();
    }

    public static SourceIndex readSources(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        long version = in.u32();
        if (version >= 3) {
            throw new DecodeException("unsupported src_index.dat version " + version);
        }
        long expiry = in.u32();
        int keys = in.count(in.u32(), 20, MAX_ITEMS);
        List<IndexKey> out = new ArrayList<>(keys);
        for (int k = 0; k < keys; k++) {
            UInt128 key = UInt128.read(in);
            int sources = in.count(in.u32(), 20, MAX_ITEMS);
            List<Source> list = new ArrayList<>(sources);
            for (int s = 0; s < sources; s++) {
                UInt128 id = UInt128.read(in);
                int entries = in.count(in.u32(), 5, MAX_ITEMS);
                List<Entry> es = new ArrayList<>(entries);
                for (int e = 0; e < entries; e++) {
                    es.add(new Entry(in.u32(), TagCodec.readKadList(in)));
                }
                list.add(new Source(id, es));
            }
            out.add(new IndexKey(key, list));
        }
        return new SourceIndex((int) version, expiry, out);
    }

    public static byte[] writeSources(SourceIndex index) {
        PacketWriter out = new PacketWriter();
        out.u32(2).u32(index.expiry()).u32(index.keys().size());
        for (IndexKey key : index.keys()) {
            key.key().write(out);
            out.u32(key.sources().size());
            for (Source s : key.sources()) {
                s.id().write(out);
                out.u32(s.entries().size());
                for (Entry e : s.entries()) {
                    out.u32(e.lifetime());
                    TagCodec.writeKadList(out, e.tags());
                }
            }
        }
        return out.toByteArray();
    }

    public static KeywordIndex readKeywords(byte[] data) throws DecodeException {
        PacketReader in = PacketReader.of(data);
        long version = in.u32();
        if (version >= 5) {
            throw new DecodeException("unsupported key_index.dat version " + version);
        }
        long expiry = in.u32();
        UInt128 ownId = UInt128.read(in);
        int keys = in.count(in.u32(), 20, MAX_ITEMS);
        List<Keyword> out = new ArrayList<>(keys);
        for (int k = 0; k < keys; k++) {
            UInt128 key = UInt128.read(in);
            int sources = in.count(in.u32(), 20, MAX_ITEMS);
            List<KeywordSource> list = new ArrayList<>(sources);
            for (int s = 0; s < sources; s++) {
                UInt128 id = UInt128.read(in);
                int names = in.count(in.u32(), 5, MAX_ITEMS);
                List<KeywordEntry> entries = new ArrayList<>(names);
                for (int e = 0; e < names; e++) {
                    long lifetime = in.u32();
                    Tracking tracking = version >= 3 ? tracking(in, version >= 4) : Tracking.NONE;
                    entries.add(new KeywordEntry(lifetime, tracking, TagCodec.readKadList(in)));
                }
                list.add(new KeywordSource(id, entries));
            }
            out.add(new Keyword(key, list));
        }
        return new KeywordIndex((int) version, expiry, ownId, out);
    }

    public static byte[] writeKeywords(KeywordIndex index) {
        PacketWriter out = new PacketWriter();
        out.u32(4).u32(index.expiry());
        index.ownId().write(out);
        out.u32(index.keywords().size());
        for (Keyword k : index.keywords()) {
            k.key().write(out);
            out.u32(k.sources().size());
            for (KeywordSource s : k.sources()) {
                s.id().write(out);
                out.u32(s.entries().size());
                for (KeywordEntry e : s.entries()) {
                    out.u32(e.lifetime());
                    Tracking t = e.tracking();
                    out.u16(t.aichHashes().size());
                    for (Hash20 h : t.aichHashes()) {
                        out.hash20(h);
                    }
                    out.u32(t.names().size());
                    for (PublishedName n : t.names()) {
                        out.string16(n.name(), StringEncoding.UTF8).u32(n.popularity());
                    }
                    out.u32(t.publishers().size());
                    for (Publisher p : t.publishers()) {
                        out.u32(p.ip() & 0xFFFF_FFFFL).u32(p.time()).u16(p.aichIndex());
                    }
                    TagCodec.writeKadList(out, e.tags());
                }
            }
        }
        return out.toByteArray();
    }

    private static Tracking tracking(PacketReader in, boolean withAich) throws DecodeException {
        List<Hash20> hashes = new ArrayList<>();
        if (withAich) {
            int n = in.count(in.u16(), Hash20.LENGTH, 0xFFFF);
            for (int i = 0; i < n; i++) {
                hashes.add(in.hash20());
            }
        }
        int names = in.count(in.u32(), 6, MAX_ITEMS);
        List<PublishedName> nameList = new ArrayList<>(names);
        for (int i = 0; i < names; i++) {
            nameList.add(new PublishedName(in.string16(), in.u32()));
        }
        int ips = in.count(in.u32(), withAich ? 10 : 8, MAX_ITEMS);
        List<Publisher> publishers = new ArrayList<>(ips);
        for (int i = 0; i < ips; i++) {
            int ip = in.i32();
            long time = in.u32();
            int aich = withAich ? in.u16() : 0xFFFF;
            if (aich != 0xFFFF && aich >= hashes.size()) {
                aich = 0xFFFF;
            }
            publishers.add(new Publisher(ip, time, aich));
        }
        return new Tracking(hashes, nameList, publishers);
    }
}
