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
package org.njmule.gui.screens;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.Observable;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.njmule.core.api.Hash16;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.model.LiveList;

/**
 * The rows of a search's results: one per file and, under a file found by several names while it is open, one per name,
 * whose value is the file as found under that name. A name's row follows its file's in the list.
 */
final class ResultRows {

    private record NameKey(Hash16 hash, String name) {}

    private final LiveList<NameKey, SearchResult> names = new LiveList<>(r -> new NameKey(r.hash(), r.name()));
    private final ObservableList<LiveList.Row<SearchResult>> rows =
            FXCollections.observableArrayList(row -> new Observable[] {row.valueProperty()});
    // The file of each name row shown, and the name rows of each open file in their order.
    private final IdentityHashMap<LiveList.Row<SearchResult>, LiveList.Row<SearchResult>> fileOf =
            new IdentityHashMap<>();
    private final Map<Hash16, List<LiveList.Row<SearchResult>>> open = new HashMap<>();
    // The order files came in, which breaks ties between them.
    private final IdentityHashMap<LiveList.Row<SearchResult>, Long> arrival = new IdentityHashMap<>();
    private final IntegerProperty toggled = new SimpleIntegerProperty();
    private long arrived;

    ResultRows(LiveList<Hash16, SearchResult> files) {
        added(files.rows());
        files.rows().addListener((ListChangeListener<LiveList.Row<SearchResult>>) this::filesChanged);
    }

    ObservableList<LiveList.Row<SearchResult>> rows() {
        return this.rows;
    }

    /** Whether {@code row} shows one name of a file rather than the file. */
    boolean isName(LiveList.Row<SearchResult> row) {
        return this.fileOf.containsKey(row);
    }

    /** The row of the file {@code row} shows, itself for a file. */
    LiveList.Row<SearchResult> fileOf(LiveList.Row<SearchResult> row) {
        return this.fileOf.getOrDefault(row, row);
    }

    /** Whether {@code row} is a file found under several names, which can open. */
    boolean hasNames(LiveList.Row<SearchResult> row) {
        return !isName(row) && row.get().names().size() > 1;
    }

    boolean isOpen(LiveList.Row<SearchResult> row) {
        return this.open.containsKey(fileOf(row).get().hash());
    }

    /** Counts the files opened and closed, for the cells that show it. */
    ReadOnlyIntegerProperty toggled() {
        return this.toggled;
    }

    /** Opens the file of {@code row} to show its names, or closes it. */
    void toggle(LiveList.Row<SearchResult> row) {
        if (isOpen(row)) {
            close(row);
        } else {
            open(row);
        }
    }

    void open(LiveList.Row<SearchResult> row) {
        LiveList.Row<SearchResult> file = fileOf(row);
        if (hasNames(file) && !isOpen(file)) {
            showNames(file);
            this.toggled.set(this.toggled.get() + 1);
        }
    }

    void close(LiveList.Row<SearchResult> row) {
        List<LiveList.Row<SearchResult>> shown =
                this.open.remove(fileOf(row).get().hash());
        if (shown == null) {
            return;
        }
        this.rows.removeAll(shown);
        for (LiveList.Row<SearchResult> name : shown) {
            this.fileOf.remove(name);
            this.names.remove(key(name.get()));
        }
        this.toggled.set(this.toggled.get() + 1);
    }

    /**
     * Rows in the order of their files by {@code files}, then as the files came; each file before its names, and those
     * by {@code byName}, then as they are listed.
     */
    Comparator<LiveList.Row<SearchResult>> grouped(
            Comparator<LiveList.Row<SearchResult>> files, Comparator<LiveList.Row<SearchResult>> byName) {
        Comparator<LiveList.Row<SearchResult>> byFile =
                files.thenComparingLong(r -> this.arrival.getOrDefault(r, Long.MAX_VALUE));
        return (a, b) -> {
            LiveList.Row<SearchResult> fileA = fileOf(a);
            LiveList.Row<SearchResult> fileB = fileOf(b);
            if (fileA != fileB) {
                return byFile.compare(fileA, fileB);
            }
            if (a == b) {
                return 0;
            }
            if (a == fileA || b == fileB) {
                return a == fileA ? -1 : 1;
            }
            int order = byName.compare(a, b);
            if (order != 0) {
                return order;
            }
            List<LiveList.Row<SearchResult>> shown =
                    this.open.getOrDefault(fileA.get().hash(), List.of());
            return Integer.compare(shown.indexOf(a), shown.indexOf(b));
        };
    }

    private void filesChanged(ListChangeListener.Change<? extends LiveList.Row<SearchResult>> c) {
        while (c.next()) {
            if (c.wasUpdated()) {
                for (int i = c.getFrom(); i < c.getTo(); i++) {
                    LiveList.Row<SearchResult> file = c.getList().get(i);
                    if (this.open.containsKey(file.get().hash())) {
                        showNames(file);
                    }
                }
                continue;
            }
            for (LiveList.Row<SearchResult> gone : c.getRemoved()) {
                close(gone);
                this.rows.remove(gone);
                this.arrival.remove(gone);
            }
            added(c.getAddedSubList());
        }
    }

    private void added(List<? extends LiveList.Row<SearchResult>> files) {
        for (LiveList.Row<SearchResult> file : files) {
            this.arrival.put(file, this.arrived++);
        }
        this.rows.addAll(files);
    }

    // Shows a row for each name of an open file, new names after the others, and the counts of those shown.
    private void showNames(LiveList.Row<SearchResult> file) {
        SearchResult found = file.get();
        List<LiveList.Row<SearchResult>> shown = this.open.computeIfAbsent(found.hash(), h -> new ArrayList<>());
        for (SearchResult.Name name : found.names()) {
            SearchResult named = found.named(name);
            this.names.put(named);
            LiveList.Row<SearchResult> row = this.names.row(key(named)).orElseThrow();
            if (!this.fileOf.containsKey(row)) {
                // Known as a name before the list shows it: its filter and count ask.
                int at = this.rows.indexOf(shown.isEmpty() ? file : shown.getLast()) + 1;
                shown.add(row);
                this.fileOf.put(row, file);
                this.rows.add(at, row);
            }
        }
    }

    private static NameKey key(SearchResult named) {
        return new NameKey(named.hash(), named.name());
    }
}
