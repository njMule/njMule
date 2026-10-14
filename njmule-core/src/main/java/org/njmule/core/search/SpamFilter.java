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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SpamMarks;
import org.njmule.core.persist.AtomicFiles;
import org.njmule.core.persist.format.SearchFiles;
import org.njmule.core.protocol.io.DecodeException;
import org.njmule.core.protocol.io.PacketWriter;
import org.njmule.core.protocol.io.StringEncoding;
import org.njmule.core.protocol.tag.Ed2kTagFormat;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagCodec;
import org.njmule.core.protocol.tag.TagName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The search results marked as spam, kept in {@code SearchSpam.met}: by file hash, by full name, and the hashes cleared
 * again. The other markers of that file (similar names, sizes, addresses) are kept as they are.
 */
public final class SpamFilter {

    public static final String FILE_NAME = "SearchSpam.met";

    private static final Logger logger = LoggerFactory.getLogger(SpamFilter.class);
    private static final DateTimeFormatter CORRUPT_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Set<Hash16> spam = new LinkedHashSet<>();
    private final Set<Hash16> notSpam = new LinkedHashSet<>();
    // By lower case name, the name as written.
    private final Map<String, String> names = new LinkedHashMap<>();
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
            default -> this.others.add(tag);
        }
    }

    public void save(Path file) throws IOException {
        List<Tag> markers = new ArrayList<>();
        for (String name : this.names.values()) {
            markers.add(new Tag.StringTag(TagName.id(SearchFiles.SPAM_FULL_NAME), name));
        }
        for (Hash16 hash : this.spam) {
            markers.add(new Tag.HashTag(TagName.id(SearchFiles.SPAM_HASH), hash));
        }
        for (Hash16 hash : this.notSpam) {
            markers.add(new Tag.HashTag(TagName.id(SearchFiles.NOT_SPAM_HASH), hash));
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

    /** Marks the file {@code hash} named {@code name} as spam, or clears it. */
    public void mark(Hash16 hash, String name, boolean isSpam) {
        String key = name.toLowerCase(Locale.ROOT);
        if (isSpam) {
            this.notSpam.remove(hash);
            this.spam.add(hash);
            this.names.put(key, name);
        } else {
            this.spam.remove(hash);
            this.names.remove(key);
            this.notSpam.add(hash);
        }
    }

    public SpamMarks marks() {
        return new SpamMarks(Set.copyOf(this.spam), Set.copyOf(this.notSpam), Set.copyOf(this.names.keySet()));
    }
}
