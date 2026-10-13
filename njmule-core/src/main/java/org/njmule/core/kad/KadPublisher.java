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

import com.google.common.base.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.FileTypes;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/**
 * Publishes what we share on Kad: each keyword of the complete files once a day, with all its files, and ourselves as a
 * source of every shared file every five hours. Only a node that is ready and reachable publishes. Engine thread only.
 */
public final class KadPublisher {

    /** A file we share; {@code complete} is false for the checked parts of a download. */
    public record Shared(Hash16 hash, String name, long size, boolean complete) {
        public Shared {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(name, "name");
        }
    }

    /** What publishing needs of the node. */
    public interface Node {
        boolean isReady();

        boolean isConnected();

        boolean isFirewalled();

        int runningKeywordPublishes();

        int runningSourcePublishes();

        /** Starts publishing {@code files} under {@code word}; false when it cannot now. */
        boolean publishKeyword(String word, List<KadMessages.Entry> files);

        /** Starts publishing us as a source of a file; false when it cannot now. */
        boolean publishSource(Hash16 hash, long size);
    }

    public static final Duration KEYWORD_EVERY = Duration.ofHours(24);
    public static final Duration SOURCE_EVERY = Duration.ofHours(5);

    /** Files published under one keyword at most. */
    public static final int FILES_PER_KEYWORD = 150;

    private static final Duration START_EVERY = Duration.ofSeconds(2);
    private static final int MAX_KEYWORD_PUBLISHES = 3;
    private static final int MAX_SOURCE_PUBLISHES = 4;

    private final Node node;
    private final Supplier<List<Shared>> shared;
    private final Ticker ticker;
    private final Map<String, Long> keywordDone = new HashMap<>();
    private final Map<Hash16, Long> sourceDone = new HashMap<>();
    private long nextKeyword;
    private long nextSource;

    public KadPublisher(Node node, Supplier<List<Shared>> shared, Ticker ticker) {
        this.node = Objects.requireNonNull(node, "node");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Runs every second: starts at most one keyword and one source publish every two seconds. */
    public void tick() {
        if (!this.node.isReady() || !this.node.isConnected() || this.node.isFirewalled()) {
            return;
        }
        long now = this.ticker.read();
        List<Shared> files = this.shared.get();
        if (now - this.nextKeyword >= 0 && this.node.runningKeywordPublishes() < MAX_KEYWORD_PUBLISHES) {
            this.nextKeyword = now + START_EVERY.toNanos();
            this.publishOneKeyword(files, now);
        }
        if (now - this.nextSource >= 0 && this.node.runningSourcePublishes() < MAX_SOURCE_PUBLISHES) {
            this.nextSource = now + START_EVERY.toNanos();
            this.publishOneSource(files, now);
        }
    }

    private void publishOneKeyword(List<Shared> files, long now) {
        Map<String, List<KadMessages.Entry>> byKeyword = new LinkedHashMap<>();
        for (Shared file : files) {
            if (!file.complete()) {
                continue;
            }
            for (String word : Keywords.of(file.name())) {
                List<KadMessages.Entry> entries = byKeyword.computeIfAbsent(word, w -> new ArrayList<>());
                if (entries.size() < FILES_PER_KEYWORD) {
                    entries.add(new KadMessages.Entry(UInt128.of(file.hash()), tags(file)));
                }
            }
        }
        for (Map.Entry<String, List<KadMessages.Entry>> e : byKeyword.entrySet()) {
            Long done = this.keywordDone.get(e.getKey());
            if (done != null && now - done < KEYWORD_EVERY.toNanos()) {
                continue;
            }
            if (this.node.publishKeyword(e.getKey(), e.getValue())) {
                this.keywordDone.put(e.getKey(), now);
            }
            return;
        }
    }

    private void publishOneSource(List<Shared> files, long now) {
        for (Shared file : files) {
            Long done = this.sourceDone.get(file.hash());
            if (done != null && now - done < SOURCE_EVERY.toNanos()) {
                continue;
            }
            if (this.node.publishSource(file.hash(), file.size())) {
                this.sourceDone.put(file.hash(), now);
            }
            return;
        }
    }

    private static List<Tag> tags(Shared file) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag.StringTag(TagName.id(FileTag.FILENAME), file.name()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.FILESIZE), file.size()));
        tags.add(new Tag.IntTag(TagName.id(FileTag.SOURCES), 1));
        FileTypes.of(file.name()).ifPresent(t -> tags.add(new Tag.StringTag(TagName.id(FileTag.FILETYPE), t)));
        return tags;
    }
}
