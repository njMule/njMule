// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TableInstance } from 'element-plus';
import { nextTick, onMounted, watch } from 'vue';

// A row of a table, as Element Plus types it.
type Row = Record<PropertyKey, any>;

/** The whole text of a cell cut short shows after a moment under the pointer, as in the desktop app, not at once. */
export const CUT_HINT = { showAfter: 700 };

// What a click on a row leaves to the control it lands on.
const CONTROLS = 'button, a, input, label, .el-checkbox, .el-dropdown, .el-switch, .el-select';

/**
 * The rows chosen in a table stand out, and a click on a row chooses it alone; with Ctrl or Cmd it is added or taken
 * off. The choice lasts as the table's {@code rows} are read again, told apart by {@code key}: its selection column
 * reserves the selection, and the chosen rows follow, the new values of each row and without those gone.
 */
export function chooser<T extends Row>(
    table: { readonly value: TableInstance | null },
    chosen: { value: T[] },
    key: (row: T) => string,
    rows: () => T[],
) {
    watch(rows, (now) =>
        nextTick(() => {
            const t = table.value;
            if (t === null) {
                return;
            }
            const present = new Map(now.map((r) => [key(r), r]));
            for (const r of t.getSelectionRows() as T[]) {
                if (!present.has(key(r))) {
                    t.toggleRowSelection(r, false);
                }
            }
            chosen.value = (t.getSelectionRows() as T[]).map((r) => present.get(key(r)) ?? r);
        }),
    );
    return {
        rowClass: ({ row }: { row: T }): string => (chosen.value.some((c) => key(c) === key(row)) ? 'chosen' : ''),
        click: (row: T, column: { type?: string } | null, event: MouseEvent): void => {
            const t = table.value;
            const target = event.target instanceof Element ? event.target : null;
            if (t === null || column?.type === 'selection' || target?.closest(CONTROLS)) {
                return;
            }
            if (event.ctrlKey || event.metaKey) {
                t.toggleRowSelection(row);
                return;
            }
            t.clearSelection();
            t.toggleRowSelection(row, true);
        },
    };
}

// What a table keeps of a column, as Element Plus does it.
interface Column {
    property?: string;
    label?: string;
    type?: string;
    width?: number | string;
    realWidth?: number | null;
}

// The table's own store of its columns, which its resizing uses.
interface Columns {
    store: { states: { columns: { value: Column[] } }; scheduleLayout: (heights: boolean, widths: boolean) => void };
}

function columnKey(column: Column): string {
    return column.property || column.label || '';
}

/**
 * The widths the user gave the columns of the table {@code name} by dragging their edges, kept in this browser and given
 * back as the table comes again, also to columns shown later; the returned handler takes each new width.
 */
export function rememberedWidths(table: { readonly value: TableInstance | null }, name: string) {
    const key = `njmule.columns.${name}`;
    const read = (): Record<string, number> => {
        try {
            return JSON.parse(localStorage.getItem(key) ?? '{}') as Record<string, number>;
        } catch {
            return {};
        }
    };
    const columns = (): Column[] => (table.value as unknown as Columns | null)?.store.states.columns.value ?? [];
    const restore = (): void => {
        const saved = read();
        let changed = false;
        for (const column of columns()) {
            const width = saved[columnKey(column)];
            if (column.type !== 'selection' && width !== undefined && column.realWidth !== width) {
                column.width = column.realWidth = width;
                changed = true;
            }
        }
        if (changed) {
            (table.value as unknown as Columns).store.scheduleLayout(false, true);
        }
    };
    onMounted(() => void nextTick(restore));
    watch(
        () => columns().length,
        () => void nextTick(restore),
    );
    return (width: number, _old: number, column: Column): void => {
        const saved = read();
        saved[columnKey(column)] = Math.round(width);
        try {
            localStorage.setItem(key, JSON.stringify(saved));
        } catch {
            // Private windows may keep nothing: the width lasts as long as the page.
        }
    };
}
