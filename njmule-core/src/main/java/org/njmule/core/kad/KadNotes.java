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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.KadNote;
import org.njmule.core.files.CommentFilter;

/**
 * What Kad nodes said of our files: the notes found by searching Kad, on request and once a run for each download. Each
 * publisher counts once, with its latest note; the comment filter applies when they are read. Engine thread only.
 */
public final class KadNotes {

    /** Kad, while it is connected. */
    public interface Finder {
        /** Whether the node knows the network well enough for searches nobody asked for. */
        boolean isReady();

        /** Starts a search for the notes on a file, then runs {@code ended}; false when it cannot start now. */
        boolean findNotes(Hash16 file, long size, Consumer<List<KadNode.Note>> notes, Runnable ended);
    }

    /** A download whose notes are looked for once a run. */
    public record Download(Hash16 hash, long size) {
        public Download {
            Objects.requireNonNull(hash, "hash");
        }
    }

    /** Notes kept per file at most. */
    static final int MAX_NOTES_PER_FILE = KadIndex.MAX_NOTES_PER_FILE;

    /** Notes kept in all at most: the files searched longest ago lose theirs first. */
    static final int MAX_NOTES = 10_000;

    /** Files whose notes are kept at most, likewise. */
    static final int MAX_FILES = 1_000;

    /** Searches nobody asked for start this far apart at least, one at a time. */
    static final Duration AUTOMATIC_EVERY = Duration.ofSeconds(10);

    private final Supplier<Optional<Finder>> kad;
    private final Supplier<List<Download>> downloads;
    private final Supplier<CommentFilter> filter;
    private final Ticker ticker;
    // By file, searched longest ago first; then by publisher, oldest first.
    private final Map<Hash16, Map<UInt128, KadNode.Note>> found = new LinkedHashMap<>();
    // The searches running now, with whoever waits for their end.
    private final Map<Hash16, List<CompletableFuture<List<KadNote>>>> running = new HashMap<>();
    private final Set<Hash16> searched = new HashSet<>();
    private int kept;
    private long nextAutomatic;

    /**
     * Notes searched for through {@code kad}, which is empty while Kad is not connected, on request and for the
     * {@code downloads} that run now.
     */
    public KadNotes(
            Supplier<Optional<Finder>> kad,
            Supplier<List<Download>> downloads,
            Supplier<CommentFilter> filter,
            Ticker ticker) {
        this.kad = Objects.requireNonNull(kad, "kad");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.nextAutomatic = ticker.read();
    }

    /** The notes found on {@code file}, without those the comment filter leaves out. */
    public List<KadNote> notes(Hash16 file) {
        Map<UInt128, KadNode.Note> notes = this.found.get(file);
        if (notes == null) {
            return List.of();
        }
        CommentFilter words = this.filter.get();
        List<KadNote> shown = new ArrayList<>();
        for (KadNode.Note n : notes.values()) {
            // The file name may carry the spam as well as the comment.
            if (!words.leavesOut(n.fileName()) && !words.leavesOut(n.comment().text())) {
                shown.add(new KadNote(n.publisher().toHex(), n.fileName(), n.comment()));
            }
        }
        return shown;
    }

    /**
     * Searches Kad for the notes on {@code file} of {@code size} bytes, or joins the search running for it;
     * {@code done} completes with the notes then known once it ends, and fails when Kad is not connected or busy with
     * the file.
     */
    public void search(Hash16 file, long size, CompletableFuture<List<KadNote>> done) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(done, "done");
        List<CompletableFuture<List<KadNote>>> waiting = this.running.get(file);
        if (waiting == null) {
            Optional<Finder> finder = this.kad.get();
            if (finder.isEmpty()) {
                done.completeExceptionally(new IllegalStateException("Kad is not connected"));
                return;
            }
            if (!start(finder.get(), file, size)) {
                done.completeExceptionally(
                        new IllegalStateException("Kad is busy with this file: try again in a minute"));
                return;
            }
            waiting = this.running.get(file);
        }
        waiting.add(done);
    }

    /** Runs every second: now and then searches for the notes on a download not searched for in this run yet. */
    public void tick() {
        long now = this.ticker.read();
        if (!this.running.isEmpty() || now - this.nextAutomatic < 0) {
            return;
        }
        this.nextAutomatic = now + AUTOMATIC_EVERY.toNanos();
        Optional<Finder> finder = this.kad.get().filter(Finder::isReady);
        if (finder.isEmpty()) {
            return;
        }
        Set<Hash16> current = new HashSet<>();
        boolean started = false;
        for (Download d : this.downloads.get()) {
            current.add(d.hash());
            if (!started && !this.searched.contains(d.hash())) {
                started = start(finder.get(), d.hash(), d.size());
            }
        }
        // A download taken away and added again is searched for again.
        this.searched.retainAll(current);
    }

    /** Kad stopped: the searches running end with what they found. */
    public void kadStopped() {
        for (Hash16 file : List.copyOf(this.running.keySet())) {
            ended(file);
        }
    }

    private boolean start(Finder finder, Hash16 file, long size) {
        this.running.put(file, new ArrayList<>());
        if (!finder.findNotes(file, size, notes -> found(file, notes), () -> ended(file))) {
            this.running.remove(file);
            return false;
        }
        this.searched.add(file);
        // The file searched last keeps its notes longest.
        Map<UInt128, KadNode.Note> notes = this.found.remove(file);
        this.found.put(file, notes == null ? new LinkedHashMap<>() : notes);
        for (Iterator<Map<UInt128, KadNode.Note>> it = this.found.values().iterator();
                this.found.size() > MAX_FILES; ) {
            this.kept -= it.next().size();
            it.remove();
        }
        return true;
    }

    private void found(Hash16 file, List<KadNode.Note> notes) {
        if (!this.running.containsKey(file)) {
            return;
        }
        Map<UInt128, KadNode.Note> known = this.found.computeIfAbsent(file, f -> new LinkedHashMap<>());
        for (KadNode.Note note : notes) {
            if (known.remove(note.publisher()) != null) {
                this.kept--;
            }
            if (known.size() >= MAX_NOTES_PER_FILE) {
                known.remove(known.keySet().iterator().next());
                this.kept--;
            }
            known.put(note.publisher(), note);
            this.kept++;
        }
        for (Iterator<Map.Entry<Hash16, Map<UInt128, KadNode.Note>>> it =
                        this.found.entrySet().iterator();
                it.hasNext() && this.kept > MAX_NOTES; ) {
            Map.Entry<Hash16, Map<UInt128, KadNode.Note>> oldest = it.next();
            if (!oldest.getKey().equals(file)) {
                this.kept -= oldest.getValue().size();
                it.remove();
            }
        }
    }

    private void ended(Hash16 file) {
        List<CompletableFuture<List<KadNote>>> waiting = this.running.remove(file);
        if (waiting != null) {
            List<KadNote> notes = notes(file);
            waiting.forEach(w -> w.complete(notes));
        }
    }
}
