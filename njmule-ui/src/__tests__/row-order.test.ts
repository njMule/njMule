// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { inOrder, movedRows, reordered } from '../ui/row-order';

describe('row order', () => {
    let ids: string[] = [];
    let result: string[] = [];

    it('should put a row dragged down after the row it is dropped on', () => {
        givenTheRows(['a', 'b', 'c', 'd']);
        whenMoved(['a'], 'c', true);
        thenTheOrderIs(['b', 'c', 'a', 'd']);
    });

    it('should put a row dragged up before the row it is dropped on', () => {
        givenTheRows(['a', 'b', 'c', 'd']);
        whenMoved(['d'], 'b', false);
        thenTheOrderIs(['a', 'd', 'b', 'c']);
    });

    it('should keep the rows dragged together in their order', () => {
        givenTheRows(['a', 'b', 'c', 'd', 'e']);
        whenMoved(['d', 'a'], 'b', true);
        thenTheOrderIs(['b', 'a', 'd', 'c', 'e']);
    });

    it('should leave the order as it is when dropped on one of the rows dragged', () => {
        givenTheRows(['a', 'b', 'c']);
        whenMoved(['a', 'b'], 'b', true);
        thenTheOrderIs(['a', 'b', 'c']);
    });

    it('should keep the places of the rows not shown when those shown are reordered', () => {
        givenTheRows(['a', 'x', 'b', 'y', 'c']);
        whenReorderedAsShown(['c', 'a', 'b']);
        thenTheOrderIs(['c', 'x', 'a', 'y', 'b']);
    });

    it('should list the items named by the order first, then the others as they come', () => {
        givenTheRows(['n1', 'b', 'n2', 'a']);
        whenPutInOrder(['a', 'b', 'gone']);
        thenTheOrderIs(['a', 'b', 'n1', 'n2']);
    });

    function givenTheRows(rows: string[]): void {
        ids = rows;
    }

    function whenMoved(moving: string[], target: string, after: boolean): void {
        result = movedRows(ids, moving, target, after);
    }

    function whenReorderedAsShown(shown: string[]): void {
        result = reordered(ids, shown);
    }

    function whenPutInOrder(order: string[]): void {
        result = inOrder(ids, order, (id) => id);
    }

    function thenTheOrderIs(expected: string[]): void {
        expect(result).toEqual(expected);
    }
});
