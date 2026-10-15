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
package org.njmule.core.kad;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import org.njmule.core.api.FileComment;
import org.njmule.core.persist.format.KadIndexFiles;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;

/**
 * What other nodes publish to us: files under keywords, sources of files and notes (comments and ratings) on files,
 * with global and per-key limits and an expiry, answered to searches. Engine thread only.
 */
final class KadIndex {

    static final Duration KEYWORD_LIFETIME = Duration.ofHours(24);
    static final Duration SOURCE_LIFETIME = Duration.ofHours(5);

    // Publishers renew their notes once a day, so a note nobody renews goes after a day.
    static final Duration NOTE_LIFETIME = Duration.ofHours(24);

    /** Notes per file at most, and per answer. */
    static final int MAX_NOTES_PER_FILE = 150;

    /** Notes of all files at most. */
    static final int MAX_NOTES = 50_000;

    // What a note keeps of the file name the publisher gave, so that a note stays small.
    static final int MAX_NOTE_NAME = 255;

    // What a keyword entry keeps of a name: no file system takes a longer one.
    static final int MAX_KEYWORD_NAME = 255;

    /** Keyword entries in total at most. */
    static final int MAX_KEYWORD_ENTRIES = 60_000;

    /** Files per keyword at most. */
    static final int MAX_FILES_PER_KEYWORD = 50_000;

    /** Sources per file at most. */
    static final int MAX_SOURCES_PER_FILE = 1_000;

    /** Files of all keywords and sources of all files at most. */
    static final int MAX_SOURCES = 250_000;

    /** Results per answer at most. */
    static final int MAX_RESULTS = 300;

    /** Bytes of a search answer at most: one datagram that no link has to fragment. */
    static final int ANSWER_BYTES = 1_420;

    private static final int MAX_PUBLISHERS = 100;

    // Our keywords whose nodes said they are loaded, kept at most.
    private static final int MAX_LOADS = 100_000;

    // The largest information an answer carries about the publishers of a file.
    private static final long MAX_PUBLISH_INFO = 0xFFFF_FFFFL;

    /** A publisher of a file under a keyword: the name it gave last, and when. */
    private record Publisher(String name, long at) {}

    /** A file published under a keyword. */
    private static final class KeywordEntry {
        // How many publishers give each name: no more names than publishers are kept.
        private final Map<String, Integer> names = new LinkedHashMap<>();
        private final long size;
        private List<Tag> tags;
        // By address, the one that published longest ago first.
        private final Map<Integer, Publisher> publishers = new LinkedHashMap<>();
        private long expires;
        // The most popular name and its lower case, worked out again only once the names changed.
        private String popular;
        private String popularLower;

        KeywordEntry(long size) {
            this.size = size;
        }

        // A publisher counts once, for the name it gave last, so repeating or switching names gains nothing.
        void published(int ip, String name, long now) {
            forget(now);
            Publisher before = this.publishers.remove(ip);
            if (before == null && this.publishers.size() >= MAX_PUBLISHERS) {
                return;
            }
            this.publishers.put(ip, new Publisher(name, now));
            if (before == null || !before.name().equals(name)) {
                if (before != null) {
                    drop(before.name());
                }
                this.names.merge(name, 1, Integer::sum);
                this.popular = null;
            }
        }

        // A publisher silent for as long as entries live counts no more and leaves its place to another.
        void forget(long now) {
            for (Iterator<Publisher> it = this.publishers.values().iterator(); it.hasNext(); ) {
                Publisher p = it.next();
                if (now - p.at() < KEYWORD_LIFETIME.toNanos()) {
                    return;
                }
                it.remove();
                drop(p.name());
            }
        }

        private void drop(String name) {
            this.names.computeIfPresent(name, (n, count) -> count > 1 ? count - 1 : null);
            this.popular = null;
        }

        String popularName() {
            if (this.popular == null) {
                String best = "";
                int count = -1;
                for (Map.Entry<String, Integer> e : this.names.entrySet()) {
                    if (e.getValue() > count) {
                        best = e.getKey();
                        count = e.getValue();
                    }
                }
                this.popular = best;
                this.popularLower = KadLowerCase.toLowerCase(best);
            }
            return this.popular;
        }

        String popularNameLower() {
            popularName();
            return this.popularLower;
        }
    }

    /** A source of a file. */
    private record SourceEntry(int ip, int tcpPort, int udpPort, List<Tag> tags, long expires) {}

    /** A note on a file, as an answer carries it. */
    private record NoteEntry(int ip, long size, List<Tag> tags, long expires) {}

    private final Map<UInt128, Map<UInt128, KeywordEntry>> keywords = new LinkedHashMap<>();
    private final Map<UInt128, Map<UInt128, SourceEntry>> sources = new LinkedHashMap<>();
    // By file, then by the publisher's id, oldest first.
    private final Map<UInt128, Map<UInt128, NoteEntry>> notes = new LinkedHashMap<>();
    // Our keywords not to publish until the time given, because their nodes are loaded.
    private final Map<UInt128, Long> loaded = new LinkedHashMap<>();
    private int keywordEntries;
    private int sourceEntries;
    private int noteEntries;

    /** Stores a file published under {@code keyword} by {@code ip}; returns the keyword's load. */
    int publishKeyword(UInt128 keyword, KadMessages.Entry file, int ip, long now) {
        Map<UInt128, KeywordEntry> files = this.keywords.get(keyword);
        int count = files == null ? 0 : files.size();
        Optional<String> name = Tags.stringValue(file.tags(), FileTag.FILENAME).map(n -> cut(n, MAX_KEYWORD_NAME));
        OptionalLong size = Tags.intValue(file.tags(), FileTag.FILESIZE);
        if (name.isEmpty() || name.get().isEmpty() || size.isEmpty() || size.getAsLong() <= 0) {
            return load(count, MAX_FILES_PER_KEYWORD);
        }
        List<Tag> other = otherTags(file.tags());
        // What no answer could carry is never asked for.
        if (!KadMessages.SearchAnswer.fits(
                answerEntry(file.id(), name.get(), size.getAsLong(), other, MAX_PUBLISH_INFO), ANSWER_BYTES)) {
            return load(count, MAX_FILES_PER_KEYWORD);
        }
        KeywordEntry entry = files == null ? null : files.get(file.id());
        if (entry == null || entry.size != size.getAsLong()) {
            if (this.keywordEntries >= MAX_KEYWORD_ENTRIES || count >= MAX_FILES_PER_KEYWORD) {
                return 100;
            }
            boolean replacing = entry != null;
            entry = new KeywordEntry(size.getAsLong());
            this.keywords.computeIfAbsent(keyword, k -> new LinkedHashMap<>()).put(file.id(), entry);
            if (!replacing) {
                this.keywordEntries++;
                count++;
            }
        }
        entry.published(ip, name.get(), now);
        entry.tags = other;
        entry.expires = now + KEYWORD_LIFETIME.toNanos();
        return count == 1 ? 1 : load(count, MAX_FILES_PER_KEYWORD);
    }

    /** Stores a source of {@code file} at {@code ip}; returns its load, empty when refused. */
    OptionalLong publishSource(UInt128 file, UInt128 source, List<Tag> tags, int ip, int udpPort, long now) {
        if (Tags.intValue(tags, FileTag.SOURCETYPE).isEmpty()) {
            return OptionalLong.empty();
        }
        int tcp = Tags.portValue(tags, FileTag.SOURCEPORT).orElse(0);
        int udp = Tags.intValue(tags, FileTag.SOURCEUPORT).isPresent()
                ? Tags.portValue(tags, FileTag.SOURCEUPORT).orElse(0)
                : udpPort;
        if (ip == 0 || tcp == 0 || udp == 0) {
            return OptionalLong.empty();
        }
        // The address is the one the packet came from, never one the publisher names.
        List<Tag> stored = new ArrayList<>();
        stored.add(new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(ip)));
        boolean hasUdp = false;
        for (Tag tag : tags) {
            int id = tag.name().isId() ? tag.name().id() : -1;
            if (id == FileTag.SOURCEIP) {
                continue;
            }
            hasUdp |= id == FileTag.SOURCEUPORT;
            stored.add(tag);
        }
        if (!hasUdp) {
            stored.add(new Tag.IntTag(TagName.id(FileTag.SOURCEUPORT), udp));
        }
        Map<UInt128, SourceEntry> entries = this.sources.computeIfAbsent(file, f -> new LinkedHashMap<>());
        for (Iterator<Map.Entry<UInt128, SourceEntry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            SourceEntry e = it.next().getValue();
            if (e.ip() == ip && (e.tcpPort() == tcp || e.udpPort() == udp)) {
                it.remove();
                this.sourceEntries--;
            }
        }
        // The same source from another address replaces itself.
        if (entries.remove(source) != null) {
            this.sourceEntries--;
        }
        if (entries.size() >= MAX_SOURCES_PER_FILE || this.sourceEntries >= MAX_SOURCES) {
            if (entries.isEmpty()) {
                return OptionalLong.empty();
            }
            UInt128 oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
            this.sourceEntries--;
        }
        entries.put(source, new SourceEntry(ip, tcp, udp, stored, now + SOURCE_LIFETIME.toNanos()));
        this.sourceEntries++;
        return OptionalLong.of(load(entries.size(), MAX_SOURCES_PER_FILE));
    }

    /** Files under {@code keyword} matching {@code expression}, skipping the first {@code start}. */
    List<KadMessages.Entry> searchKeyword(UInt128 keyword, Optional<SearchExpr> expression, int start, long now) {
        Map<UInt128, KeywordEntry> files = this.keywords.get(keyword);
        List<KadMessages.Entry> results = new ArrayList<>();
        if (files == null) {
            return results;
        }
        Optional<Matcher> matcher = expression.map(Matcher::new);
        int skipped = 0;
        for (Map.Entry<UInt128, KeywordEntry> e : files.entrySet()) {
            KeywordEntry entry = e.getValue();
            if (now - entry.expires >= 0) {
                continue;
            }
            // Refreshed only by publishers that found no place, an entry may have no name left.
            entry.forget(now);
            String name = entry.popularName();
            if (name.isEmpty()
                    || (matcher.isPresent()
                            && !matcher.get().matches(name, entry.popularNameLower(), entry.size, entry.tags))) {
                continue;
            }
            if (skipped++ < start) {
                continue;
            }
            long info = (long) Math.min(entry.names.size(), 255) << 24
                    | (long) Math.min(entry.publishers.size(), 255) << 16
                    | Math.min(entry.publishers.size() * 100L, 0xFFFF);
            results.add(answerEntry(e.getKey(), name, entry.size, entry.tags, info));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    /** Sources of {@code file} for a file of {@code size} bytes (0: any). */
    List<KadMessages.Entry> searchSources(UInt128 file, long size, int start, long now) {
        Map<UInt128, SourceEntry> entries = this.sources.get(file);
        List<KadMessages.Entry> results = new ArrayList<>();
        if (entries == null) {
            return results;
        }
        int skipped = 0;
        for (Map.Entry<UInt128, SourceEntry> e : entries.entrySet()) {
            SourceEntry s = e.getValue();
            if (now - s.expires() >= 0) {
                continue;
            }
            long entrySize = Tags.intValue(s.tags(), FileTag.FILESIZE).orElse(0);
            if (size != 0 && entrySize != 0 && entrySize != size) {
                continue;
            }
            if (skipped++ < start) {
                continue;
            }
            results.add(new KadMessages.Entry(e.getKey(), s.tags()));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        return results;
    }

    /**
     * Stores the note of the node {@code publisher} on {@code file}, sent from {@code ip}; returns the file's load,
     * empty when refused. A publisher, and an address, hold one note on a file: a new one replaces it.
     */
    OptionalLong publishNote(UInt128 file, UInt128 publisher, List<Tag> tags, int ip, long now) {
        FileComment comment = noteComment(tags);
        if (ip == 0 || comment.isEmpty()) {
            return OptionalLong.empty();
        }
        // Only what a note says is kept, no longer than clients show it.
        String name = cut(Tags.stringValue(tags, FileTag.FILENAME).orElse(""), MAX_NOTE_NAME);
        long size = Math.max(0, Tags.intValue(tags, FileTag.FILESIZE).orElse(0));
        List<Tag> stored = new ArrayList<>();
        if (!name.isEmpty()) {
            stored.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
        }
        if (size > 0) {
            stored.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size));
        }
        if (comment.rating() > 0) {
            stored.add(new Tag.IntTag(TagName.id(FileTag.FILERATING), comment.rating()));
        }
        if (!comment.text().isEmpty()) {
            stored.add(new Tag.StringTag(TagName.id(FileTag.DESCRIPTION), comment.text()));
        }
        Map<UInt128, NoteEntry> entries = this.notes.computeIfAbsent(file, f -> new LinkedHashMap<>());
        int before = entries.size();
        entries.values().removeIf(e -> e.ip() == ip);
        entries.remove(publisher);
        this.noteEntries -= before - entries.size();
        if (entries.size() >= MAX_NOTES_PER_FILE || this.noteEntries >= MAX_NOTES) {
            if (entries.isEmpty()) {
                this.notes.remove(file);
                return OptionalLong.empty();
            }
            entries.remove(entries.keySet().iterator().next());
            this.noteEntries--;
        }
        entries.put(publisher, new NoteEntry(ip, size, List.copyOf(stored), now + NOTE_LIFETIME.toNanos()));
        this.noteEntries++;
        return OptionalLong.of(load(entries.size(), MAX_NOTES_PER_FILE));
    }

    /** The notes on {@code file} for a file of {@code size} bytes (0: any). */
    List<KadMessages.Entry> searchNotes(UInt128 file, long size, long now) {
        Map<UInt128, NoteEntry> entries = this.notes.get(file);
        List<KadMessages.Entry> results = new ArrayList<>();
        if (entries == null) {
            return results;
        }
        for (Map.Entry<UInt128, NoteEntry> e : entries.entrySet()) {
            NoteEntry note = e.getValue();
            if (now - note.expires() >= 0 || (size != 0 && note.size() != 0 && note.size() != size)) {
                continue;
            }
            results.add(new KadMessages.Entry(e.getKey(), note.tags()));
        }
        return results;
    }

    /** The nodes that store our keyword {@code key} are loaded: it is not published before {@code until}. */
    void loaded(UInt128 key, long until) {
        if (this.loaded.size() >= MAX_LOADS && !this.loaded.containsKey(key)) {
            this.loaded.remove(this.loaded.keySet().iterator().next());
        }
        this.loaded.put(key, until);
    }

    /** Whether our keyword {@code key} may be published now. */
    boolean mayStore(UInt128 key, long now) {
        Long until = this.loaded.get(key);
        if (until == null) {
            return true;
        }
        if (now - until < 0) {
            return false;
        }
        this.loaded.remove(key);
        return true;
    }

    /**
     * The keyword entries alive at {@code now} for the node {@code ownId}, as a file; times in epoch seconds,
     * {@code wall} being now. The publishers of each name follow one another, in the order of the names.
     */
    KadIndexFiles.KeywordIndex keywordFile(UInt128 ownId, long wall, long now) {
        List<KadIndexFiles.Keyword> keys = new ArrayList<>();
        for (Map.Entry<UInt128, Map<UInt128, KeywordEntry>> k : this.keywords.entrySet()) {
            List<KadIndexFiles.KeywordSource> files = new ArrayList<>();
            for (Map.Entry<UInt128, KeywordEntry> f : k.getValue().entrySet()) {
                KeywordEntry entry = f.getValue();
                entry.forget(now);
                if (now - entry.expires >= 0 || entry.publishers.isEmpty()) {
                    continue;
                }
                Map<String, List<KadIndexFiles.Publisher>> byName = new HashMap<>();
                for (Map.Entry<Integer, Publisher> p : entry.publishers.entrySet()) {
                    byName.computeIfAbsent(p.getValue().name(), n -> new ArrayList<>())
                            .add(new KadIndexFiles.Publisher(
                                    p.getKey(), wallOf(p.getValue().at(), wall, now), 0xFFFF));
                }
                List<KadIndexFiles.PublishedName> names = new ArrayList<>();
                List<KadIndexFiles.Publisher> publishers = new ArrayList<>();
                for (Map.Entry<String, Integer> n : entry.names.entrySet()) {
                    names.add(new KadIndexFiles.PublishedName(n.getKey(), n.getValue()));
                    publishers.addAll(byName.getOrDefault(n.getKey(), List.of()));
                }
                List<Tag> tags = new ArrayList<>();
                tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), entry.popularName()));
                tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), entry.size));
                tags.addAll(entry.tags);
                files.add(new KadIndexFiles.KeywordSource(
                        f.getKey(),
                        List.of(new KadIndexFiles.KeywordEntry(
                                wallOf(entry.expires, wall, now),
                                new KadIndexFiles.Tracking(List.of(), names, publishers),
                                tags))));
            }
            if (!files.isEmpty()) {
                keys.add(new KadIndexFiles.Keyword(k.getKey(), files));
            }
        }
        return new KadIndexFiles.KeywordIndex(4, wall + KEYWORD_LIFETIME.toSeconds(), ownId, keys);
    }

    /**
     * Takes back the keyword entries of a file written for the node {@code ownId}, each publisher as if it published
     * again when it last did: the same limits apply as to a live publish. Publishers {@code refused} are left out.
     */
    void restoreKeywords(KadIndexFiles.KeywordIndex file, UInt128 ownId, IntPredicate refused, long wall, long now) {
        if (file.expiry() <= wall || !file.ownId().equals(ownId)) {
            return;
        }
        for (KadIndexFiles.Keyword k : file.keywords()) {
            for (KadIndexFiles.KeywordSource f : k.sources()) {
                for (KadIndexFiles.KeywordEntry e : f.entries()) {
                    if (e.lifetime() > wall) {
                        restoreKeyword(k.key(), f.id(), e, refused, wall, now);
                    }
                }
            }
        }
    }

    private void restoreKeyword(
            UInt128 keyword, UInt128 id, KadIndexFiles.KeywordEntry e, IntPredicate refused, long wall, long now) {
        Optional<String> popular = Tags.stringValue(e.tags(), FileTag.FILENAME);
        OptionalLong size = Tags.intValue(e.tags(), FileTag.FILESIZE);
        if (popular.isEmpty() || size.isEmpty()) {
            return;
        }
        List<Tag> other = otherTags(e.tags());
        // Each name goes to as many of the publishers that follow as it counts.
        List<KadIndexFiles.Publisher> publishers = e.tracking().publishers();
        List<String> names = new ArrayList<>();
        for (KadIndexFiles.PublishedName n : e.tracking().names()) {
            for (long i = 0; i < n.popularity() && names.size() < publishers.size(); i++) {
                names.add(n.name());
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < publishers.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) ->
                Long.compare(publishers.get(a).time(), publishers.get(b).time()));
        for (int i : order) {
            KadIndexFiles.Publisher p = publishers.get(i);
            long at = Math.min(p.time(), wall);
            if (refused.test(p.ip()) || at + KEYWORD_LIFETIME.toSeconds() <= wall) {
                continue;
            }
            List<Tag> tags = new ArrayList<>(other.size() + 2);
            tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), i < names.size() ? names.get(i) : popular.get()));
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size.getAsLong()));
            tags.addAll(other);
            publishKeyword(keyword, new KadMessages.Entry(id, tags), p.ip(), tickerOf(at, wall, now));
        }
    }

    /** The sources alive at {@code now}, as a file; times in epoch seconds, {@code wall} being now. */
    KadIndexFiles.SourceIndex sourceFile(long wall, long now) {
        List<KadIndexFiles.IndexKey> keys = new ArrayList<>();
        for (Map.Entry<UInt128, Map<UInt128, SourceEntry>> k : this.sources.entrySet()) {
            List<KadIndexFiles.Source> list = new ArrayList<>();
            for (Map.Entry<UInt128, SourceEntry> s : k.getValue().entrySet()) {
                if (now - s.getValue().expires() < 0) {
                    list.add(new KadIndexFiles.Source(
                            s.getKey(),
                            List.of(new KadIndexFiles.Entry(
                                    wallOf(s.getValue().expires(), wall, now),
                                    s.getValue().tags()))));
                }
            }
            if (!list.isEmpty()) {
                keys.add(new KadIndexFiles.IndexKey(k.getKey(), list));
            }
        }
        return new KadIndexFiles.SourceIndex(2, wall + SOURCE_LIFETIME.toSeconds(), keys);
    }

    /** Takes back the sources of a file whose key is {@code near} us, as live publishes from their address. */
    void restoreSources(
            KadIndexFiles.SourceIndex file, Predicate<UInt128> near, IntPredicate refused, long wall, long now) {
        if (file.expiry() <= wall) {
            return;
        }
        for (KadIndexFiles.IndexKey k : file.keys()) {
            if (!near.test(k.key())) {
                continue;
            }
            for (KadIndexFiles.Source s : k.sources()) {
                for (KadIndexFiles.Entry e : s.entries()) {
                    int ip = (int) Tags.intValue(e.tags(), FileTag.SOURCEIP).orElse(0);
                    int udp = Tags.portValue(e.tags(), FileTag.SOURCEUPORT).orElse(0);
                    long lifetime = Math.min(e.lifetime(), wall + SOURCE_LIFETIME.toSeconds());
                    if (lifetime > wall && ip != 0 && !refused.test(ip)) {
                        publishSource(
                                k.key(),
                                s.id(),
                                e.tags(),
                                ip,
                                udp,
                                tickerOf(lifetime, wall, now) - SOURCE_LIFETIME.toNanos());
                    }
                }
            }
        }
    }

    /** The notes alive at {@code now}, as a file of the sources' layout: each note with its publisher's address. */
    KadIndexFiles.SourceIndex noteFile(long wall, long now) {
        List<KadIndexFiles.IndexKey> keys = new ArrayList<>();
        for (Map.Entry<UInt128, Map<UInt128, NoteEntry>> k : this.notes.entrySet()) {
            List<KadIndexFiles.Source> list = new ArrayList<>();
            for (Map.Entry<UInt128, NoteEntry> n : k.getValue().entrySet()) {
                NoteEntry note = n.getValue();
                if (now - note.expires() < 0) {
                    List<Tag> tags = new ArrayList<>(note.tags());
                    tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCEIP), Integer.toUnsignedLong(note.ip())));
                    list.add(new KadIndexFiles.Source(
                            n.getKey(), List.of(new KadIndexFiles.Entry(wallOf(note.expires(), wall, now), tags))));
                }
            }
            if (!list.isEmpty()) {
                keys.add(new KadIndexFiles.IndexKey(k.getKey(), list));
            }
        }
        return new KadIndexFiles.SourceIndex(2, wall + NOTE_LIFETIME.toSeconds(), keys);
    }

    /** Takes back the notes of a file whose key is {@code near} us, as live publishes from their address. */
    void restoreNotes(
            KadIndexFiles.SourceIndex file, Predicate<UInt128> near, IntPredicate refused, long wall, long now) {
        if (file.expiry() <= wall) {
            return;
        }
        for (KadIndexFiles.IndexKey k : file.keys()) {
            if (!near.test(k.key())) {
                continue;
            }
            for (KadIndexFiles.Source s : k.sources()) {
                for (KadIndexFiles.Entry e : s.entries()) {
                    int ip = (int) Tags.intValue(e.tags(), FileTag.SOURCEIP).orElse(0);
                    long lifetime = Math.min(e.lifetime(), wall + NOTE_LIFETIME.toSeconds());
                    if (lifetime > wall && ip != 0 && !refused.test(ip)) {
                        publishNote(
                                k.key(), s.id(), e.tags(), ip, tickerOf(lifetime, wall, now) - NOTE_LIFETIME.toNanos());
                    }
                }
            }
        }
    }

    /** Our keywords whose nodes are loaded, until when, as a file. */
    KadIndexFiles.LoadIndex loadFile(long wall, long now) {
        List<KadIndexFiles.Load> loads = new ArrayList<>();
        for (Map.Entry<UInt128, Long> l : this.loaded.entrySet()) {
            if (now - l.getValue() < 0) {
                loads.add(new KadIndexFiles.Load(l.getKey(), wallOf(l.getValue(), wall, now)));
            }
        }
        return new KadIndexFiles.LoadIndex(wall, loads);
    }

    /** Takes back our keywords whose nodes are loaded, for no longer than the most a load spares them. */
    void restoreLoads(KadIndexFiles.LoadIndex file, Duration longest, long wall, long now) {
        for (KadIndexFiles.Load l : file.loads()) {
            long until = Math.min(l.time(), wall + longest.toSeconds());
            if (until > wall) {
                loaded(l.key(), tickerOf(until, wall, now));
            }
        }
    }

    private static long wallOf(long ticker, long wall, long now) {
        return wall + Math.floorDiv(ticker - now, 1_000_000_000L);
    }

    private static long tickerOf(long seconds, long wall, long now) {
        return now - (wall - seconds) * 1_000_000_000L;
    }

    /** Drops what expired, and the publishers silent for as long. */
    void clean(long now) {
        this.loaded.values().removeIf(until -> now - until >= 0);
        for (Iterator<Map<UInt128, KeywordEntry>> it = this.keywords.values().iterator(); it.hasNext(); ) {
            Map<UInt128, KeywordEntry> files = it.next();
            int before = files.size();
            files.values().removeIf(e -> now - e.expires >= 0);
            files.values().forEach(e -> e.forget(now));
            this.keywordEntries -= before - files.size();
            if (files.isEmpty()) {
                it.remove();
            }
        }
        for (Iterator<Map<UInt128, SourceEntry>> it = this.sources.values().iterator(); it.hasNext(); ) {
            Map<UInt128, SourceEntry> entries = it.next();
            int before = entries.size();
            entries.values().removeIf(e -> now - e.expires() >= 0);
            this.sourceEntries -= before - entries.size();
            if (entries.isEmpty()) {
                it.remove();
            }
        }
        for (Iterator<Map<UInt128, NoteEntry>> it = this.notes.values().iterator(); it.hasNext(); ) {
            Map<UInt128, NoteEntry> entries = it.next();
            int before = entries.size();
            entries.values().removeIf(e -> now - e.expires() >= 0);
            this.noteEntries -= before - entries.size();
            if (entries.isEmpty()) {
                it.remove();
            }
        }
    }

    int keywordEntries() {
        return this.keywordEntries;
    }

    int sourceEntries() {
        return this.sourceEntries;
    }

    int noteEntries() {
        return this.noteEntries;
    }

    private static int load(int count, int max) {
        return (int) Math.min(100, count * 100L / max);
    }

    /** The comment and rating a note carries, made fit; a rating out of range counts as none. */
    static FileComment noteComment(List<Tag> tags) {
        long rating = Tags.intValue(tags, FileTag.FILERATING).orElse(0);
        return FileComment.of(
                Tags.stringValue(tags, FileTag.DESCRIPTION).orElse(""),
                rating > 0 && rating <= FileComment.MAX_RATING ? (int) rating : 0);
    }

    // Never between the two halves of a surrogate pair.
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    private static KadMessages.Entry answerEntry(UInt128 file, String name, long size, List<Tag> other, long info) {
        List<Tag> tags = new ArrayList<>(other.size() + 3);
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), size));
        tags.addAll(other);
        tags.add(new Tag.IntTag(TagName.id(FileTag.PUBLISHINFO), info));
        return new KadMessages.Entry(file, tags);
    }

    // Name and size are kept as fields, the rest as they came, except what only answers carry.
    private static List<Tag> otherTags(List<Tag> tags) {
        List<Tag> kept = new ArrayList<>();
        for (Tag tag : tags) {
            int id = tag.name().isId() ? tag.name().id() : -1;
            if (id != FileTag.FILENAME && id != FileTag.FILESIZE && id != FileTag.PUBLISHINFO) {
                kept.add(tag);
            }
        }
        return kept;
    }

    /** An expression whose words are split and put in lower case once, for all the files it is matched against. */
    static final class Matcher {
        private final SearchExpr expression;
        private final Map<String, List<String>> words = new HashMap<>();

        Matcher(SearchExpr expression) {
            this.expression = expression;
            split(expression);
        }

        /** Whether the file {@code name} ({@code lower} in the network's lower case) matches. */
        boolean matches(String name, String lower, long size, List<Tag> tags) {
            return matches(this.expression, name, lower, size, tags);
        }

        private boolean matches(SearchExpr expr, String name, String lower, long size, List<Tag> tags) {
            return switch (expr) {
                case SearchExpr.And and ->
                    matches(and.left(), name, lower, size, tags) && matches(and.right(), name, lower, size, tags);
                case SearchExpr.Or or ->
                    matches(or.left(), name, lower, size, tags) || matches(or.right(), name, lower, size, tags);
                case SearchExpr.Not not ->
                    matches(not.left(), name, lower, size, tags) && !matches(not.right(), name, lower, size, tags);
                case SearchExpr.Keyword keyword -> containsAll(lower, this.words.get(keyword.text()));
                case SearchExpr.StringAttr attr -> matchesString(attr, name, tags);
                case SearchExpr.NumericAttr attr -> matchesNumber(attr, size, tags);
            };
        }

        private void split(SearchExpr expr) {
            switch (expr) {
                case SearchExpr.And and -> {
                    split(and.left());
                    split(and.right());
                }
                case SearchExpr.Or or -> {
                    split(or.left());
                    split(or.right());
                }
                case SearchExpr.Not not -> {
                    split(not.left());
                    split(not.right());
                }
                case SearchExpr.Keyword keyword -> this.words.computeIfAbsent(keyword.text(), KadIndex::words);
                case SearchExpr.StringAttr attr -> {
                    // Matched against the tags as they are.
                }
                case SearchExpr.NumericAttr attr -> {
                    // Matched against the size or the tags.
                }
            }
        }
    }

    private static List<String> words(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : KadLowerCase.toLowerCase(text).split("[\" ()\\[\\]{}<>,._\\-!?:;\\\\/]+", -1)) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    // A term made only of separators names no word: it matches nothing.
    private static boolean containsAll(String lower, List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        for (String word : words) {
            if (!lower.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesString(SearchExpr.StringAttr attr, String name, List<Tag> tags) {
        if (attr.tag() == FileTag.FILEFORMAT) {
            int dot = name.lastIndexOf('.');
            return dot >= 0 && name.substring(dot + 1).equalsIgnoreCase(attr.value());
        }
        return Tags.stringValue(tags, attr.tag())
                .map(v -> v.toLowerCase(Locale.ROOT).equals(attr.value().toLowerCase(Locale.ROOT)))
                .orElse(false);
    }

    private static boolean matchesNumber(SearchExpr.NumericAttr attr, long size, List<Tag> tags) {
        long value = attr.tag() == FileTag.FILESIZE
                ? size
                : Tags.intValue(tags, attr.tag()).orElse(Long.MIN_VALUE);
        if (value == Long.MIN_VALUE) {
            return false;
        }
        long wanted = attr.value();
        return switch (attr.comparison()) {
            case EQUAL -> value == wanted;
            case GREATER -> value > wanted;
            case LESS -> value < wanted;
            case GREATER_EQUAL -> value >= wanted;
            case LESS_EQUAL -> value <= wanted;
            case NOT_EQUAL -> value != wanted;
        };
    }
}
