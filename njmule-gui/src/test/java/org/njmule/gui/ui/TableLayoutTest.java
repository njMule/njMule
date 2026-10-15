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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.njmule.gui.FxTesting;

class TableLayoutTest {

    private TableView<String> table;
    private List<TableColumn<String, ?>> columns;

    // Run alone, the class would otherwise find the toolkit not started.
    @BeforeAll
    static void toolkit() throws Exception {
        FxTesting.start();
    }

    @Test
    void shouldBringTheColumnsBackInTheirOrderWidthShownStateAndSort() throws Exception {
        givenATableOfFourColumns();

        whenTheLayoutIsRestored("2:150:1;0:90:0;bad;9:10:1;1:x:1", "2:D;7:A");

        thenTheColumnsAre("c", "a", "b", "d");
        thenTheWidthOfColumnIs(2, 150, "as saved");
        thenColumnIsShown(0, false);
        thenTheWidthOfColumnIs(1, 80, "an unreadable width keeps the built one");
        thenTheTableIsSortedBy(2, TableColumn.SortType.DESCENDING);
        thenTheSavedColumnsStartWithContainAndEndWith("2:", ";0:", ":1");
        thenTheSavedSortIs("2:D");
    }

    private void givenATableOfFourColumns() throws Exception {
        this.table = FxTesting.onFx(TableLayoutTest::table);
        this.columns = List.copyOf(this.table.getColumns());
    }

    private void whenTheLayoutIsRestored(String layout, String sort) throws Exception {
        FxTesting.onFx(() -> TableLayout.restore(this.table, this.columns, layout, sort));
    }

    private void thenTheColumnsAre(String... titles) throws Exception {
        assertThat(FxTesting.onFx(() -> List.copyOf(this.table.getColumns())))
                .extracting(TableColumn::getText)
                .containsExactly(titles);
    }

    private void thenTheWidthOfColumnIs(int column, double width, String why) throws Exception {
        assertThat(FxTesting.onFx(() -> this.columns.get(column).getPrefWidth()))
                .as(why)
                .isEqualTo(width);
    }

    private void thenColumnIsShown(int column, boolean shown) throws Exception {
        assertThat(FxTesting.onFx(() -> this.columns.get(column).isVisible())).isEqualTo(shown);
    }

    private void thenTheTableIsSortedBy(int column, TableColumn.SortType type) throws Exception {
        assertThat(FxTesting.onFx(() -> List.copyOf(this.table.getSortOrder())))
                .containsExactly(this.columns.get(column));
        assertThat(FxTesting.onFx(() -> this.columns.get(column).getSortType())).isEqualTo(type);
    }

    private void thenTheSavedColumnsStartWithContainAndEndWith(String start, String part, String end) throws Exception {
        assertThat(FxTesting.onFx(() -> TableLayout.columnsOf(this.table)))
                .startsWith(start)
                .contains(part)
                .endsWith(end);
    }

    private void thenTheSavedSortIs(String sort) throws Exception {
        assertThat(FxTesting.onFx(() -> TableLayout.sortOf(this.table))).isEqualTo(sort);
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
