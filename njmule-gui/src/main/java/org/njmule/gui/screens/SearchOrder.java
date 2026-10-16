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

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.SortedList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.njmule.core.api.SearchResult;
import org.njmule.gui.model.LiveList;

/**
 * How search results sort, once a column is chosen: spam last in either direction, names by the rules of the
 * interface's language whatever their case, values a result lacks last in either direction, and the columns chosen
 * before break ties. With no column chosen the results keep the order they came in.
 */
final class SearchOrder {

    // Columns chosen before that still break ties.
    private static final int EARLIER = 3;

    private record Earlier(TableColumn<LiveList.Row<SearchResult>, ?> column, TableColumn.SortType type) {}

    private SearchOrder() {}

    /** Text compared as the interface's language orders it, case aside. */
    static Comparator<String> text(Locale language) {
        Collator collator = Collator.getInstance(language);
        collator.setStrength(Collator.SECONDARY);
        return collator::compare;
    }

    /** {@code present} for the values a result has; those it lacks go last whichever way {@code column} sorts. */
    static <S, T> Comparator<T> missingLast(TableColumn<S, T> column, Predicate<T> missing, Comparator<T> present) {
        return (a, b) -> {
            boolean noA = missing.test(a);
            boolean noB = missing.test(b);
            if (noA || noB) {
                // The table turns the comparison round for a descending column.
                int last = column.getSortType() == TableColumn.SortType.ASCENDING ? 1 : -1;
                return noA == noB ? 0 : (noA ? last : -last);
            }
            return present.compare(a, b);
        };
    }

    /** The complete sources of a result, compared as a share of its sources, and shown as their number. */
    record Complete(int complete, int sources) implements Comparable<Complete> {
        int percent() {
            return this.sources <= 0 ? 0 : (int) (100L * this.complete / this.sources);
        }

        @Override
        public int compareTo(Complete other) {
            return Integer.compare(percent(), other.percent());
        }
    }

    /** The type of a file, then its extension, which files without one follow; shown as the type. */
    record Kind(String type, String extension) implements Comparable<Kind> {
        static Kind of(String type, String name) {
            int dot = name.lastIndexOf('.');
            return new Kind(type, dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1));
        }

        @Override
        public int compareTo(Kind other) {
            int byType = this.type.compareTo(other.type);
            if (byType != 0) {
                return byType;
            }
            if (this.extension.isEmpty() != other.extension.isEmpty()) {
                return this.extension.isEmpty() ? 1 : -1;
            }
            return this.extension.compareToIgnoreCase(other.extension);
        }
    }

    /**
     * Sorts {@code table}, made by {@link org.njmule.gui.ui.Fx#table}, with spam last and the columns chosen before as
     * tie breakers.
     */
    static void apply(TableView<LiveList.Row<SearchResult>> table) {
        List<Earlier> earlier = new ArrayList<>();
        // The table sorts as its order changes, before the columns chosen before are known: they sort again after.
        IntegerProperty earlierChanged = new SimpleIntegerProperty();
        List<TableColumn<LiveList.Row<SearchResult>, ?>> chosen = new ArrayList<>(table.getSortOrder());
        table.getSortOrder().addListener((ListChangeListener<TableColumn<LiveList.Row<SearchResult>, ?>>) c -> {
            List<TableColumn<LiveList.Row<SearchResult>, ?>> now = List.copyOf(table.getSortOrder());
            for (TableColumn<LiveList.Row<SearchResult>, ?> column : chosen) {
                if (!now.contains(column)) {
                    earlier.removeIf(e -> e.column().equals(column));
                    earlier.addFirst(new Earlier(column, column.getSortType()));
                }
            }
            earlier.removeIf(e -> now.contains(e.column()));
            while (earlier.size() > EARLIER) {
                earlier.removeLast();
            }
            chosen.clear();
            chosen.addAll(now);
            earlierChanged.set(earlierChanged.get() + 1);
        });
        // The comparator below sorts the rows: the table's own sorting has nothing to do.
        table.setSortPolicy(t -> true);
        SortedList<LiveList.Row<SearchResult>> sorted = (SortedList<LiveList.Row<SearchResult>>) table.getItems();
        sorted.comparatorProperty().unbind();
        sorted.comparatorProperty()
                .bind(Bindings.createObjectBinding(
                        () -> {
                            Comparator<LiveList.Row<SearchResult>> columns = table.getComparator();
                            if (columns == null) {
                                return null;
                            }
                            Comparator<LiveList.Row<SearchResult>> order =
                                    Comparator.<LiveList.Row<SearchResult>, Boolean>comparing(
                                                    r -> r.get().isSpam())
                                            .thenComparing(columns);
                            for (Earlier e : List.copyOf(earlier)) {
                                order = order.thenComparing(by(e));
                            }
                            return order;
                        },
                        table.comparatorProperty(),
                        earlierChanged));
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // a column's comparator takes the values of its own cells
    private static Comparator<LiveList.Row<SearchResult>> by(Earlier e) {
        Comparator values = e.column().getComparator();
        Comparator<LiveList.Row<SearchResult>> order =
                (a, b) -> values.compare(e.column().getCellData(a), e.column().getCellData(b));
        return e.type() == TableColumn.SortType.DESCENDING ? order.reversed() : order;
    }
}
