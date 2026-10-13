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
package org.njmule.gui.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Rows kept by key: a new snapshot updates the rows in place, adds the new ones and drops the
 * vanished, so tables keep their selection and sort order. FX thread only.
 *
 * @param <K> the key of a row
 * @param <V> the value a row shows
 */
public final class LiveList<K, V> {

    /** One row: its value changes, the row stays. */
    public static final class Row<V> {
        private final ReadOnlyObjectWrapper<V> value;

        Row(V value) {
            this.value = new ReadOnlyObjectWrapper<>(value);
        }

        public V get() {
            return this.value.get();
        }

        public ReadOnlyObjectProperty<V> valueProperty() {
            return this.value.getReadOnlyProperty();
        }
    }

    private final Function<V, K> key;
    private final Map<K, Row<V>> byKey = new HashMap<>();
    private final ObservableList<Row<V>> rows =
            FXCollections.observableArrayList(row -> new Observable[] {row.valueProperty()});

    public LiveList(Function<V, K> key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    /** The rows; a value change shows as an update of its row. */
    public ObservableList<Row<V>> rows() {
        return this.rows;
    }

    /** Makes the rows show {@code values}, in this order for new rows. */
    public void update(List<V> values) {
        Set<K> seen = new HashSet<>();
        for (V value : values) {
            K k = this.key.apply(value);
            if (!seen.add(k)) {
                continue;
            }
            this.put(k, value);
        }
        if (seen.size() != this.byKey.size()) {
            this.rows.removeIf(row -> !seen.contains(this.key.apply(row.get())));
            this.byKey.keySet().retainAll(seen);
        }
    }

    /** Updates or adds one row. */
    public void put(V value) {
        this.put(this.key.apply(value), value);
    }

    public void remove(K k) {
        Row<V> row = this.byKey.remove(k);
        if (row != null) {
            this.rows.remove(row);
        }
    }

    public int size() {
        return this.rows.size();
    }

    private void put(K k, V value) {
        Row<V> row = this.byKey.get(k);
        if (row == null) {
            row = new Row<>(value);
            this.byKey.put(k, row);
            this.rows.add(row);
        } else if (!row.get().equals(value)) {
            row.value.set(value);
        }
    }
}
