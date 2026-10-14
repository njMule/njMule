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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.njmule.core.api.FileComment;
import org.njmule.core.api.Hash16;
import org.njmule.core.files.FileTypes;
import org.njmule.core.protocol.kad.KadMessages;
import org.njmule.core.protocol.tag.FileTag;
import org.njmule.core.protocol.tag.Tag;
import org.njmule.core.protocol.tag.TagName;

/**
 * Publishes what we share on Kad: each keyword of the complete files once a day, with all its files, ourselves as a
 * source of every shared file every five hours, and our comment on a shared file once a day and whenever it changes.
 * Only a node that is ready and reachable publishes. Engine thread only.
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

        int runningNotePublishes();

        /** Starts publishing {@code files} under {@code word}; false when it cannot now. */
        boolean publishKeyword(String word, List<KadMessages.Entry> files);

        /** Starts publishing us as a source of a file; false when it cannot now. */
        boolean publishSource(Hash16 hash, long size);

        /**
         * Starts publishing our comment on a file, read again for each node it goes to: taken away, the publish ends.
         * False when it cannot start now.
         */
        boolean publishNote(Hash16 hash, String name, long size, Supplier<Optional<FileComment>> comment);
    }

    public static final Duration KEYWORD_EVERY = Duration.ofHours(24);
    public static final Duration SOURCE_EVERY = Duration.ofHours(5);
    public static final Duration NOTE_EVERY = Duration.ofHours(24);

    /** Files published under one keyword at most. */
    public static final int FILES_PER_KEYWORD = 150;

    private static final Duration START_EVERY = Duration.ofSeconds(2);
    private static final int MAX_KEYWORD_PUBLISHES = 3;
    private static final int MAX_SOURCE_PUBLISHES = 4;
    private static final int MAX_NOTE_PUBLISHES = 1;

    private final Node node;
    private final Supplier<List<Shared>> shared;
    private final Function<Hash16, Optional<FileComment>> comments;
    private final Ticker ticker;
    private final Map<String, Long> keywordDone = new HashMap<>();
    private final Map<Hash16, Long> sourceDone = new HashMap<>();
    private final Map<Hash16, PublishedNote> noteDone = new HashMap<>();
    private long nextKeyword;
    private long nextSource;
    private long nextNote;

    /** Our comment on a file as last published, and when. */
    private record PublishedNote(FileComment comment, long at) {}

    /** A publisher of keywords and sources only. */
    public KadPublisher(Node node, Supplier<List<Shared>> shared, Ticker ticker) {
        this(node, shared, hash -> Optional.empty(), ticker);
    }

    /** A publisher that also publishes our {@code comments} on the files we share. */
    public KadPublisher(
            Node node, Supplier<List<Shared>> shared, Function<Hash16, Optional<FileComment>> comments, Ticker ticker) {
        this.node = Objects.requireNonNull(node, "node");
        this.shared = Objects.requireNonNull(shared, "shared");
        this.comments = Objects.requireNonNull(comments, "comments");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    /** Runs every second: starts at most one keyword, one source and one note publish every two seconds. */
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
        if (now - this.nextNote >= 0 && this.node.runningNotePublishes() < MAX_NOTE_PUBLISHES) {
            this.nextNote = now + START_EVERY.toNanos();
            this.publishOneNote(files, now);
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

    // A changed comment is due at once; one taken away is published no more, and at once if it comes back.
    private void publishOneNote(List<Shared> files, long now) {
        Set<Hash16> commented = new HashSet<>();
        Shared due = null;
        FileComment dueComment = null;
        for (Shared file : files) {
            Optional<FileComment> comment = this.comment(file.hash());
            if (comment.isEmpty()) {
                continue;
            }
            commented.add(file.hash());
            PublishedNote done = this.noteDone.get(file.hash());
            if (due == null
                    && (done == null
                            || !done.comment().equals(comment.get())
                            || now - done.at() >= NOTE_EVERY.toNanos())) {
                due = file;
                dueComment = comment.get();
            }
        }
        this.noteDone.keySet().retainAll(commented);
        if (due == null) {
            return;
        }
        Hash16 hash = due.hash();
        if (this.node.publishNote(hash, due.name(), due.size(), () -> this.comment(hash))) {
            this.noteDone.put(hash, new PublishedNote(dueComment, now));
        }
    }

    private Optional<FileComment> comment(Hash16 file) {
        return this.comments.apply(file).filter(c -> !c.isEmpty());
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
