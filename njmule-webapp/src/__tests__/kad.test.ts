// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { BARS, distanceBars } from '../kad';

describe('kad', () => {
    let bars: number[] = [];

    it('should put the farthest contact in the last bar and the closest in the first', () => {
        whenCounted(['F'.padEnd(32, '0'), '0'.repeat(31) + '1']);

        thenTheBarHolds(BARS - 1, 1);
        thenTheBarHolds(0, 1);
    });

    it('should count a contact sharing one leading bit in the bar before the last', () => {
        whenCounted(['4'.padEnd(32, '0'), 'not an id']);

        thenTheBarHolds(BARS - 2, 1);
        thenTheTotalIs(1);
    });

    function whenCounted(distances: string[]): void {
        bars = distanceBars(distances.map((distance) => ({ distance })));
    }

    function thenTheBarHolds(bar: number, count: number): void {
        expect(bars[bar]).toBe(count);
    }

    function thenTheTotalIs(total: number): void {
        expect(bars.reduce((a, b) => a + b, 0)).toBe(total);
    }
});
