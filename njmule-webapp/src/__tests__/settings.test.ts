// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { label, matches, PAGES, type Page, type Row } from '../settings';

describe('settings', () => {
    const page: Page = { id: 'p', title: 'Connection', summary: 'How it connects', sections: [] };
    const row: Row = { key: 'net.tcpPort', label: 'Client Port', kind: 'number', hint: 'TCP' };
    let words = '';
    let found = false;
    let keys: string[] = [];
    let text = '';

    it('should find an option by every word of its label, hint or page', () => {
        givenTheWords('port connection');
        whenTheOptionIsLookedFor();
        thenItIsFound(true);
    });

    it('should not find an option missing a word', () => {
        givenTheWords('port proxy');
        whenTheOptionIsLookedFor();
        thenItIsFound(false);
    });

    it('should bind every option to its own key', () => {
        whenTheKeysAreCollected();
        thenNoKeyRepeats();
    });

    it.each([
        ['Proxy host:', 'Proxy host'],
        ['Port du proxy :', 'Port du proxy'],
        ['Client Port', 'Client Port'],
    ])('should show the label %s without its colon', (translated, shown) => {
        whenTheLabelIsShown(translated);
        thenTheLabelReads(shown);
    });

    it.each([
        ['proxy.port', 'IDS_PROXY_PORT'],
        ['proxy.password', 'IDS_WS_PASS'],
        ['notify.smtpPassword', 'IDS_WS_PASS'],
    ])('should name %s as what it holds', (key, expected) => {
        whenTheLabelOfIsTaken(key);
        thenTheLabelReads(expected);
    });

    function givenTheWords(typed: string): void {
        words = typed;
    }

    function whenTheLabelIsShown(translated: string): void {
        text = label(translated);
    }

    function whenTheLabelOfIsTaken(key: string): void {
        text = PAGES.flatMap((p) => p.sections.flatMap((s) => s.rows)).find((r) => r.key === key)?.label ?? '';
    }

    function whenTheOptionIsLookedFor(): void {
        found = matches(row, page, words, (key) => key);
    }

    function whenTheKeysAreCollected(): void {
        keys = PAGES.flatMap((p) => p.sections.flatMap((s) => s.rows.map((r) => r.key)));
    }

    function thenNoKeyRepeats(): void {
        expect(new Set(keys).size).toBe(keys.length);
    }

    function thenTheLabelReads(expected: string): void {
        expect(text).toBe(expected);
    }

    function thenItIsFound(expected: boolean): void {
        expect(found).toBe(expected);
    }
});
