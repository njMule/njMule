// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TableInstance } from 'element-plus';
import { nextTick, watch } from 'vue';

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
