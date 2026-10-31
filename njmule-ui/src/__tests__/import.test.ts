// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { detail, importButtons, reportSummary, type ImportItem, type ImportReport } from '../import';

// A translation that shows the key and its arguments, so that the phrases can be told apart.
function translate(key: string, ...args: unknown[]): string {
    return args.length === 0 ? key : `${key}(${args.join(',')})`;
}

describe('the import from eMule', () => {
    let item: ImportItem;
    let report: ImportReport;
    let text = '';
    let buttons: ReturnType<typeof importButtons>;

    it('should write a detail phrase by phrase in the language of the window', () => {
        givenAnItemSaying(
            [
                { key: 'nj.import.d.preferences', args: [] },
                { key: 'nj.import.d.servers', args: [214] },
            ],
            'nick, limits; 214 servers',
        );
        whenItsDetailIsWritten();
        thenTheTextIs('nj.import.d.preferences; nj.import.d.servers(214)');
    });

    it('should write a detail as the engine did when a phrase is unknown here', () => {
        givenAnItemSaying([{ key: 'nj.import.d.newer', args: [] }], 'something newer');
        whenItsDetailIsWritten();
        thenTheTextIs('something newer');
    });

    it('should count the files checked and those unreadable', () => {
        givenAReport(true, ['would_import', 'skipped', 'failed']);
        whenItsSummaryIsWritten();
        thenTheTextIs('nj.import.checked(3,1)');
    });

    it('should tell an import done once it was done', () => {
        givenAReport(false, ['imported', 'missing']);
        whenItsSummaryIsWritten();
        thenTheTextIs('nj.import.done(2,0)');
    });

    it('should put Import before Close on Windows, Check apart', () => {
        whenTheButtonsAreOrderedFor('Win32');
        thenTheButtonsAre(['take', 'close'], true);
    });

    it('should put Close before Import on macOS, Check apart', () => {
        whenTheButtonsAreOrderedFor('MacIntel');
        thenTheButtonsAre(['close', 'take'], true);
    });

    it('should put Close before Import on Linux, Check beside them', () => {
        whenTheButtonsAreOrderedFor('Linux x86_64');
        thenTheButtonsAre(['close', 'take'], false);
    });

    function givenAnItemSaying(phrases: ImportItem['phrases'], english: string): void {
        item = { file: 'preferences.ini', outcome: 'would_import', detail: english, phrases };
    }

    function givenAReport(dryRun: boolean, outcomes: ImportItem['outcome'][]): void {
        report = {
            dryRun,
            startAtLogin: false,
            items: outcomes.map((outcome, n) => ({ file: `f${n}`, outcome, detail: '', phrases: [] })),
        };
    }

    function whenItsDetailIsWritten(): void {
        text = detail(item, translate);
    }

    function whenItsSummaryIsWritten(): void {
        text = reportSummary(report, translate);
    }

    function whenTheButtonsAreOrderedFor(platform: string): void {
        buttons = importButtons(platform);
    }

    function thenTheTextIs(expected: string): void {
        expect(text).toBe(expected);
    }

    function thenTheButtonsAre(order: string[], apart: boolean): void {
        expect(buttons).toEqual({ order, apart });
    }
});
