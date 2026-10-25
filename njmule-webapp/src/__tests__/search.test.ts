// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { completeness, expression, shade, tone, words, type Filters } from '../search';

describe('search', () => {
    let filters: Filters = { type: '', extension: '' };
    let shown = '';

    it('should add the filters to the words as attributes', () => {
        givenTheFilters({ type: 'video', minMb: 100, maxMb: 700, availability: 2, complete: 1, extension: '.mkv' });
        whenTheExpressionIsMade('night');
        thenItReads('night @type=video @size>=100m @size<=700m @sources>=2 @complete>=1 @ext=mkv');
    });

    it('should leave the words alone without filters', () => {
        givenTheFilters({ type: '', extension: ' ' });
        whenTheExpressionIsMade('  debian -netinst ');
        thenItReads('debian -netinst');
    });

    it('should title a search with its words only', () => {
        whenTheWordsAreTaken('night @type=video @ext=mkv');
        thenItReads('night');
    });

    it('should not know the complete sources Kad does not tell', () => {
        whenTheCompletenessIsShown(10, null);
        thenItReads('?');
    });

    it('should give the complete sources as a share of all', () => {
        whenTheCompletenessIsShown(8, 2);
        thenItReads('25%');
    });

    function givenTheFilters(value: Filters): void {
        filters = value;
    }

    function whenTheExpressionIsMade(text: string): void {
        shown = expression(text, filters);
    }

    function whenTheWordsAreTaken(text: string): void {
        shown = words(text);
    }

    function whenTheCompletenessIsShown(sources: number, completeSources: number | null): void {
        shown = completeness({ sources, completeSources });
    }

    function thenItReads(text: string): void {
        expect(shown).toBe(text);
    }
});

describe('the colour of a result', () => {
    let tones: string[] = [];
    let shades: number[] = [];

    it('should tell a file being downloaded or shared as ours, even without a mark of it', () => {
        whenTheTonesAreTold([{ spam: false, known: 'shared' }, false], [{ spam: false }, true]);

        thenTheTonesAre('ours', 'ours');
    });

    it('should tell a file downloaded or cancelled before as known from before', () => {
        whenTheTonesAreTold(
            [{ spam: false, known: 'downloaded' }, false],
            [{ spam: false, known: 'cancelled' }, false],
        );

        thenTheTonesAre('before', 'before');
    });

    it('should tell spam before anything else', () => {
        whenTheTonesAreTold([{ spam: true, known: 'shared' }, true]);

        thenTheTonesAre('spam');
    });

    it('should shade a new file towards the accent by thirteenths of its sources past the first', () => {
        whenTheShadesAreTold(0, 1, 2, 13, 40);

        thenTheShadesAre(0, 0, 100 / 13, 1200 / 13, 1200 / 13);
    });

    function whenTheTonesAreTold(...results: [Parameters<typeof tone>[0], boolean][]): void {
        tones = results.map(([r, downloading]) => tone(r, downloading));
    }

    function whenTheShadesAreTold(...sources: number[]): void {
        shades = sources.map(shade);
    }

    function thenTheTonesAre(...expected: string[]): void {
        expect(tones).toEqual(expected);
    }

    function thenTheShadesAre(...expected: number[]): void {
        expect(shades).toEqual(expected);
    }
});
