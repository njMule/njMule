// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { connect } from '../api/client';
import {
    detail,
    importButtons,
    reportSummary,
    startAtLoginAsEmuleDid,
    takeEmuleHistory,
    type ImportItem,
    type ImportReport,
} from '../import';
import { urlHistory } from '../network';
import type { Platform } from '../platform';
import { history, remember } from '../search';
import { close, toasts } from '../toasts';

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

// An engine whose calls a test answers by method and path; it notes each call, with its body.
const HERE = 'http://127.0.0.1:4711/api/v1';
const ELSEWHERE = 'http://nas.local:4711/api/v1';
let answers: Record<string, Response> = {};
let asked: string[] = [];

function givenTheEngineAt(url: string): void {
    answers = {};
    asked = [];
    vi.stubGlobal('fetch', async (request: Request) => {
        const call = `${request.method} ${new URL(request.url).pathname.replace(/^\/api\/v1/, '')}`;
        const body = await request.text();
        asked.push(body === '' ? call : `${call} ${body}`);
        return answers[call]?.clone() ?? new Response('{"error":"Not found."}', { status: 404 });
    });
    connect({ url, token: 'secret' }, () => {});
}

function givenTheEngineAnswers(call: string, status: number, body: unknown): void {
    answers[call] = new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('the start at login after an import', () => {
    let platform: Platform;
    let report: ImportReport;

    beforeEach(() => {
        toasts.value.forEach((t) => close(t.id));
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('should turn it on after the import of an eMule that started at login', async () => {
        givenTheWindow(true, HERE);
        givenTheSystemStartsNjmuleAtLogin(false);
        givenAnImport(false, true);
        await whenTheImportEnds();
        thenTheEngineWasAsked('GET /desktop', 'PATCH /desktop {"autostart":true}');
        thenTheMessagesAre(['info', '[nj.import.autostart]']);
    });

    it('should only tell it when njMule starts at login already', async () => {
        givenTheWindow(true, HERE);
        givenTheSystemStartsNjmuleAtLogin(true);
        givenAnImport(false, true);
        await whenTheImportEnds();
        thenTheEngineWasAsked('GET /desktop');
        thenTheMessagesAre(['info', '[nj.import.autostart]']);
    });

    it('should tell why the system refused it', async () => {
        givenTheWindow(true, HERE);
        givenTheSystemStartsNjmuleAtLogin(false);
        givenTheSystemRefusesTheChange();
        givenAnImport(false, true);
        await whenTheImportEnds();
        thenTheMessagesAre(['danger', '[nj.settings.autostartFailed]']);
    });

    it('should leave it after the import of an eMule that did not start at login', async () => {
        givenTheWindow(true, HERE);
        givenTheSystemStartsNjmuleAtLogin(false);
        givenAnImport(false, false);
        await whenTheImportEnds();
        thenTheEngineWasAsked();
        thenTheMessagesAre();
    });

    it('should leave it after a check', async () => {
        givenTheWindow(true, HERE);
        givenTheSystemStartsNjmuleAtLogin(false);
        givenAnImport(true, true);
        await whenTheImportEnds();
        thenTheEngineWasAsked();
    });

    it("should leave it to a window on another computer's njMule", async () => {
        givenTheWindow(true, ELSEWHERE);
        givenTheSystemStartsNjmuleAtLogin(false);
        givenAnImport(false, true);
        await whenTheImportEnds();
        thenTheEngineWasAsked();
        thenTheMessagesAre();
    });

    it('should leave it to a browser', async () => {
        givenTheWindow(false, HERE);
        givenTheSystemStartsNjmuleAtLogin(false);
        givenAnImport(false, true);
        await whenTheImportEnds();
        thenTheEngineWasAsked();
        thenTheMessagesAre();
    });

    function givenTheWindow(desktop: boolean, url: string): void {
        platform = { desktop } as Platform;
        givenTheEngineAt(url);
    }

    function givenTheSystemStartsNjmuleAtLogin(on: boolean): void {
        givenTheEngineAnswers('GET /desktop', 200, { links: true, autostart: on });
        givenTheEngineAnswers('PATCH /desktop', 200, { links: true, autostart: true });
    }

    function givenTheSystemRefusesTheChange(): void {
        givenTheEngineAnswers('PATCH /desktop', 500, { error: 'Access is denied.' });
    }

    function givenAnImport(dryRun: boolean, startAtLogin: boolean): void {
        report = { dryRun, startAtLogin, items: [] };
    }

    async function whenTheImportEnds(): Promise<void> {
        await startAtLoginAsEmuleDid(report, platform);
    }

    function thenTheEngineWasAsked(...calls: string[]): void {
        expect(asked).toEqual(calls);
    }

    function thenTheMessagesAre(...shown: [string, string][]): void {
        expect(toasts.value.map((t) => [t.tone, t.text])).toEqual(shown);
    }
});

describe('the histories of the eMule imported', () => {
    beforeEach(() => {
        localStorage.clear();
        givenTheEngineAt(HERE);
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it('should start the empty histories with what was typed in eMule', async () => {
        givenTheEngineKept(['linux iso', 'talk', 'linux iso'], ['http://example.org/server.met', 'not an address']);
        await whenTheHistoryIsTaken();
        thenTheSearchesOfferedAre('linux iso', 'talk');
        thenTheServerListsOfferedAre('http://example.org/server.met');
    });

    it('should leave a history used already', async () => {
        givenTheSearchesTyped('ubuntu');
        givenTheEngineKept(['linux iso'], ['http://example.org/server.met']);
        await whenTheHistoryIsTaken();
        thenTheSearchesOfferedAre('ubuntu');
        thenTheServerListsOfferedAre('http://example.org/server.met');
    });

    it('should leave the histories empty when the engine does not tell them', async () => {
        givenTheEngineAnswers('GET /import/emule/history', 403, { error: 'A guest can only look.' });
        await whenTheHistoryIsTaken();
        thenTheSearchesOfferedAre();
        thenTheServerListsOfferedAre();
    });

    function givenTheEngineKept(searches: string[], serverLists: string[]): void {
        givenTheEngineAnswers('GET /import/emule/history', 200, { searches, serverLists });
    }

    function givenTheSearchesTyped(...searches: string[]): void {
        [...searches].reverse().forEach(remember);
    }

    async function whenTheHistoryIsTaken(): Promise<void> {
        await takeEmuleHistory();
    }

    function thenTheSearchesOfferedAre(...searches: string[]): void {
        expect(history()).toEqual(searches);
    }

    function thenTheServerListsOfferedAre(...urls: string[]): void {
        expect(urlHistory('serverMet')).toEqual(urls);
    }
});
