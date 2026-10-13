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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.protocol.tag.Tags;
import org.njmule.core.search.SearchExpr;

/**
 * What other nodes publish to us: files under keywords and sources of files, with global and
 * per-key limits and an expiry, answered to searches. Engine thread only.
 */
final class KadIndex {

    static final Duration KEYWORD_LIFETIME = Duration.ofHours(24);
    static final Duration SOURCE_LIFETIME = Duration.ofHours(5);

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

    private static final int MAX_PUBLISHERS = 100;

    /** A file published under a keyword. */
    private static final class KeywordEntry {
        private final Map<String, Integer> names = new LinkedHashMap<>();
        private final long size;
        private List<Tag> tags;
        private final Set<Integer> publishers = new HashSet<>();
        private long expires;

        KeywordEntry(long size) {
            this.size = size;
        }

        String popularName() {
            String best = "";
            int count = -1;
            for (Map.Entry<String, Integer> e : this.names.entrySet()) {
                if (e.getValue() > count) {
                    best = e.getKey();
                    count = e.getValue();
                }
            }
            return best;
        }
    }

    /** A source of a file. */
    private record SourceEntry(int ip, int tcpPort, int udpPort, List<Tag> tags, long expires) {}

    private final Map<UInt128, Map<UInt128, KeywordEntry>> keywords = new LinkedHashMap<>();
    private final Map<UInt128, Map<UInt128, SourceEntry>> sources = new LinkedHashMap<>();
    private int keywordEntries;
    private int sourceEntries;

    /** Stores a file published under {@code keyword} by {@code ip}; returns the keyword's load. */
    int publishKeyword(UInt128 keyword, KadMessages.Entry file, int ip, long now) {
        Map<UInt128, KeywordEntry> files = this.keywords.get(keyword);
        int count = files == null ? 0 : files.size();
        Optional<String> name = Tags.stringValue(file.tags(), FileTag.FILENAME);
        OptionalLong size = Tags.intValue(file.tags(), FileTag.FILESIZE);
        if (name.isEmpty() || name.get().isEmpty() || size.isEmpty() || size.getAsLong() <= 0) {
            return load(count, MAX_FILES_PER_KEYWORD);
        }
        KeywordEntry entry = files == null ? null : files.get(file.id());
        if (entry == null || entry.size != size.getAsLong()) {
            if (this.keywordEntries >= MAX_KEYWORD_ENTRIES || count >= MAX_FILES_PER_KEYWORD) {
                return 100;
            }
            entry = new KeywordEntry(size.getAsLong());
            this.keywords
                    .computeIfAbsent(keyword, k -> new LinkedHashMap<>())
                    .put(file.id(), entry);
            this.keywordEntries++;
            count++;
        }
        // A name published again gains popularity on the entry we keep.
        if (entry.publishers.add(ip) && entry.publishers.size() > MAX_PUBLISHERS) {
            entry.publishers.remove(ip);
        }
        entry.names.merge(name.get(), 1, Integer::sum);
        entry.tags = otherTags(file.tags());
        entry.expires = now + KEYWORD_LIFETIME.toNanos();
        return count == 1 ? 1 : load(count, MAX_FILES_PER_KEYWORD);
    }

    /** Stores a source of {@code file} at {@code ip}; returns its load, empty when refused. */
    OptionalLong publishSource(
            UInt128 file, UInt128 source, List<Tag> tags, int ip, int udpPort, long now) {
        if (Tags.intValue(tags, FileTag.SOURCETYPE).isEmpty()) {
            return OptionalLong.empty();
        }
        int tcp = (int) Tags.intValue(tags, FileTag.SOURCEPORT).orElse(0);
        int udp = (int) Tags.intValue(tags, FileTag.SOURCEUPORT).orElse(udpPort);
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
        Map<UInt128, SourceEntry> entries =
                this.sources.computeIfAbsent(file, f -> new LinkedHashMap<>());
        for (Iterator<Map.Entry<UInt128, SourceEntry>> it = entries.entrySet().iterator();
                it.hasNext(); ) {
            SourceEntry e = it.next().getValue();
            if (e.ip() == ip && (e.tcpPort() == tcp || e.udpPort() == udp)) {
                it.remove();
                this.sourceEntries--;
            }
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

    /**
     * Files under {@code keyword} matching {@code expression}, skipping the first {@code start}.
     */
    List<KadMessages.Entry> searchKeyword(
            UInt128 keyword, Optional<SearchExpr> expression, int start, long now) {
        Map<UInt128, KeywordEntry> files = this.keywords.get(keyword);
        List<KadMessages.Entry> results = new ArrayList<>();
        if (files == null) {
            return results;
        }
        int skipped = 0;
        for (Map.Entry<UInt128, KeywordEntry> e : files.entrySet()) {
            KeywordEntry entry = e.getValue();
            if (now - entry.expires >= 0) {
                continue;
            }
            String name = entry.popularName();
            if (expression.isPresent() && !matches(expression.get(), name, entry)) {
                continue;
            }
            if (skipped++ < start) {
                continue;
            }
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), name));
            tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), entry.size));
            tags.addAll(entry.tags);
            long info =
                    (long) Math.min(entry.names.size(), 255) << 24
                            | (long) Math.min(entry.publishers.size(), 255) << 16
                            | Math.min(entry.publishers.size() * 100L, 0xFFFF);
            tags.add(new Tag.IntTag(TagName.id(FileTag.PUBLISHINFO), info));
            results.add(new KadMessages.Entry(e.getKey(), tags));
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

    /** Drops what expired. */
    void clean(long now) {
        for (Iterator<Map<UInt128, KeywordEntry>> it = this.keywords.values().iterator();
                it.hasNext(); ) {
            Map<UInt128, KeywordEntry> files = it.next();
            int before = files.size();
            files.values().removeIf(e -> now - e.expires >= 0);
            this.keywordEntries -= before - files.size();
            if (files.isEmpty()) {
                it.remove();
            }
        }
        for (Iterator<Map<UInt128, SourceEntry>> it = this.sources.values().iterator();
                it.hasNext(); ) {
            Map<UInt128, SourceEntry> entries = it.next();
            int before = entries.size();
            entries.values().removeIf(e -> now - e.expires() >= 0);
            this.sourceEntries -= before - entries.size();
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

    private static int load(int count, int max) {
        return (int) Math.min(100, count * 100L / max);
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

    private static boolean matches(SearchExpr expr, String name, KeywordEntry entry) {
        return switch (expr) {
            case SearchExpr.And and ->
                    matches(and.left(), name, entry) && matches(and.right(), name, entry);
            case SearchExpr.Or or ->
                    matches(or.left(), name, entry) || matches(or.right(), name, entry);
            case SearchExpr.Not not ->
                    matches(not.left(), name, entry) && !matches(not.right(), name, entry);
            case SearchExpr.Keyword keyword -> containsWords(name, keyword.text());
            case SearchExpr.StringAttr attr -> matchesString(attr, name, entry);
            case SearchExpr.NumericAttr attr -> matchesNumber(attr, entry);
        };
    }

    private static boolean containsWords(String name, String words) {
        String haystack = KadLowerCase.toLowerCase(name);
        for (String part :
                KadLowerCase.toLowerCase(words).split("[\" ()\\[\\]{}<>,._\\-!?:;\\\\/]+")) {
            if (!part.isEmpty() && !haystack.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesString(
            SearchExpr.StringAttr attr, String name, KeywordEntry entry) {
        if (attr.tag() == FileTag.FILEFORMAT) {
            int dot = name.lastIndexOf('.');
            return dot >= 0 && name.substring(dot + 1).equalsIgnoreCase(attr.value());
        }
        return Tags.stringValue(entry.tags, attr.tag())
                .map(v -> v.toLowerCase(Locale.ROOT).equals(attr.value().toLowerCase(Locale.ROOT)))
                .orElse(false);
    }

    private static boolean matchesNumber(SearchExpr.NumericAttr attr, KeywordEntry entry) {
        long value =
                attr.tag() == FileTag.FILESIZE
                        ? entry.size
                        : Tags.intValue(entry.tags, attr.tag()).orElse(Long.MIN_VALUE);
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
