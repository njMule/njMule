// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { duration, rate, size } from '../format';

describe('format', () => {
    let shown = '';

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
