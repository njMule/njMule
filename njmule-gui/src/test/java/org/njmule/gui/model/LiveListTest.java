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

    @Test
    void rowsStayAndTheirValuesChange() {
        LiveList<String, Item> list = new LiveList<>(Item::key);
        List<String> changes = new ArrayList<>();
        list.rows().addListener((ListChangeListener<LiveList.Row<Item>>) c -> {
            while (c.next()) {
                changes.add(c.wasUpdated() ? "updated" : c.wasAdded() ? "added" : "removed");
            }
        });

        list.update(List.of(new Item("a", 1), new Item("b", 2), new Item("a", 9)));
        LiveList.Row<Item> a = list.rows().getFirst();
        list.update(List.of(new Item("a", 3), new Item("b", 2)));
        list.update(List.of(new Item("b", 2)));

        assertThat(a.get()).isEqualTo(new Item("a", 3));
        assertThat(a.valueProperty().get()).isEqualTo(new Item("a", 3));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.rows().getFirst().get().key()).isEqualTo("b");
        assertThat(changes).containsExactly("added", "added", "updated", "removed");

        list.put(new Item("c", 4));
        list.remove("b");
        list.remove("nothing");
        assertThat(list.rows()).extracting(r -> r.get().key()).containsExactly("c");
    }
}
