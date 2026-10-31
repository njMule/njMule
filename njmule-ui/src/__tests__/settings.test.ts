// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    defaultWords,
    holds,
    label,
    matches,
    PAGES,
    pendingWords,
    rowTitle,
    type Page,
    type Row,
    type Section,
} from '../settings';

describe('settings', () => {
    const page: Page = { id: 'p', title: 'Connection', summary: 'How it connects', icon: 'LINK', sections: [] };
    const section: Section = { title: 'Ports', rows: [] };
    const row: Row = { label: 'Client Port', hint: 'TCP', fields: [{ key: 'net.tcpPort', kind: 'number' }] };
    // The keys stand for their texts, as the tests have no translations.
    const t = (key: string, ...args: unknown[]) => (args.length === 0 ? key : `${key}(${args.join(',')})`);
    let words = '';
    let found = false;
    let keys: string[] = [];
    let text = '';
    let defaults: Record<string, string> = {};
    let shown: Row = row;

    it('should find an option by every word of its label, hint, section or page', () => {
        givenTheWords('client ports connection tcp');
        whenTheOptionIsLookedFor();
        thenItIsFound(true);
    });

    it('should not find an option missing a word', () => {
        givenTheWords('port proxy');
        whenTheOptionIsLookedFor();
        thenItIsFound(false);
    });

    it('should not find an option by the description of its page', () => {
        givenTheWords('connects');
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
    ])('should show the label %s without its colon', (translated, expected) => {
        whenTheLabelIsShown(translated);
        thenTheTextReads(expected);
    });

    it.each([
        ['net.tcpPort', 'IDS_PW_CLIENTPORT (TCP)'],
        ['proxy.port', 'nj.proxy.host'],
        ['proxy.password', 'nj.proxy.user'],
        ['notify.smtpPort', 'IDS_SMTPSERVER'],
        ['notify.smtpPassword', 'nj.proxy.user'],
        ['events.chatMessage.command', 'nj.events.chatMessage'],
    ])('should show %s in the row named %s', (key, expected) => {
        whenTheTitleOfTheRowOfIsTaken(key);
        thenTheTextReads(expected);
    });

    it.each([
        ['switch', 'true', 'IDS_YES'],
        ['limit', '0', 'nj.rate.noLimit'],
        ['limit', '100', 'rate(102400)'],
        ['number', '25', '25'],
        ['text', 'words', ''],
    ] as const)('should name the default of a %s %s as %s', (kind, value, expected) => {
        givenTheDefault({ label: 'x', fields: [{ key: 'k', kind }] }, value);
        whenTheDefaultIsWritten();
        thenTheTextReads(expected);
    });

    it('should name the default of an option with more fields as none', () => {
        givenTheDefault(rowOf('proxy.host'), 'host');
        whenTheDefaultIsWritten();
        thenTheTextReads('');
    });

    it('should name the default of a folder as the default folder', () => {
        givenTheDefault(rowOf('dirs.incoming'), '');
        whenTheDefaultIsWritten();
        thenTheTextReads('nj.settings.defaultDir');
    });

    it('should tell every reason a change waits, and the value to fix', () => {
        whenTheWaitIsTold(true, true, 'UDP');
        thenTheTextReads('nj.settings.pendingReconnect nj.settings.pendingRestart nj.settings.fixFirst(UDP)');
    });

    it('should tell nothing while nothing waits', () => {
        whenTheWaitIsTold(false, false, undefined);
        thenTheTextReads('');
    });

    it.each([
        ['SOCKS', true],
        ['NONE', false],
    ])('should let the proxy host be set with a proxy %s: %s', (type, expected) => {
        whenTheConditionsOfAreRead('proxy.host', { 'proxy.type': type });
        thenItIsFound(expected);
    });

    function rowOf(key: string): Row {
        return PAGES.flatMap((p) => p.sections.flatMap((s) => s.rows)).find((r) =>
            r.fields.some((f) => f.key === key),
        )!;
    }

    function givenTheWords(typed: string): void {
        words = typed;
    }

    function givenTheDefault(of: Row, value: string): void {
        shown = of;
        defaults = { [of.fields[0]!.key]: value };
    }

    function whenTheLabelIsShown(translated: string): void {
        text = label(translated);
    }

    function whenTheTitleOfTheRowOfIsTaken(key: string): void {
        text = rowTitle(rowOf(key), (k) => k);
    }

    function whenTheOptionIsLookedFor(): void {
        found = matches(row, section, page, words, (key) => key);
    }

    function whenTheKeysAreCollected(): void {
        keys = PAGES.flatMap((p) => p.sections.flatMap((s) => s.rows.flatMap((r) => r.fields.map((f) => f.key))));
    }

    function whenTheDefaultIsWritten(): void {
        text = defaultWords(shown, defaults, t, (b) => `rate(${b})`);
    }

    function whenTheWaitIsTold(network: boolean, restart: boolean, wrong: string | undefined): void {
        text = pendingWords(network, restart, wrong, t);
    }

    function whenTheConditionsOfAreRead(key: string, values: Record<string, string>): void {
        const field = rowOf(key).fields.find((f) => f.key === key)!;
        found = holds(field.enabledWhen, (k) => values[k] ?? '');
    }

    function thenNoKeyRepeats(): void {
        expect(new Set(keys).size).toBe(keys.length);
    }

    function thenTheTextReads(expected: string): void {
        expect(text).toBe(expected);
    }

    function thenItIsFound(expected: boolean): void {
        expect(found).toBe(expected);
    }
});
