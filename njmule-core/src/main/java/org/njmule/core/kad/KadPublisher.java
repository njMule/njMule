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

        /** Whether other clients can reach us: directly, through a buddy or by a direct callback. */
        default boolean isReachable() {
            return !this.isFirewalled();
        }

        /** The address of the buddy that relays callbacks to us, 0 without one. */
        default int buddyIp() {
            return 0;
        }

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
    // Where the files of a keyword with more than its share start at its next publish.
    private final Map<String, Integer> keywordStart = new HashMap<>();
    private final Map<Hash16, Long> sourceDone = new HashMap<>();
    private final Map<Hash16, PublishedNote> noteDone = new HashMap<>();
    private long nextKeyword;
    private long nextSource;
    private long nextNote;
    private int lastBuddy;
    // What each kind tried last: the next try starts after it.
    private String lastKeyword;
    private Hash16 lastSource;
    private Hash16 lastNote;

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

    /**
     * Runs every second: starts at most one keyword, one source and one note publish every two seconds. A new buddy has
     * every source published again at once: the old ones point to the buddy we left.
     */
    public void tick() {
        if (!this.node.isReady() || !this.node.isConnected() || !this.node.isReachable()) {
            return;
        }
        int buddy = this.node.buddyIp();
        if (buddy != this.lastBuddy) {
            this.lastBuddy = buddy;
            if (buddy != 0) {
                this.sourceDone.clear();
            }
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

    // A keyword with more files than its share publishes the next ones each time, round the list.
    private void publishOneKeyword(List<Shared> files, long now) {
        Map<String, List<Shared>> byKeyword = new LinkedHashMap<>();
        for (Shared file : files) {
            if (file.complete()) {
                for (String word : Keywords.of(file.name())) {
                    byKeyword.computeIfAbsent(word, w -> new ArrayList<>()).add(file);
                }
            }
        }
        for (String word : inTurn(List.copyOf(byKeyword.keySet()), this.lastKeyword)) {
            Long done = this.keywordDone.get(word);
            if (done != null && now - done < KEYWORD_EVERY.toNanos()) {
                continue;
            }
            this.lastKeyword = word;
            List<Shared> all = byKeyword.get(word);
            int start = this.keywordStart.getOrDefault(word, 0) % all.size();
            List<KadMessages.Entry> entries = new ArrayList<>();
            for (int i = 0; i < Math.min(all.size(), FILES_PER_KEYWORD); i++) {
                Shared file = all.get((start + i) % all.size());
                entries.add(new KadMessages.Entry(UInt128.of(file.hash()), tags(file)));
            }
            if (this.node.publishKeyword(word, entries)) {
                this.keywordDone.put(word, now);
                this.keywordStart.put(word, (start + entries.size()) % all.size());
            }
            return;
        }
    }

    private void publishOneSource(List<Shared> files, long now) {
        for (Shared file : inTurn(files, this.lastSource)) {
            Long done = this.sourceDone.get(file.hash());
            if (done != null && now - done < SOURCE_EVERY.toNanos()) {
                continue;
            }
            this.lastSource = file.hash();
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
        for (Shared file : inTurn(files, this.lastNote)) {
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
        this.lastNote = hash;
        if (this.node.publishNote(hash, due.name(), due.size(), () -> this.comment(hash))) {
            this.noteDone.put(hash, new PublishedNote(dueComment, now));
        }
    }

    // The words of keywords, or the shared files, starting after the one tried last: one that cannot be published now
    // does not hold back the others, and is tried again at its next turn.
    private static List<String> inTurn(List<String> words, String last) {
        int at = last == null ? -1 : words.indexOf(last);
        return turned(words, at);
    }

    private static List<Shared> inTurn(List<Shared> files, Hash16 last) {
        int at = -1;
        for (int i = 0; last != null && i < files.size(); i++) {
            if (files.get(i).hash().equals(last)) {
                at = i;
                break;
            }
        }
        return turned(files, at);
    }

    private static <T> List<T> turned(List<T> items, int last) {
        if (last < 0) {
            return items;
        }
        List<T> turned = new ArrayList<>(items.size());
        turned.addAll(items.subList(last + 1, items.size()));
        turned.addAll(items.subList(0, last + 1));
        return turned;
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
