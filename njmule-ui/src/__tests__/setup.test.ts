// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { changedPreferences, LINES, lineKib, portPreferences, wizardPages, type Answers } from '../setup';

describe('the questions of the first start', () => {
    const SAVED: Record<string, string> = {
        'user.nick': 'njMule',
        'dirs.incoming': '',
        'net.tcpPort': '4662',
        'net.udpPort': '4672',
        'limits.downloadCapacityKiBps': '10240',
        'limits.uploadCapacityKiBps': '1024',
        'net.upnp': 'false',
    };
    const SHOWN_INCOMING = 'C:\\Users\\ann\\Downloads\\njMule';
    let answers: Answers;
    let changes: Record<string, string> = {};
    let pages: string[] = [];
    let kib = 0;

    it('should leave every setting as it is when the pages are left as they were', () => {
        givenThePagesAsTheyOpened();
        whenTheChangesAreWorkedOut();
        thenTheChangesAre({});
    });

    it('should change only what was answered otherwise', () => {
        givenThePagesAsTheyOpened();
        givenTheAnswers({ nick: 'Lupo', tcpPort: 30407, upnp: true });
        whenTheChangesAreWorkedOut();
        thenTheChangesAre({ 'user.nick': 'Lupo', 'net.tcpPort': '30407', 'net.upnp': 'true' });
    });

    it('should keep the setting of a field that is wrong', () => {
        givenThePagesAsTheyOpened();
        givenTheAnswers({ nick: null, udpPort: null, downloadKiBps: null });
        whenTheChangesAreWorkedOut();
        thenTheChangesAre({});
    });

    it('should set the incoming folder only when another one is chosen', () => {
        givenThePagesAsTheyOpened();
        givenTheAnswers({ incoming: 'D:\\Incoming' });
        whenTheChangesAreWorkedOut();
        thenTheChangesAre({ 'dirs.incoming': 'D:\\Incoming' });
    });

    it('should test the ports as the page gives them, and nothing else', () => {
        givenThePagesAsTheyOpened();
        givenTheAnswers({ nick: 'Lupo', udpPort: 20998, upnp: true });
        whenThePortsAreWorkedOut();
        thenTheChangesAre({ 'net.udpPort': '20998', 'net.upnp': 'true' });
    });

    it('should ask about eMule only when one was found', () => {
        whenThePagesAreListed(false);
        thenThePagesAre(['start', 'nick', 'folders', 'connection']);
    });

    it('should end with eMule when one was found', () => {
        whenThePagesAreListed(true);
        thenThePagesAre(['start', 'nick', 'folders', 'connection', 'emule']);
    });

    it('should turn the megabits of a line into the KiB/s of the settings', () => {
        whenTheLineIsTaken(LINES[1]!.down);
        thenTheCapacityIs(12_207);
    });

    function givenThePagesAsTheyOpened(): void {
        answers = {
            nick: 'njMule',
            incoming: SHOWN_INCOMING,
            tcpPort: 4662,
            udpPort: 4672,
            downloadKiBps: 10240,
            uploadKiBps: 1024,
            upnp: false,
        };
    }

    function givenTheAnswers(changed: Partial<Answers>): void {
        answers = { ...answers, ...changed };
    }

    function whenTheChangesAreWorkedOut(): void {
        changes = changedPreferences(answers, SAVED, SHOWN_INCOMING);
    }

    function whenThePortsAreWorkedOut(): void {
        changes = portPreferences(changedPreferences(answers, SAVED, SHOWN_INCOMING));
    }

    function whenThePagesAreListed(emuleFound: boolean): void {
        pages = wizardPages(emuleFound);
    }

    function whenTheLineIsTaken(mbits: number): void {
        kib = lineKib(mbits);
    }

    function thenTheChangesAre(expected: Record<string, string>): void {
        expect(changes).toEqual(expected);
    }

    function thenThePagesAre(expected: string[]): void {
        expect(pages).toEqual(expected);
    }

    function thenTheCapacityIs(expected: number): void {
        expect(kib).toBe(expected);
    }
});
