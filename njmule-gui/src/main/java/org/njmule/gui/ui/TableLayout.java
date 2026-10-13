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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javafx.collections.ListChangeListener;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.njmule.gui.infra.GuiSettings;

/**
 * Remembers how a table's columns were left, as eMule's lists do: their order, widths, which ones show (from the column
 * menu) and the sort. Columns are known by their place when the table was built.
 */
public final class TableLayout {

    private static final Pattern ENTRIES = Pattern.compile(";");
    private static final Pattern FIELDS = Pattern.compile(":");
    private static final double MIN_WIDTH = 20;
    private static final double MAX_WIDTH = 2000;

    private TableLayout() {}

    /** Arranges {@code table} as it was left under {@code name}, offers the column menu, and keeps later changes. */
    public static <S> void remember(TableView<S> table, String name, GuiSettings settings) {
        List<TableColumn<S, ?>> columns = List.copyOf(table.getColumns());
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getId() == null) {
                columns.get(i).setId(Integer.toString(i));
            }
        }
        table.setTableMenuButtonVisible(true);
        restore(table, columns, settings.tableColumns(name), settings.tableSort(name));
        Runnable save = () -> {
            settings.setTableColumns(name, columnsOf(table));
            settings.setTableSort(name, sortOf(table));
        };
        table.getColumns().addListener((ListChangeListener<TableColumn<S, ?>>) c -> save.run());
        table.getSortOrder().addListener((ListChangeListener<TableColumn<S, ?>>) c -> save.run());
        for (TableColumn<S, ?> column : columns) {
            column.widthProperty().addListener((o, was, now) -> save.run());
            column.visibleProperty().addListener((o, was, now) -> save.run());
            column.sortTypeProperty().addListener((o, was, now) -> save.run());
        }
    }

    // "id:width:shown" for each column in its order; columns added since come after, as built.
    static <S> void restore(TableView<S> table, List<TableColumn<S, ?>> columns, String layout, String sort) {
        Map<String, TableColumn<S, ?>> byId = new LinkedHashMap<>();
        for (TableColumn<S, ?> column : columns) {
            byId.put(column.getId(), column);
        }
        Map<String, TableColumn<S, ?>> all = Map.copyOf(byId);
        List<TableColumn<S, ?>> ordered = new ArrayList<>();
        for (String entry : ENTRIES.splitAsStream(layout).toList()) {
            List<String> fields = FIELDS.splitAsStream(entry).toList();
            if (fields.size() != 3) {
                continue;
            }
            TableColumn<S, ?> column = byId.remove(fields.get(0));
            if (column == null) {
                continue;
            }
            try {
                double width = Double.parseDouble(fields.get(1));
                if (width >= MIN_WIDTH && width <= MAX_WIDTH) {
                    column.setPrefWidth(width);
                }
            } catch (NumberFormatException e) {
                // A width edited by hand: the built one stays.
            }
            column.setVisible(!"0".equals(fields.get(2)));
            ordered.add(column);
        }
        ordered.addAll(byId.values());
        table.getColumns().setAll(ordered);
        List<TableColumn<S, ?>> sorted = new ArrayList<>();
        for (String entry : ENTRIES.splitAsStream(sort).toList()) {
            List<String> fields = FIELDS.splitAsStream(entry).toList();
            TableColumn<S, ?> column = fields.size() == 2 ? all.get(fields.get(0)) : null;
            if (column != null && !sorted.contains(column)) {
                column.setSortType(
                        "D".equals(fields.get(1)) ? TableColumn.SortType.DESCENDING : TableColumn.SortType.ASCENDING);
                sorted.add(column);
            }
        }
        table.getSortOrder().setAll(sorted);
    }

    static <S> String columnsOf(TableView<S> table) {
        List<String> entries = new ArrayList<>();
        for (TableColumn<S, ?> column : table.getColumns()) {
            entries.add(column.getId() + ":" + Math.round(column.getWidth()) + ":" + (column.isVisible() ? "1" : "0"));
        }
        return String.join(";", entries);
    }

    static <S> String sortOf(TableView<S> table) {
        List<String> entries = new ArrayList<>();
        for (TableColumn<S, ?> column : table.getSortOrder()) {
            entries.add(column.getId() + ":" + (column.getSortType() == TableColumn.SortType.DESCENDING ? "D" : "A"));
        }
        return String.join(";", entries);
    }
}
