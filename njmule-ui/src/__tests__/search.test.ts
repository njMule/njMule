// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    automaticScope,
    type ColumnOrder,
    type ColumnSort,
    completePercent,
    completeness,
    expression,
    type Filters,
    filterCount,
    isMedia,
    kindOrder,
    lowestMaxSize,
    MAX_SIZE_MB,
    NO_FILTERS,
    optionalExtension,
    optionalNumber,
    type Known,
    knownOf,
    merged,
    type Ours,
    type Result,
    type ResultRow,
    resultRows,
    rowOrder,
    SortMemory,
    tone,
    type Tone,
    words,
} from '../search';

describe('the search filters', () => {
    let filters: Filters = { ...NO_FILTERS };
    let made = '';
    let counted = 0;

    it('should add the sizes asked for to the expression', () => {
        givenTheFilters({ minMb: '10', maxMb: '700' });
        whenTheExpressionIsMade('debian netinst');
        thenTheExpressionIs('debian netinst @size>=10m @size<=700m');
    });

    it('should leave out a size field typed in and emptied again', () => {
        givenTheFilters({ minMb: '' });
        whenTheExpressionIsMade('debian netinst');
        thenTheExpressionIs('debian netinst');
    });

    it('should add the type and the extension without its dot', () => {
        givenTheFilters({ type: 'cd', extension: '.iso' });
        whenTheExpressionIsMade('debian');
        thenTheExpressionIs('debian @type=cd @ext=iso');
    });

    it('should count each field typed in among the filters asked for', () => {
        givenTheFilters({ type: 'video', availability: '0', extension: 'iso' });
        whenTheFiltersAreCounted();
        thenTheCountIs(3);
    });

    function givenTheFilters(asked: Partial<Filters>): void {
        filters = { ...NO_FILTERS, ...asked };
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

describe('the fields of the filters', () => {
    let valid = false;

    it('should take an empty number field', () => {
        whenTheNumberIsChecked('', 1, 10);
        thenItIsValid(true);
    });

    it('should refuse a number out of its range', () => {
        whenTheNumberIsChecked('0', 1, 10);
        thenItIsValid(false);
    });

    it('should keep the maximum size from going below the minimum', () => {
        whenTheNumberIsChecked('5', lowestMaxSize('8'), MAX_SIZE_MB);
        thenItIsValid(false);
    });

    it('should take an extension with its dot', () => {
        whenTheExtensionIsChecked('.mkv');
        thenItIsValid(true);
    });

    it('should refuse an extension with two dots', () => {
        whenTheExtensionIsChecked('tar.gz');
        thenItIsValid(false);
    });

    function whenTheNumberIsChecked(text: string, min: number, max: number): void {
        valid = optionalNumber(text, min, max);
    }

    function whenTheExtensionIsChecked(text: string): void {
        valid = optionalExtension(text);
    }

    function thenItIsValid(expected: boolean): void {
        expect(valid).toBe(expected);
    }
});

describe('the network of an automatic search', () => {
    const SMALL = { users: 100, files: 1000, static: false };
    const BIG = { users: 50_000, files: 6_000_000, static: false };
    let scope: string | undefined;

    it('should go to Kad when both networks are connected', () => {
        whenTheScopeIsPicked(true, true, SMALL, 100);
        thenItGoesTo('kad');
    });

    it('should stay on a big server of a short list', () => {
        whenTheScopeIsPicked(true, true, BIG, 10);
        thenItGoesTo('server');
    });

    it('should stay on a static server', () => {
        whenTheScopeIsPicked(true, true, { ...SMALL, static: true }, 100);
        thenItGoesTo('server');
    });

    it('should go nowhere with neither network', () => {
        whenTheScopeIsPicked(false, false, null, 100);
        thenItGoesTo(undefined);
    });

    function whenTheScopeIsPicked(
        server: boolean,
        kad: boolean,
        info: { users: number; files: number; static: boolean } | null,
        known: number,
    ): void {
        scope = automaticScope(server, kad, info, known);
    }

    function thenItGoesTo(expected: string | undefined): void {
        expect(scope).toBe(expected);
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

describe('the rows of the names of a file', () => {
    const NAMES = [
        { name: 'one.iso', sources: 5, completeSources: 1 },
        { name: 'two.iso', sources: 2, completeSources: null },
    ];
    let results: Result[] = [];
    let rows: ResultRow[] = [];

    it('should show only the files while none is open', () => {
        givenTheResults(result('A', { names: NAMES }), result('B'));
        whenTheRowsAreMade();
        thenTheRowsAre('A.iso', 'B.iso');
    });

    it('should show the names of an open file under it', () => {
        givenTheResults(result('A', { names: NAMES }), result('B'));
        whenTheRowsAreMade('A');
        thenTheRowsAre('A.iso', 'one.iso', 'two.iso', 'B.iso');
    });

    it('should give a name its own sources', () => {
        givenTheResults(result('A', { names: NAMES }));
        whenTheRowsAreMade('A');
        thenTheSourcesAre(1, 5, 2);
    });

    it('should not open a file found under one name', () => {
        givenTheResults(result('A', { names: [NAMES[0]!] }));
        whenTheRowsAreMade('A');
        thenTheRowsAre('A.iso');
    });

    function givenTheResults(...given: Result[]): void {
        results = given;
    }

    function whenTheRowsAreMade(...open: string[]): void {
        rows = resultRows(results, new Set(open.map((h) => h.padEnd(32, '0'))));
    }

    function thenTheRowsAre(...names: string[]): void {
        expect(rows.map((r) => r.result.name)).toEqual(names);
    }

    function thenTheSourcesAre(...sources: number[]): void {
        expect(rows.map((r) => r.result.sources)).toEqual(sources);
    }
});

describe('the order of sorted results', () => {
    const ORDERS: Record<string, ColumnOrder> = {
        name: { compare: (a, b) => a.name.localeCompare(b.name) },
        sources: { compare: (a, b) => a.sources - b.sources },
        size: { compare: (a, b) => a.size - b.size },
        artist: {
            compare: (a, b) => (a.media?.artist ?? '').localeCompare(b.media?.artist ?? ''),
            missing: (r) => !r.media?.artist,
        },
    };
    const memory = new SortMemory();
    let rows: ResultRow[] = [];
    let sorting: ColumnSort[] = [];

    it('should put spam last whichever way a column sorts', () => {
        givenTheRows(result('A', { sources: 1, spam: true }), result('B', { sources: 5 }), result('C', { sources: 3 }));
        whenSortedBy({ id: 'sources', desc: true });
        thenTheOrderIs('B', 'C', 'A');
    });

    it('should put the values a result lacks last whichever way a column sorts', () => {
        givenTheRows(result('A'), result('B', { media: media('b') }), result('C', { media: media('c') }));
        whenSortedBy({ id: 'artist', desc: true });
        thenTheOrderIs('C', 'B', 'A');
    });

    it('should keep the order the results came in where the column ties', () => {
        givenTheRows(result('C'), result('A'), result('B'));
        whenSortedBy({ id: 'size', desc: false });
        thenTheOrderIs('C', 'A', 'B');
    });

    it('should break ties by the column chosen before', () => {
        givenTheRows(result('A', { sources: 1 }), result('B', { sources: 2 }), result('C', { sources: 3 }));
        givenSortedBefore({ id: 'sources', desc: true });
        whenSortedBy({ id: 'size', desc: false });
        thenTheOrderIs('C', 'B', 'A');
    });

    it('should keep the names of a file under it, most sources first', () => {
        givenTheRows(
            result('A', {
                sources: 1,
                names: [
                    { name: 'few', sources: 1, completeSources: 0 },
                    { name: 'many', sources: 9, completeSources: 0 },
                ],
            }),
            result('B', { sources: 2 }),
        );
        givenTheNamesOpen('A');
        whenSortedBy({ id: 'sources', desc: false });
        thenTheNamesAre('A.iso', 'many', 'few', 'B.iso');
    });

    function media(artist: string): Result['media'] {
        return { artist, album: '', title: '', length: 0, bitrate: 0, codec: '' };
    }

    function givenTheRows(...results: Result[]): void {
        rows = resultRows(results);
    }

    function givenTheNamesOpen(hash: string): void {
        rows = resultRows(
            rows.map((r) => r.file),
            new Set([hash.padEnd(32, '0')]),
        );
    }

    function givenSortedBefore(...before: ColumnSort[]): void {
        memory.earlier(before);
    }

    function whenSortedBy(...now: ColumnSort[]): void {
        sorting = now;
        rows = [...rows].sort(rowOrder(sorting, memory.earlier(sorting), ORDERS));
    }

    function thenTheOrderIs(...hashes: string[]): void {
        expect(rows.map((r) => r.result.hash[0])).toEqual(hashes);
    }

    function thenTheNamesAre(...names: string[]): void {
        expect(rows.map((r) => r.result.name)).toEqual(names);
    }
});

describe('the order of file types', () => {
    let order = 0;

    it('should order by type first', () => {
        whenTheKindsAreCompared({ type: 'Audio', name: 'z.mp3' }, { type: 'Video', name: 'a.avi' });
        thenTheFirstComes(true);
    });

    it('should put a file without extension after one with', () => {
        whenTheKindsAreCompared({ type: 'Pro', name: 'setup' }, { type: 'Pro', name: 'setup.exe' });
        thenTheFirstComes(false);
    });

    it('should order extensions in any case', () => {
        whenTheKindsAreCompared({ type: 'Pro', name: 'a.ZIP' }, { type: 'Pro', name: 'b.rar' });
        thenTheFirstComes(false);
    });

    function whenTheKindsAreCompared(a: Pick<Result, 'type' | 'name'>, b: Pick<Result, 'type' | 'name'>): void {
        order = kindOrder(a, b);
    }

    function thenTheFirstComes(first: boolean): void {
        expect(order < 0).toBe(first);
    }
});
