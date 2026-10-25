// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TableInstance } from 'element-plus';
import { describe, expect, it } from 'vitest';
import { nextTick, ref } from 'vue';
import { chooser } from '../tables';

interface Row extends Record<string, unknown> {
    hash: string;
}

describe('tables', () => {
    const film: Row = { hash: 'F1' };
    const song: Row = { hash: 'S1' };
    let chosen: Row[] = [];
    let calls: string[] = [];
    // A table that notes how its choice is changed, and passes it on to the chosen rows as the real one does.
    const table = {
        clearSelection: () => {
            calls.push('clear');
            chosen = [];
        },
        toggleRowSelection: (row: Row, selected?: boolean) => {
            calls.push(`toggle ${row.hash} ${String(selected)}`);
            const has = chosen.some((c) => c.hash === row.hash);
            chosen =
                (selected ?? !has)
                    ? [...chosen.filter((c) => c.hash !== row.hash), row]
                    : chosen.filter((c) => c.hash !== row.hash);
        },
        getSelectionRows: () => chosen,
    } as unknown as TableInstance;
    const rows = ref<Row[]>([film, song]);
    const choice = chooser(
        { value: table },
        {
            get value() {
                return chosen;
            },
            set value(now: Row[]) {
                chosen = now;
            },
        },
        (r: Row) => r.hash,
        () => rows.value,
    );

    it('should mark a chosen row, told apart by its key as the data is read again', () => {
        givenChosen(film);

        const marks = whenTheRowsAreMarked({ hash: 'F1' }, song);

        expect(marks).toEqual(['chosen', '']);
    });

    it('should choose a clicked row alone', () => {
        givenChosen(song);

        whenClicked(film, {});

        expect(chosen).toEqual([film]);
        expect(calls).toEqual(['clear', 'toggle F1 true']);
    });

    it('should add a row clicked with Ctrl to those chosen', () => {
        givenChosen(song);

        whenClicked(film, { ctrlKey: true });

        expect(chosen).toEqual([song, film]);
    });

    it('should keep the chosen rows as the data is read again, with their new values, without those gone', async () => {
        givenChosen(film, song);

        await whenTheRowsAreReadAgain({ hash: 'F1', name: 'new' });

        expect(chosen).toEqual([{ hash: 'F1', name: 'new' }]);
    });

    it('should leave a click on a button of the row to the button', () => {
        givenChosen(song);

        whenClicked(film, {}, document.createElement('button'));

        expect(chosen).toEqual([song]);
        expect(calls).toEqual([]);
    });

    it('should leave a click on the box of the row to the box', () => {
        givenChosen(song);

        whenClicked(film, {}, document.createElement('td'), 'selection');

        expect(calls).toEqual([]);
    });

    function givenChosen(...rows: Row[]): void {
        chosen = rows;
        calls = [];
    }

    async function whenTheRowsAreReadAgain(...now: Row[]): Promise<void> {
        rows.value = now;
        await nextTick();
        await nextTick();
    }

    function whenTheRowsAreMarked(...rows: Row[]): string[] {
        return rows.map((row) => choice.rowClass({ row }));
    }

    function whenClicked(row: Row, keys: MouseEventInit, target?: Element, type?: string): void {
        const event = new MouseEvent('click', keys);
        Object.defineProperty(event, 'target', { value: target ?? document.createElement('td') });
        choice.click(row, { type }, event);
    }
});
