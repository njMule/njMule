// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { type ColumnSpan, dragColumn, fitColumns, MAX_WIDTH } from '../ui/column-widths';

describe('column widths', () => {
    let columns: ColumnSpan[] = [];
    let widths: number[] = [];

    it('should share a large growth among all the columns, as the desktop app lays out its downloads', () => {
        givenColumnsAtTheirPreferredWidths([220, 94, 120, 90, 70, 128, 130]);
        whenTheyFill(933);
        thenTheWidthsAre([231, 106, 132, 101, 82, 139, 142]);
    });

    it('should give a small growth to the narrowest columns first', () => {
        givenColumnsAtTheirPreferredWidths([100, 40, 60]);
        whenTheyFill(210);
        thenTheWidthsAre([100, 50, 60]);
    });

    it('should shrink the columns towards their least widths in proportion to what each can give', () => {
        givenColumnsAtTheirPreferredWidths([300, 100, 100], 50);
        whenTheyFill(400);
        thenTheWidthsAre([229, 85, 86]);
    });

    it('should keep the columns at their least widths when even those do not fit', () => {
        givenColumnsAtTheirPreferredWidths([100, 100], 60);
        whenTheyFill(100);
        thenTheWidthsAre([60, 60]);
    });

    it('should take a dragged column growth from the last column first', () => {
        givenColumnsAtTheirPreferredWidths([100, 100, 100], 30);
        whenColumnIsDraggedTo(0, 150);
        thenTheWidthsAre([150, 100, 50]);
    });

    it('should take from the column before the last once the last is at its least width', () => {
        givenColumnsAtTheirPreferredWidths([100, 100, 100], 30);
        whenColumnIsDraggedTo(0, 200);
        thenTheWidthsAre([200, 70, 30]);
    });

    it('should give a dragged column shrink to the last column', () => {
        givenColumnsAtTheirPreferredWidths([100, 100, 100], 30);
        whenColumnIsDraggedTo(1, 60);
        thenTheWidthsAre([100, 60, 140]);
    });

    it('should leave the last column as it is when it is dragged', () => {
        givenColumnsAtTheirPreferredWidths([100, 100, 100], 30);
        whenColumnIsDraggedTo(2, 150);
        thenTheWidthsAre([100, 100, 100]);
    });

    function givenColumnsAtTheirPreferredWidths(prefs: number[], min = 24): void {
        columns = prefs.map((pref) => ({ size: pref, pref, min, max: MAX_WIDTH }));
    }

    function whenTheyFill(target: number): void {
        widths = fitColumns(columns, target);
    }

    function whenColumnIsDraggedTo(index: number, wanted: number): void {
        widths = dragColumn(columns, index, wanted);
    }

    function thenTheWidthsAre(expected: number[]): void {
        expect(widths).toEqual(expected);
    }
});
