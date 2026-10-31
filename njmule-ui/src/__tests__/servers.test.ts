// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it } from 'vitest';
import type { ServerNote } from '../model/engine';
import {
    hostPort,
    lastSaid,
    rememberUrl,
    serverLink,
    serverLinks,
    type ServerLinkFound,
    urlHistory,
    webAddress,
} from '../network';

describe('the addresses of servers and lists', () => {
    let address: string | null = null;
    let url: URL | null = null;

    it.each(['example.org:4661', '198.51.100.23:4242', '[2001:db8::1]:4661'])(
        'should take %s as a server address',
        (text) => {
            whenReadAsAnAddress(` ${text} `);
            thenTheAddressIs(text);
        },
    );

    it.each(['example.org', 'example.org:0', 'example.org:70000', '999.1.1.1:4661', 'a b:4661', ''])(
        'should refuse %s as a server address',
        (text) => {
            whenReadAsAnAddress(text);
            thenTheAddressIs(null);
        },
    );

    it('should take an https address with a host as a list', () => {
        whenReadAsAList('https://upd.emule-security.org/server.met');
        thenTheListHostIs('upd.emule-security.org');
    });

    it.each(['ftp://example.org/server.met', 'server.met', 'https://'])('should refuse %s as a list', (text) => {
        whenReadAsAList(text);
        thenTheListHostIs(null);
    });

    function whenReadAsAnAddress(text: string): void {
        address = hostPort(text);
    }

    function whenReadAsAList(text: string): void {
        url = webAddress(text);
    }

    function thenTheAddressIs(expected: string | null): void {
        expect(address).toBe(expected);
    }

    function thenTheListHostIs(host: string | null): void {
        expect(url?.hostname ?? null).toBe(host);
    }
});

describe('the server links', () => {
    let found: ServerLinkFound[] = [];
    let link = '';

    it('should find the servers and the server lists of a text, in their order', () => {
        whenTheLinksAreRead(
            'see ed2k://|serverlist|https://example.org/server.met|/ and ED2K://|server|198.51.100.23|4661|/ ' +
                'ed2k://|file|a.txt|1|0123456789ABCDEF0123456789ABCDEF|/',
        );
        thenTheLinksAre([
            { kind: 'list', url: 'https://example.org/server.met' },
            { kind: 'server', address: '198.51.100.23:4661' },
        ]);
    });

    it('should leave out a server link whose port is none', () => {
        whenTheLinksAreRead('ed2k://|server|example.org|0|/');
        thenTheLinksAre([]);
    });

    it('should write the link of a server', () => {
        whenTheLinkIsMade('[2001:db8::1]:4661');
        thenTheLinkIs('ed2k://|server|2001:db8::1|4661|/');
    });

    function whenTheLinksAreRead(text: string): void {
        found = serverLinks(text);
    }

    function whenTheLinkIsMade(address: string): void {
        link = serverLink(address);
    }

    function thenTheLinksAre(expected: ServerLinkFound[]): void {
        expect(found).toEqual(expected);
    }

    function thenTheLinkIs(expected: string): void {
        expect(link).toBe(expected);
    }
});

describe('what the server said', () => {
    let notes: ServerNote[] = [];
    let said: string[] = [];

    it('should keep the last four lines since the latest session started', () => {
        givenNotes([
            'message:old',
            'connected:Lighthouse',
            'message:1',
            'message:2',
            'message:3',
            'message:4',
            'message:5',
        ]);
        whenWhatWasSaidIsRead();
        thenItIs(['2', '3', '4', '5']);
    });

    it('should forget the lines of an earlier session', () => {
        givenNotes(['message:old', 'obfuscated:Lighthouse']);
        whenWhatWasSaidIsRead();
        thenItIs([]);
    });

    function givenNotes(lines: string[]): void {
        notes = lines.map((line, i) => {
            const [kind, text] = line.split(':') as [ServerNote['kind'], string];
            return { sequence: i + 1, time: '2026-10-31T10:00:00Z', kind, text };
        });
    }

    function whenWhatWasSaidIsRead(): void {
        said = lastSaid(notes);
    }

    function thenItIs(expected: string[]): void {
        expect(said).toEqual(expected);
    }
});

describe('the history of the lists', () => {
    let history: string[] = [];

    beforeEach(() => localStorage.clear());

    it('should put the latest list that worked first, once', () => {
        givenRemembered([
            'https://a.example/server.met',
            'https://b.example/server.met',
            'https://a.example/server.met',
        ]);
        whenTheHistoryIsRead();
        thenTheHistoryIs(['https://a.example/server.met', 'https://b.example/server.met']);
    });

    it('should keep ten lists at most', () => {
        givenRemembered(Array.from({ length: 12 }, (_, i) => `https://${i}.example/server.met`));
        whenTheHistoryIsRead();
        thenTheHistoryHas(10, 'https://11.example/server.met');
    });

    function givenRemembered(urls: string[]): void {
        urls.forEach((url) => rememberUrl('serverMet', url));
    }

    function whenTheHistoryIsRead(): void {
        history = urlHistory('serverMet');
    }

    function thenTheHistoryIs(expected: string[]): void {
        expect(history).toEqual(expected);
    }

    function thenTheHistoryHas(size: number, latest: string): void {
        expect([history.length, history[0]]).toEqual([size, latest]);
    }
});
