// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it } from 'vitest';
import { count, duration, rate, shortTime, size } from '../format';
import { language } from '../i18n';

describe('format', () => {
    let shown = '';

    afterEach(() => {
        language.value = 'en';
    });

    it('should group the thousands of a four-digit count, as the desktop app', () => {
        givenTheLanguage('it');
        whenTheCountIsWritten(1243);
        thenItReads('1.243');
    });

    it('should write small sizes in bytes', () => {
        whenTheSizeIsWritten(999);
        thenItReads('999 B');
    });

    it('should write sizes with two decimals below a hundred units', () => {
        whenTheSizeIsWritten(1.5 * 1024 * 1024);
        thenItReads('1.50 MB');
    });

    it('should write a rate below a thousand kilobytes in kilobytes', () => {
        whenTheRateIsWritten(12.3 * 1024);
        thenItReads('12.3 KB/s');
    });

    it('should write a rate from a thousand kilobytes on in megabytes', () => {
        whenTheRateIsWritten(1.26 * 1024 * 1024);
        thenItReads('1.26 MB/s');
    });

    it('should write long durations in days and hours', () => {
        whenTheDurationIsWritten(2 * 86400 + 3 * 3600);
        thenItReads('2 d 3 h');
    });

    it('should write minutes with their seconds', () => {
        whenTheDurationIsWritten(4 * 60 + 7);
        thenItReads('4:07 mins');
    });

    function givenTheLanguage(tag: string): void {
        language.value = tag;
    }

    function whenTheCountIsWritten(value: number): void {
        shown = count(value);
    }

    function whenTheSizeIsWritten(bytes: number): void {
        shown = size(bytes);
    }

    function whenTheRateIsWritten(bytesPerSecond: number): void {
        shown = rate(bytesPerSecond);
    }

    function whenTheDurationIsWritten(seconds: number): void {
        shown = duration(seconds);
    }

    function thenItReads(text: string): void {
        expect(shown).toBe(text);
    }
});

describe('the time of the day', () => {
    let shown = '';

    afterEach(() => {
        language.value = 'en';
    });

    it('should write the time on a 12-hour clock in English', () => {
        givenTheLanguage('en');
        whenTheTimeIsWritten(new Date(2026, 9, 31, 22, 9, 41));
        thenItReads('10:09 PM');
    });

    it('should write the time on a 24-hour clock in Italian', () => {
        givenTheLanguage('it');
        whenTheTimeIsWritten(new Date(2026, 9, 31, 22, 9, 41));
        thenItReads('22:09');
    });

    function givenTheLanguage(tag: string): void {
        language.value = tag;
    }

    function whenTheTimeIsWritten(time: Date): void {
        shown = shortTime(time);
    }

    // The language may put a narrow space before AM and PM.
    function thenItReads(text: string): void {
        expect(shown.replace(/\s/g, ' ')).toBe(text);
    }
});
