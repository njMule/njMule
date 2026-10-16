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
package org.njmule.core.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedCollection;
import java.util.SequencedSet;
import java.util.Set;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.files.FileTypes;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.njmule.core.util.Bytes;
import org.njmule.core.util.Ipv4;
import org.njmule.core.util.LittleEndian;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rates search results for spam, from what the files marked as spam had (hash, name, a similar name, a similar size,
 * the servers and sources that gave them) and how many spam results each server gives over UDP; kept in
 * {@code SearchSpam.met}. The ratings add up: {@value SearchResult#SPAM_THRESHOLD} and more is spam. Engine thread
 * only.
 */
public final class SpamFilter {

    public static final String FILE_NAME = "SearchSpam.met";

    // The ratings each hit adds.
    static final int HASH_HIT = 100;
    static final int FULL_NAME_HIT = 80;
    static final int SHORT_FULL_NAME_HIT = 50;
    static final int BARE_NAME_HIT = 60;
    static final int SHORT_BARE_NAME_HIT = 40;
    static final int NEAR_NAME_HIT = 50;
    static final int FAR_NAME_HIT = 40;
    static final int SIZE_HIT = 10;
    static final int ONLY_SERVER_HIT = 21;
    static final int ONLY_SERVER_OF_MANY_HIT = 15;
    static final int ONE_SERVER_OF_SEVERAL_HIT = 10;
    static final int ONLY_SPAM_SERVERS_HIT = 30;
    static final int SOURCE_HIT = 39;
    static final int POPULAR_HIT = 39;
    static final int POPULAR_SMALL_PROGRAM_HIT = 60;

    /** Names this long or shorter are common: a match counts for less. */
    static final int SHORT_NAME = SpamMarks.MAX_COMMON_NAME;

    // A server whose UDP answers are this share of spam or more is a spam server.
    private static final int SPAM_SERVER_PERCENT = 50;
    // Up to this many spam servers known, a file only one of them gives rates higher.
    private static final int FEW_SPAM_SERVERS = 10;
    // Similar sizes: within 5 % and 5 MiB.
    private static final long SIZE_SPREAD = 5L << 20;
    private static final int SIZE_PERCENT = 5;
    // A popular result: more sources than this from spam servers only.
    private static final int POPULAR = 100;
    // Programs and archives of this size, small and popular, are the usual fakes.
    private static final long SMALL_PROGRAM_MIN = 100 * 1024;
    private static final long SMALL_PROGRAM_MAX = 10L << 20;
    // Markers kept of each kind: the oldest go first.
    private static final int MAX_MARKERS = 4096;
    private static final int UDP_RECORD_BYTES = 12;

    private static final Logger logger = LoggerFactory.getLogger(SpamFilter.class);
    private static final DateTimeFormatter CORRUPT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    /** How many results a server gave over UDP, and how many of them were spam. */
    private record ServerRecord(long results, long spam) {
        boolean isSpamServer() {
            return this.results > 0 && this.spam * 100 >= this.results * SPAM_SERVER_PERCENT;
        }
    }

    private final SequencedSet<Hash16> spam = new LinkedHashSet<>();
    private final SequencedSet<Hash16> notSpam = new LinkedHashSet<>();
    // By lower case name, the name as written.
    private final Map<String, String> names = new LinkedHashMap<>();
    private final SequencedSet<String> bareNames = new LinkedHashSet<>();
    private final SequencedSet<Long> sizes = new LinkedHashSet<>();
    private final SequencedSet<Integer> sourceIps = new LinkedHashSet<>();
    private final SequencedSet<Integer> serverIps = new LinkedHashSet<>();
    private final Map<Integer, ServerRecord> servers = new LinkedHashMap<>();
    private final List<Tag> others = new ArrayList<>();

    /** A filter without markers. */
    public SpamFilter() {
        // Markers come from load and mark.
    }

    /**
     * Reads {@code file}, or its backup; a missing file gives no markers. When neither can be decoded, they are set
     * aside as {@code <name>.corrupt-<time>}, so that saving does not overwrite them, and there are no markers.
     */
    public static SpamFilter load(Path file) throws IOException {
        SpamFilter filter = new SpamFilter();
        Path bak = AtomicFiles.bakPath(file.toAbsolutePath());
        if (!Files.exists(file) && !Files.exists(bak)) {
            return filter;
        }
        List<Tag> tags;
        try {
            tags = AtomicFiles.read(file, in -> SearchFiles.readSpam(in.readAllBytes()));
        } catch (DecodeException e) {
            logger.warn("{} is damaged: set aside, no spam markers", file, e);
            String suffix = ".corrupt-" + CORRUPT_TIME.format(Instant.now());
            setAside(file, suffix);
            setAside(bak, suffix);
            return filter;
        }
        for (Tag tag : tags) {
            filter.take(tag);
        }
        return filter;
    }

    private static void setAside(Path file, String suffix) {
        try {
            if (Files.exists(file)) {
                Files.move(file, file.resolveSibling(file.getFileName() + suffix));
            }
        } catch (IOException e) {
            logger.warn("Cannot set {} aside", file, e);
        }
    }

    // IPs travel as the eD2K form of the address.
    private void take(Tag tag) {
        if (!writable(tag)) {
            logger.debug("Dropping the spam marker {}, which the file cannot hold", tag.name());
            return;
        }
        int id = tag.name().isId() ? tag.name().id() : -1;
        switch (tag) {
            case Tag.HashTag h when id == SearchFiles.SPAM_HASH -> this.spam.add(h.value());
            case Tag.HashTag h when id == SearchFiles.NOT_SPAM_HASH -> this.notSpam.add(h.value());
            case Tag.StringTag s
            when id == SearchFiles.SPAM_FULL_NAME -> this.names.put(s.value().toLowerCase(Locale.ROOT), s.value());
            case Tag.StringTag s
            when id == SearchFiles.SPAM_SIMILAR_NAME && !s.value().isBlank() -> this.bareNames.add(s.value());
            case Tag.IntTag i when id == SearchFiles.SPAM_SIZE && i.value() > 0 -> this.sizes.add(i.value());
            case Tag.IntTag i when id == SearchFiles.SPAM_SOURCE_IP -> this.sourceIps.add(Ipv4.fromEd2k(i.value()));
            case Tag.IntTag i when id == SearchFiles.SPAM_SERVER_IP -> this.serverIps.add(Ipv4.fromEd2k(i.value()));
            case Tag.BlobTag b
            when id == SearchFiles.SPAM_UDP_SERVER_RATIO && b.value().length() == UDP_RECORD_BYTES -> {
                byte[] record = b.value().toByteArray();
                int ip = Ipv4.fromEd2k(Integer.toUnsignedLong(LittleEndian.getInt(record, 0)));
                long results = Integer.toUnsignedLong(LittleEndian.getInt(record, 4));
                long spamResults = Integer.toUnsignedLong(LittleEndian.getInt(record, 8));
                this.servers.put(ip, new ServerRecord(results, Math.min(spamResults, results)));
            }
            default -> this.others.add(tag);
        }
    }

    public void save(Path file) throws IOException {
        List<Tag> markers = new ArrayList<>();
        for (String name : this.names.values()) {
            markers.add(new Tag.StringTag(TagName.id(SearchFiles.SPAM_FULL_NAME), name));
        }
        for (String name : this.bareNames) {
            markers.add(new Tag.StringTag(TagName.id(SearchFiles.SPAM_SIMILAR_NAME), name));
        }
        for (long size : this.sizes) {
            markers.add(new Tag.IntTag(TagName.id(SearchFiles.SPAM_SIZE), size));
        }
        for (Hash16 hash : this.spam) {
            markers.add(new Tag.HashTag(TagName.id(SearchFiles.SPAM_HASH), hash));
        }
        for (Hash16 hash : this.notSpam) {
            markers.add(new Tag.HashTag(TagName.id(SearchFiles.NOT_SPAM_HASH), hash));
        }
        for (int ip : this.serverIps) {
            markers.add(new Tag.IntTag(TagName.id(SearchFiles.SPAM_SERVER_IP), Ipv4.toEd2k(ip)));
        }
        for (int ip : this.sourceIps) {
            markers.add(new Tag.IntTag(TagName.id(SearchFiles.SPAM_SOURCE_IP), Ipv4.toEd2k(ip)));
        }
        for (Map.Entry<Integer, ServerRecord> e : this.servers.entrySet()) {
            byte[] record = new byte[UDP_RECORD_BYTES];
            LittleEndian.putInt(record, 0, (int) Ipv4.toEd2k(e.getKey()));
            LittleEndian.putInt(record, 4, (int) Math.min(e.getValue().results(), 0xFFFF_FFFFL));
            LittleEndian.putInt(record, 8, (int) Math.min(e.getValue().spam(), 0xFFFF_FFFFL));
            markers.add(new Tag.BlobTag(TagName.id(SearchFiles.SPAM_UDP_SERVER_RATIO), Bytes.of(record)));
        }
        markers.addAll(this.others);
        markers.removeIf(marker -> !writable(marker));
        AtomicFiles.writeBytes(file, SearchFiles.writeSpam(markers));
    }

    // Short blobs and strings too long for their length field cannot be written as eD2K tags.
    private static boolean writable(Tag tag) {
        try {
            TagCodec.writeEd2k(new PacketWriter(), tag, Ed2kTagFormat.NEW, StringEncoding.UTF8_BOM_IF_NEEDED);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Learns what {@code result} has, to rate other results like it as spam; or, not spam, forgets what would rate it
     * so. The UDP servers that gave it count one spam result more, or less.
     */
    public void mark(SearchResult result, boolean isSpam) {
        boolean wasSpam = rate(result) >= SearchResult.SPAM_THRESHOLD;
        SearchResult.Origin origin = result.origin();
        if (isSpam) {
            this.notSpam.remove(result.hash());
            add(this.spam, result.hash());
            this.names.put(result.name().toLowerCase(Locale.ROOT), result.name());
            trim(this.names);
            if (!origin.bareName().isEmpty()) {
                add(this.bareNames, origin.bareName());
            }
            add(this.sizes, result.size());
            origin.sourceIps().forEach(ip -> add(this.sourceIps, ip));
            origin.udpServers().forEach(ip -> add(this.serverIps, ip));
        } else {
            this.spam.remove(result.hash());
            add(this.notSpam, result.hash());
            this.names.remove(result.name().toLowerCase(Locale.ROOT));
            this.bareNames.removeIf(bare -> nameRating(origin.bareName(), bare) > 0);
            this.sizes.removeIf(size -> similarSize(result.size(), size));
            this.sourceIps.removeAll(origin.sourceIps());
            this.serverIps.removeAll(origin.udpServers());
        }
        if (wasSpam != isSpam) {
            for (int ip : origin.udpServers()) {
                ServerRecord record = this.servers.get(ip);
                if (record != null) {
                    long count = Math.clamp(record.spam() + (isSpam ? 1 : -1), 0, record.results());
                    this.servers.put(ip, new ServerRecord(record.results(), count));
                }
            }
        }
    }

    /** The server at {@code ip} gave {@code results} over UDP, {@code spam} of them rated as spam. */
    public void answered(int ip, int results, int spamResults) {
        ServerRecord record = this.servers.remove(ip);
        long all = (record == null ? 0 : record.results()) + results;
        long spamOnes = (record == null ? 0 : record.spam()) + spamResults;
        this.servers.put(ip, new ServerRecord(all, Math.min(spamOnes, all)));
        trim(this.servers);
    }

    /** How much {@code result} looks like the files marked as spam; 0 for a file cleared again. */
    public int rate(SearchResult result) {
        if (this.notSpam.contains(result.hash())) {
            return 0;
        }
        SearchResult.Origin origin = result.origin();
        int rating = this.spam.contains(result.hash()) ? HASH_HIT : 0;
        rating += nameRating(result);
        if (this.sizes.stream().anyMatch(size -> similarSize(result.size(), size))) {
            rating += SIZE_HIT;
        }
        rating += serverRating(origin);
        boolean onlySpamServers = !origin.direct()
                && !origin.udpServers().isEmpty()
                && origin.udpServers().stream().allMatch(this::isSpamServer);
        if (onlySpamServers) {
            rating += ONLY_SPAM_SERVERS_HIT;
            if (result.sources() > POPULAR) {
                rating += smallProgram(result) || sourceNearServer(origin) ? POPULAR_SMALL_PROGRAM_HIT : POPULAR_HIT;
            }
        }
        if (origin.sourceIps().stream().anyMatch(this.sourceIps::contains)) {
            rating += SOURCE_HIT;
        }
        return rating;
    }

    // The same name as a spam file, or one like it once the words searched for are left out.
    private int nameRating(SearchResult result) {
        String name = result.name().toLowerCase(Locale.ROOT);
        if (this.names.containsKey(name)) {
            return name.length() <= SHORT_NAME ? SHORT_FULL_NAME_HIT : FULL_NAME_HIT;
        }
        String bare = result.origin().bareName();
        int best = 0;
        for (String marked : this.bareNames) {
            int rating = nameRating(bare, marked);
            if (rating >= BARE_NAME_HIT || (rating == SHORT_BARE_NAME_HIT && bare.equals(marked))) {
                return rating;
            }
            best = Math.max(best, rating);
        }
        return best;
    }

    // Equal, or close for names long enough: the fewer edits for the length, the closer.
    static int nameRating(String bare, String marked) {
        if (bare.isEmpty()) {
            return 0;
        }
        if (bare.equals(marked)) {
            return bare.length() <= SHORT_NAME ? SHORT_BARE_NAME_HIT : BARE_NAME_HIT;
        }
        int length = bare.length();
        int apart = Math.abs(length - marked.length());
        if (length <= SHORT_NAME || (apart != 0 && length / apart < 3)) {
            return 0;
        }
        int edits = Levenshtein.distance(bare, marked);
        int closeness = length / edits;
        if (closeness >= 6) {
            return NEAR_NAME_HIT;
        }
        return closeness >= 3 ? FAR_NAME_HIT : 0;
    }

    private static boolean similarSize(long size, long marked) {
        long apart = Math.abs(size - marked);
        return size > 0 && apart < SIZE_SPREAD && apart * 100 / size < SIZE_PERCENT;
    }

    // A server that gave spam before: more when it is the result's only server and few such servers are known.
    private int serverRating(SearchResult.Origin origin) {
        if (origin.udpServers().stream().noneMatch(this.serverIps::contains)) {
            return 0;
        }
        int servers = origin.udpServers().size() + (origin.direct() ? 1 : 0);
        if (servers > 1) {
            return ONE_SERVER_OF_SEVERAL_HIT;
        }
        return this.serverIps.size() <= FEW_SPAM_SERVERS ? ONLY_SERVER_HIT : ONLY_SERVER_OF_MANY_HIT;
    }

    // A server never heard over UDP is no spam server: no record is no proof.
    private boolean isSpamServer(int ip) {
        ServerRecord record = this.servers.get(ip);
        return record != null && record.isSpamServer();
    }

    private static boolean smallProgram(SearchResult result) {
        Optional<String> type = FileTypes.of(result.name());
        return type.filter(t -> t.equals(FileTypes.PROGRAM) || t.equals(FileTypes.ARCHIVE))
                        .isPresent()
                && result.size() > SMALL_PROGRAM_MIN
                && result.size() < SMALL_PROGRAM_MAX;
    }

    // A server that offers its own files: a source in its /24.
    private static boolean sourceNearServer(SearchResult.Origin origin) {
        for (int server : origin.udpServers()) {
            for (int source : origin.sourceIps()) {
                if ((server >>> 8) == (source >>> 8)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T> void add(SequencedCollection<T> markers, T marker) {
        markers.remove(marker);
        markers.addLast(marker);
        while (markers.size() > MAX_MARKERS) {
            markers.removeFirst();
        }
    }

    private static void trim(Map<?, ?> markers) {
        Iterator<?> oldest = markers.keySet().iterator();
        while (markers.size() > MAX_MARKERS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    public SpamMarks marks() {
        return new SpamMarks(Set.copyOf(this.spam), Set.copyOf(this.notSpam), Set.copyOf(this.names.keySet()));
    }
}
