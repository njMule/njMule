// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { expression, type Filters, filterCount } from '../search';

describe('the search filters', () => {
    let filters: Filters = { type: '', extension: '' };
    let made = '';
    let counted = 0;

    it('should add the sizes asked for to the expression', () => {
        givenTheFilters({ minMb: 10, maxMb: 700 });
        whenTheExpressionIsMade('debian netinst');
        thenTheExpressionIs('debian netinst @size>=10m @size<=700m');
    });

    it('should leave out a size field typed in and emptied again', () => {
        givenTheFilters({ minMb: '' as unknown as number });
        whenTheExpressionIsMade('debian netinst');
        thenTheExpressionIs('debian netinst');
    });

    it('should not count a field emptied again among the filters asked for', () => {
        givenTheFilters({ availability: '' as unknown as number, extension: 'iso' });
        whenTheFiltersAreCounted();
        thenTheCountIs(1);
    });

    function givenTheFilters(asked: Partial<Filters>): void {
        filters = { type: '', extension: '', ...asked };
    }

    function whenTheExpressionIsMade(words: string): void {
        made = expression(words, filters);
    }

    function whenTheFiltersAreCounted(): void {
        counted = filterCount(filters);
    }

    function thenTheExpressionIs(expected: string): void {
        expect(made).toBe(expected);
    }

    function thenTheCountIs(expected: number): void {
        expect(counted).toBe(expected);
    }
});
