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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;

class LiveListTest {

    private record Item(String key, int value) {}

    private final LiveList<String, Item> list = new LiveList<>(Item::key);
    private final List<String> changes = new ArrayList<>();
    private LiveList.Row<Item> kept;

    @Test
    void shouldKeepTheRowsAndChangeTheirValues() {
        givenTheChangesAreRecorded();
        givenTheListIsUpdatedWith(new Item("a", 1), new Item("b", 2), new Item("a", 9));
        givenTheFirstRowIsKept();
        givenTheListIsUpdatedWith(new Item("a", 3), new Item("b", 2));

        whenTheListIsUpdatedWith(new Item("b", 2));

        thenTheKeptRowHolds(new Item("a", 3));
        thenTheListHasOneRowWithKey("b");
        thenTheChangesAre("added", "added", "updated", "removed");
    }

    @Test
    void shouldPutAndRemoveRowsByKey() {
        givenTheListIsUpdatedWith(new Item("b", 2));
        givenARowIsPut(new Item("c", 4));

        whenTheRowsAreRemoved("b", "nothing");

        thenTheRowKeysAre("c");
    }

    private void givenTheChangesAreRecorded() {
        this.list.rows().addListener((ListChangeListener<LiveList.Row<Item>>) c -> {
            while (c.next()) {
                this.changes.add(c.wasUpdated() ? "updated" : c.wasAdded() ? "added" : "removed");
            }
        });
    }

    private void givenTheListIsUpdatedWith(Item... items) {
        this.list.update(List.of(items));
    }

    private void givenTheFirstRowIsKept() {
        this.kept = this.list.rows().getFirst();
    }

    private void givenARowIsPut(Item item) {
        this.list.put(item);
    }

    private void whenTheListIsUpdatedWith(Item... items) {
        this.list.update(List.of(items));
    }

    private void whenTheRowsAreRemoved(String... keys) {
        for (String key : keys) {
            this.list.remove(key);
        }
    }

    private void thenTheKeptRowHolds(Item item) {
        assertThat(this.kept.get()).isEqualTo(item);
        assertThat(this.kept.valueProperty().get()).isEqualTo(item);
    }

    private void thenTheListHasOneRowWithKey(String key) {
        assertThat(this.list.size()).isEqualTo(1);
        assertThat(this.list.rows().getFirst().get().key()).isEqualTo(key);
    }

    private void thenTheChangesAre(String... changes) {
        assertThat(this.changes).containsExactly(changes);
    }

    private void thenTheRowKeysAre(String... keys) {
        assertThat(this.list.rows()).extracting(r -> r.get().key()).containsExactly(keys);
    }
}
