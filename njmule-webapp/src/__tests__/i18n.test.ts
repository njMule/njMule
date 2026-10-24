// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { bestLanguage, format } from '../i18n';

describe('i18n', () => {
    let pattern = '';
    let shown = '';
    let wanted: string[] = [];

    it('should fill in the placeholders in their order', () => {
        givenThePattern('%s of %s, %%');
        whenItIsFilledWith('1', '2');
        thenItReads('1 of 2, %');
    });

    it('should fill in numbered placeholders where they stand', () => {
        givenThePattern('%2$s before %1$s');
        whenItIsFilledWith('a', 'b');
        thenItReads('b before a');
    });

    it('should round decimals as the pattern asks', () => {
        givenThePattern('%.1f%%');
        whenItIsFilledWith(12.34);
        thenItReads('12.3%');
    });

    it('should choose the regional variant of a language the browser names alone', () => {
        givenTheBrowserLanguages('pt', 'en');
        whenTheBestLanguageIsChosen();
        thenItReads('pt-BR');
    });

    it('should fall back to English for a language without translation', () => {
        givenTheBrowserLanguages('xx-YY');
        whenTheBestLanguageIsChosen();
        thenItReads('en');
    });

    function givenThePattern(text: string): void {
        pattern = text;
    }

    function givenTheBrowserLanguages(...tags: string[]): void {
        wanted = tags;
    }

    function whenItIsFilledWith(...args: unknown[]): void {
        shown = format(pattern, args);
    }

    function whenTheBestLanguageIsChosen(): void {
        shown = bestLanguage(wanted);
    }

    function thenItReads(text: string): void {
        expect(shown).toBe(text);
    }
});
