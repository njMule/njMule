// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    completePercent,
    completeness,
    expression,
    type Filters,
    filterCount,
    isMedia,
    type Known,
    knownOf,
    merged,
    type Ours,
    type Result,
    tone,
    type Tone,
    words,
} from '../search';

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

describe('the complete sources of a result', () => {
    let given = result('A');
    let shown = '';
    let percent = 0;

    it('should show the share of the sources with the whole file', () => {
        givenTheResult({ sources: 3, completeSources: 2 });
        whenItIsShown();
        thenItShows('66%', 66);
    });

    it('should tell a file of one part whole where the network does not count', () => {
        givenTheResult({ size: 9_000_000, completeSources: null });
        whenItIsShown();
        thenItShows('Yes', 100);
    });

    it('should not know a bigger file the network does not count', () => {
        givenTheResult({ size: 20_000_000, completeSources: null });
        whenItIsShown();
        thenItShows('?', -1);
    });

    it('should tell a file whole with complete sources but none counted', () => {
        givenTheResult({ sources: 0, completeSources: 1 });
        whenItIsShown();
        thenItShows('Yes', 0);
    });

    function givenTheResult(fields: Partial<Result>): void {
        given = result('A', fields);
    }

    function whenItIsShown(): void {
        shown = completeness(given, 'Yes');
        percent = completePercent(given);
    }

    function thenItShows(text: string, share: number): void {
        expect(shown).toBe(text);
        expect(percent).toBe(share);
    }
});

describe('what a result is to us', () => {
    const OURS: Ours = { downloading: new Set(['D'.padEnd(32, '0')]), shared: new Set(['S'.padEnd(32, '0')]) };
    let given = result('A');
    let state: Known | undefined;

    it('should tell a file being downloaded before its spam mark', () => {
        givenTheResult(result('D', { spam: true }));
        whenWhatItIsIsTold();
        thenItIs('downloading', 'ours');
    });

    it('should tell a shared file', () => {
        givenTheResult(result('S'));
        whenWhatItIsIsTold();
        thenItIs('shared', 'ours');
    });

    it('should tell a file downloaded before', () => {
        givenTheResult(result('A', { known: 'downloaded', spam: true }));
        whenWhatItIsIsTold();
        thenItIs('downloaded', 'known');
    });

    it('should tell spam', () => {
        givenTheResult(result('A', { spam: true }));
        whenWhatItIsIsTold();
        thenItIs('spam', 'spam');
    });

    it('should leave a new result unmarked', () => {
        givenTheResult(result('A'));
        whenWhatItIsIsTold();
        thenItIs(undefined, 'fresh');
    });

    function givenTheResult(r: Result): void {
        given = r;
    }

    function whenWhatItIsIsTold(): void {
        state = knownOf(given, OURS);
    }

    function thenItIs(expected: Known | undefined, colour: Tone): void {
        expect(state).toBe(expected);
        expect(tone(state)).toBe(colour);
    }
});

describe('the media columns', () => {
    let given = result('A');
    let media = false;

    it('should show for a result of the audio type', () => {
        givenTheResult({ type: 'Audio' });
        whenItIsChecked();
        thenItIsMedia(true);
    });

    it('should show for a result with media details', () => {
        givenTheResult({ type: 'Pro', media: { artist: 'x', album: '', title: '', length: 0, bitrate: 0, codec: '' } });
        whenItIsChecked();
        thenItIsMedia(true);
    });

    it('should not show for a program', () => {
        givenTheResult({ type: 'Pro' });
        whenItIsChecked();
        thenItIsMedia(false);
    });

    function givenTheResult(fields: Partial<Result>): void {
        given = result('A', fields);
    }

    function whenItIsChecked(): void {
        media = isMedia(given);
    }

    function thenItIsMedia(expected: boolean): void {
        expect(media).toBe(expected);
    }
});
