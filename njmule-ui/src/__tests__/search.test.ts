// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { expression, type Filters, filterCount, merged, type Result, words } from '../search';

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

function result(hash: string, fields: Partial<Result> = {}): Result {
    return {
        hash: hash.padEnd(32, '0'),
        name: `${hash}.iso`,
        size: 1000,
        sources: 1,
        completeSources: 0,
        type: null,
        spamRating: 0,
        spam: false,
        names: [],
        ...fields,
    };
}

describe('the order of the results', () => {
    let before: Result[] = [];
    let after: Result[] = [];

    it('should keep the results in the order they came', () => {
        givenTheResults(result('A'), result('B'));
        whenTheEngineListsThem(result('B', { sources: 9 }), result('C', { sources: 5 }), result('A'));
        thenTheOrderIs('A', 'B', 'C');
    });

    it('should take the new values of the results known', () => {
        givenTheResults(result('A'));
        whenTheEngineListsThem(result('A', { sources: 7 }));
        thenTheSourcesAre(7);
    });

    function givenTheResults(...results: Result[]): void {
        before = results;
    }

    function whenTheEngineListsThem(...results: Result[]): void {
        after = merged(before, results);
    }

    function thenTheOrderIs(...hashes: string[]): void {
        expect(after.map((r) => r.hash[0])).toEqual(hashes);
    }

    function thenTheSourcesAre(...sources: number[]): void {
        expect(after.map((r) => r.sources)).toEqual(sources);
    }
});

describe('the title of a search', () => {
    let title = '';

    it('should leave out the attributes of the expression', () => {
        whenTheTitleIsMade('debian  netinst @size>=10m @type=cd');
        thenTheTitleIs('debian  netinst');
    });

    function whenTheTitleIsMade(expression: string): void {
        title = words(expression);
    }

    function thenTheTitleIs(expected: string): void {
        expect(title).toBe(expected);
    }
});
