// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { keepLanguage, keptLanguage, language } from '../i18n';
import { switchLanguage } from '../language';

describe('a language chosen while the window shows', () => {
    const TEXTS: Record<string, Record<string, string>> = {
        en: { 'nj.tray.show': 'Show njMule' },
        it: { 'nj.tray.show': 'Mostra njMule' },
        de: { 'nj.tray.show': 'njMule zeigen' },
    };
    beforeEach(() => {
        keepLanguage('');
        vi.stubGlobal('fetch', async (url: string) => {
            const tag = /i18n\/(.+)\.json$/.exec(url)?.[1] ?? '';
            return new Response(JSON.stringify(TEXTS[tag] ?? {}), { status: TEXTS[tag] ? 200 : 404 });
        });
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('should speak it at once', async () => {
        await whenTheLanguageIsChosen('it', ['en-US']);
        thenTheWindowSpeaks('it');
    });

    it('should keep it for the next windows', async () => {
        await whenTheLanguageIsChosen('it', ['en-US']);
        thenTheLanguageKeptIs('it');
    });

    it('should speak the best of ours for the system when none is chosen', async () => {
        await whenTheLanguageIsChosen('', ['de-DE', 'en']);
        thenTheWindowSpeaks('de');
        thenTheLanguageKeptIs('');
    });

    async function whenTheLanguageIsChosen(tag: string, system: string[]): Promise<void> {
        await switchLanguage(tag, system);
    }

    function thenTheWindowSpeaks(tag: string): void {
        expect(language.value).toBe(tag);
    }

    function thenTheLanguageKeptIs(tag: string): void {
        expect(keptLanguage()).toBe(tag);
    }
});
