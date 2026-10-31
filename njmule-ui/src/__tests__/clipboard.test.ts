// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it } from 'vitest';
import { askedNames, copiedHere, copiedLinks } from '../clipboard';
import type { FileLink } from '../links';

const LINK = 'ed2k://|file|a.iso|1000|0123456789ABCDEF0123456789ABCDEF|/';
const OTHER = 'ed2k://|file|b.iso|2000|FEDCBA9876543210FEDCBA9876543210|/';

describe('eD2K links copied in another program', () => {
    let links: FileLink[] = [];
    let names: string[] = [];

    beforeEach(() => {
        // Something else was copied before each case.
        copiedLinks('');
    });

    it('should offer the links of a new text', () => {
        whenCopied(`${LINK}\n${OTHER}`);
        thenTheLinksAre(['a.iso', 'b.iso']);
    });

    it('should leave the links njMule copied itself', () => {
        givenNjMuleCopied(LINK);
        whenCopied(LINK);
        thenTheLinksAre([]);
    });

    it('should offer the links njMule copied once something else was copied', () => {
        givenNjMuleCopied(LINK);
        givenCopied('');
        whenCopied(LINK);
        thenTheLinksAre(['a.iso']);
    });

    it('should name ten files at most', () => {
        whenTheQuestionNames(12);
        thenTheNamesEndWith(11, '…');
    });

    function givenNjMuleCopied(text: string): void {
        copiedHere(text);
    }

    function givenCopied(text: string): void {
        copiedLinks(text);
    }

    function whenCopied(text: string): void {
        links = copiedLinks(text);
    }

    function whenTheQuestionNames(count: number): void {
        names = askedNames(
            Array.from({ length: count }, (_, i) => ({ link: '', name: `file ${i}`, size: 1, hash: String(i) })),
        );
    }

    function thenTheLinksAre(expected: string[]): void {
        expect(links.map((l) => l.name)).toEqual(expected);
    }

    function thenTheNamesEndWith(count: number, last: string): void {
        expect(names).toHaveLength(count);
        expect(names.at(-1)).toBe(last);
    }
});
