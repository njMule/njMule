// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    convertRate,
    digit,
    fitsListCount,
    invalidListEntry,
    invalidWhitelistEntry,
    isCommandLine,
    isFullPath,
    isNightHours,
    isSoundFile,
    isValidHost,
    isWebhookUrl,
    isWhitelistEntry,
    keepTyped,
    nickChar,
    optionalNumberRules,
    rateKib,
    rateRules,
    rateText,
    timeOfDay,
    webAddresses,
    type RateUnit,
} from '../screens/settings/inputs';

describe('inputs', () => {
    let text = '';
    let kept = '';
    let valid = false;
    let kib: number | null | undefined;
    let found: string | undefined;
    let addresses: string[] = [];

    it('should keep only the characters a field may hold', () => {
        whenTyped('12', '1a2b3', digit, 6);
        thenTheFieldHolds('123');
    });

    it('should refuse a change that makes the text too long', () => {
        whenTyped('1234', '12345', digit, 4);
        thenTheFieldHolds('1234');
    });

    it('should keep the text direction controls out of a name', () => {
        whenTyped('', 'ab\u202ecd', nickChar, 50);
        thenTheFieldHolds('abcd');
    });

    it.each(['example.com', 'njmule.example.com.', 'localhost', '1.2.3.4', '::1', 'fe80::1:2'])(
        'should take %s as a host',
        (host) => {
            whenTheHostIsChecked(host);
            thenItIsValid(true);
        },
    );

    it.each(['', '1.2.3.256', '-a.example', 'a..example', 'example.123', '[::1]'])(
        'should not take %s as a host',
        (host) => {
            whenTheHostIsChecked(host);
            thenItIsValid(false);
        },
    );

    it.each([
        ['', true],
        ['https://ntfy.sh/topic', true],
        ['http://ntfy.sh/topic', false],
        ['https://', false],
        ['https://ntfy.sh/a b', false],
    ])('should take %s as a web hook: %s', (url, expected) => {
        whenTheWebHookIsChecked(url);
        thenItIsValid(expected);
    });

    it('should name the first line that is not the address of a list', () => {
        whenTheListsAreChecked('https://example.com/ipfilter.zip\n\nftp://example.com/level1.p2p\nnot one');
        thenTheEntryFoundIs('ftp://example.com/level1.p2p');
    });

    it('should take at most sixteen lists', () => {
        whenTheListCountIsChecked(17);
        thenItIsValid(false);
    });

    it.each(['1.2.3.4', '1.2.3.0/24', '1.2.3.0 - 1.2.3.255 , 100 , name', 'Some name:1.2.3.0-1.2.3.255', '10.0.0.0/8'])(
        'should take %s in the whitelist',
        (entry) => {
            whenTheWhitelistEntryIsChecked(entry);
            thenItIsValid(true);
        },
    );

    it.each(['1.2.3.4/33', '300.1.1.1', 'example.com', '1.2.3.9 - 1.2.3.1', '/24'])(
        'should not take %s in the whitelist',
        (entry) => {
            whenTheWhitelistEntryIsChecked(entry);
            thenItIsValid(false);
        },
    );

    it('should name the first wrong entry of a whitelist, comments left aside', () => {
        whenTheWhitelistIsChecked('# mine\n// also mine\n1.2.3.4\nnot one\n5.6.7.8');
        thenTheEntryFoundIs('not one');
    });

    it.each([
        ['03:00-05:00', true],
        ['03:00 - 05:00', true],
        ['3:00-05:00', false],
        ['03:00-03:00', false],
        ['03:00', false],
    ])('should take %s as night hours: %s', (hours, expected) => {
        whenTheNightHoursAreChecked(hours);
        thenItIsValid(expected);
    });

    it.each([
        ['7:30', '07:30'],
        ['23:59', '23:59'],
        ['24:00', null],
        ['7:3', null],
    ])('should read the time %s as %s', (typed, expected) => {
        whenTheTimeIsRead(typed);
        thenTheTimeIs(expected);
    });

    it.each([
        ['"C:\\Program Files\\x.exe" %FILE', true],
        ["notify 'it''s done'", true],
        ['"unclosed %NAME', false],
    ])('should take the command line %s: %s', (line, expected) => {
        whenTheCommandLineIsChecked(line);
        thenItIsValid(expected);
    });

    it.each([
        ['', true],
        ['C:\\sounds\\done.wav', true],
        ['/usr/share/sounds/done.wav', true],
        ['\\\\server\\share\\done.wav', true],
        ['done.wav', false],
    ])('should take %s as the full path of a file: %s', (path, expected) => {
        whenThePathIsChecked(path);
        thenItIsValid(expected);
    });

    it.each([
        ['', true],
        ['C:\\sounds\\done.wav', true],
        ['C:\\sounds\\done.MP3', true],
        ['C:\\sounds\\done.ogg', false],
        ['done.mp3', false],
    ])('should take %s as a sound the window plays: %s', (path, expected) => {
        whenTheSoundIsChecked(path);
        thenItIsValid(expected);
    });

    it.each([
        ['1,5', 'MB', 1, 1536],
        ['1.5', 'MB', 1, 1536],
        ['100', 'KB', 1, 100],
        ['8', 'MBIT', 1, 977],
        ['0.4', 'KB', 1, null],
        ['20000000', 'KB', 1, null],
        ['1.2.3', 'KB', 1, null],
        ['', 'KB', 1, undefined],
    ] as const)('should read %s %s as %s KiB/s', (typed, unit, min, expected) => {
        whenTheSpeedIsRead(typed, unit, min);
        thenTheSpeedIs(expected);
    });

    it.each([
        [1536, 'MB', 'en', '1.5'],
        [1536, 'MB', 'it', '1,5'],
        [1500, 'MB', 'en', '1.46'],
        [977, 'MBIT', 'en', '8'],
        [100, 'KB', 'en', '100'],
    ] as const)('should write %s KiB/s in %s for %s as %s', (value, unit, locale, expected) => {
        whenTheSpeedIsWritten(value, unit, locale);
        thenTheFieldHolds(expected);
    });

    it('should write a speed typed again in another unit', () => {
        whenTheSpeedIsConverted('1.5', 'MB', 'KB');
        thenTheFieldHolds('1536');
    });

    it('should keep the http and https addresses of server lists only', () => {
        whenTheAddressesAreRead(' https://example.com/server.met \nftp://x/server.met\n\nnot an address');
        thenTheAddressesAre(['https://example.com/server.met']);
    });

    it.each([
        ['', true],
        ['5', true],
        ['10000', true],
        ['0', false],
        ['10001', false],
    ])('should take %s for a rule limit of 1 to 10000: %s', (typed, expected) => {
        whenTheRuleLimitIsChecked(typed);
        thenItIsValid(expected);
    });

    it.each([
        [true, true],
        [false, false],
    ])('should take an empty speed where it is optional (%s): %s', (optional, expected) => {
        whenAnEmptySpeedIsChecked(optional);
        thenItIsValid(expected);
    });

    function whenTyped(previous: string, next: string, allowed: (c: string) => boolean, max: number): void {
        kept = keepTyped(previous, next, allowed, max);
    }

    function whenTheHostIsChecked(host: string): void {
        valid = isValidHost(host);
    }

    function whenTheWebHookIsChecked(url: string): void {
        valid = isWebhookUrl(url);
    }

    function whenTheListsAreChecked(lists: string): void {
        found = invalidListEntry(lists);
    }

    function whenTheListCountIsChecked(count: number): void {
        valid = fitsListCount(Array.from({ length: count }, (_, i) => `https://example.com/${i}.p2p`).join('\n'));
    }

    function whenTheWhitelistEntryIsChecked(entry: string): void {
        valid = isWhitelistEntry(entry);
    }

    function whenTheWhitelistIsChecked(whitelist: string): void {
        found = invalidWhitelistEntry(whitelist);
    }

    function whenTheNightHoursAreChecked(hours: string): void {
        valid = isNightHours(hours);
    }

    function whenTheTimeIsRead(typed: string): void {
        text = timeOfDay(typed) ?? 'none';
    }

    function whenTheCommandLineIsChecked(line: string): void {
        valid = isCommandLine(line);
    }

    function whenThePathIsChecked(path: string): void {
        valid = isFullPath(path);
    }

    function whenTheSoundIsChecked(path: string): void {
        valid = isSoundFile(path);
    }

    function whenTheSpeedIsRead(typed: string, unit: RateUnit, min: number): void {
        kib = rateKib(typed, unit, min);
    }

    function whenTheSpeedIsWritten(value: number, unit: RateUnit, locale: string): void {
        kept = rateText(value, unit, locale);
    }

    function whenTheSpeedIsConverted(typed: string, from: RateUnit, to: RateUnit): void {
        kept = convertRate(typed, from, to, 'en');
    }

    function whenTheRuleLimitIsChecked(typed: string): void {
        valid = optionalNumberRules(1, 10000, (key) => key).valid(typed);
    }

    function whenAnEmptySpeedIsChecked(optional: boolean): void {
        valid = rateRules('KB', 1, optional, (key) => key, String).valid('');
    }

    function whenTheAddressesAreRead(lines: string): void {
        addresses = webAddresses(lines);
    }

    function thenTheFieldHolds(expected: string): void {
        expect(kept).toBe(expected);
    }

    function thenItIsValid(expected: boolean): void {
        expect(valid).toBe(expected);
    }

    function thenTheEntryFoundIs(expected: string): void {
        expect(found).toBe(expected);
    }

    function thenTheTimeIs(expected: string | null): void {
        expect(text).toBe(expected ?? 'none');
    }

    function thenTheSpeedIs(expected: number | null | undefined): void {
        expect(kib).toBe(expected);
    }

    function thenTheAddressesAre(expected: string[]): void {
        expect(addresses).toEqual(expected);
    }
});
