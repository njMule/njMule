// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { logTime } from '../network';

describe('the time of a log line', () => {
    let written = '';

    it('should write the date with its whole year and the time with its seconds, as the system does', () => {
        whenWritten('2026-10-31T09:05:07Z', 'en-US');
        thenItReads(/^\d{1,2}\/\d{1,2}\/2026, \d{1,2}:\d{2}:07\s[AP]M$/);
    });

    it('should follow the clock of the locale', () => {
        whenWritten('2026-10-31T09:05:07Z', 'de-DE');
        // A 24-hour clock without AM or PM; the hour has one digit or two as the time zone puts it.
        thenItReads(/^\d{1,2}\.\d{1,2}\.2026, \d{1,2}:\d{2}:07$/);
    });

    function whenWritten(time: string, locale: string): void {
        written = logTime(time, locale);
    }

    function thenItReads(pattern: RegExp): void {
        expect(written).toMatch(pattern);
    }
});
