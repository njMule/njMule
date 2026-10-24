// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { completeness, expression, words, type Filters } from '../search';

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
