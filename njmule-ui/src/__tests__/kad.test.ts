// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { addressNumber, BARS, distanceBars } from '../kad';

describe('kad', () => {
    let bars: number[] = [];
    let number = '';

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

    it('should give an IPv4 address as the number Kad uses for its id', () => {
        whenNumbered('198.51.100.23');

        thenTheNumberIs('3325256727');
    });

    it('should give no number for an address that is not IPv4', () => {
        whenNumbered('2001:db8::1');

        thenTheNumberIs('');
    });

    function whenCounted(distances: string[]): void {
        bars = distanceBars(distances.map((distance) => ({ distance })));
    }

    function thenTheBarHolds(bar: number, count: number): void {
        expect(bars[bar]).toBe(count);
    }

    function whenNumbered(address: string): void {
        number = addressNumber(address);
    }

    function thenTheNumberIs(expected: string): void {
        expect(number).toBe(expected);
    }

    function thenTheTotalIs(total: number): void {
        expect(bars.reduce((a, b) => a + b, 0)).toBe(total);
    }
});
