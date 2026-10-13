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
package org.njmule.gui.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.Test;
import org.njmule.gui.FxTesting;

class TableLayoutTest {

    @Test
    void columnsComeBackInTheirOrderWidthShownStateAndSort() throws Exception {
        FxTesting.onFx(() -> {
            TableView<String> table = table();
            List<TableColumn<String, ?>> columns = List.copyOf(table.getColumns());

            TableLayout.restore(table, columns, "2:150:1;0:90:0;bad;9:10:1;1:x:1", "2:D;7:A");

            assertThat(table.getColumns()).extracting(TableColumn::getText).containsExactly("c", "a", "b", "d");
            assertThat(columns.get(2).getPrefWidth()).isEqualTo(150);
            assertThat(columns.get(0).isVisible()).isFalse();
            assertThat(columns.get(1).getPrefWidth())
                    .as("an unreadable width keeps the built one")
                    .isEqualTo(80);
            assertThat(table.getSortOrder()).containsExactly(columns.get(2));
            assertThat(columns.get(2).getSortType()).isEqualTo(TableColumn.SortType.DESCENDING);
            assertThat(TableLayout.columnsOf(table))
                    .startsWith("2:")
                    .contains(";0:")
                    .endsWith(":1");
            assertThat(TableLayout.sortOf(table)).isEqualTo("2:D");
            return null;
        });
    }

    private static TableView<String> table() {
        TableView<String> table = new TableView<>();
        int i = 0;
        for (String title : List.of("a", "b", "c", "d")) {
            TableColumn<String, String> column = new TableColumn<>(title);
            column.setPrefWidth(80);
            column.setId(Integer.toString(i++));
            table.getColumns().add(column);
        }
        return table;
    }
}
