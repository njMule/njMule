// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { kibAt, limitsOf, positionOf, STEPS, type Limits } from '../limits';

describe('the speed limits sliders', () => {
    let kib = 0;
    let position = 0;
    let limits: Limits | null = null;

    it('should take the first position as no limit', () => {
        whenTheSliderIsAt(0, 10_000);
        thenTheLimitIs(0);
    });

    it('should take the last position as the top', () => {
        whenTheSliderIsAt(STEPS, 10_000);
        thenTheLimitIs(10_000);
    });

    it('should start the scale at 1 KiB/s', () => {
        whenTheSliderIsAt(1, 10_000);
        thenTheLimitIs(1);
    });

    it('should round a limit to two significant digits', () => {
        whenTheSliderIsAt(STEPS / 2, 1_000_000);
        thenTheLimitIs(990);
    });

    it('should put no limit at the start', () => {
        whenTheLimitIsPlaced(0, 10_000);
        thenThePositionIs(0);
    });

    it('should put a limit past the top at the end', () => {
        whenTheLimitIsPlaced(20_000, 10_000);
        thenThePositionIs(STEPS);
    });

    it('should place a limit where it reads back the same', () => {
        whenTheLimitIsPlaced(1_200, 10_000);
        thenItReadsBack(1_200, 10_000);
    });

    it('should read the limits and the capacity from the preferences', () => {
        whenThePreferencesAreRead({
            'limits.downloadKiBps': '500',
            'limits.uploadKiBps': '50',
            'limits.downloadCapacityKiBps': '10000',
        });
        thenTheLimitsAre({ down: 500, up: 50, downCapacity: 10_000, upCapacity: 0 });
    });

    function whenTheSliderIsAt(at: number, top: number): void {
        kib = kibAt(at, top);
    }

    function whenTheLimitIsPlaced(value: number, top: number): void {
        position = positionOf(value, top);
    }

    function whenThePreferencesAreRead(preferences: Record<string, string>): void {
        limits = limitsOf(preferences);
    }

    function thenTheLimitIs(expected: number): void {
        expect(kib).toBe(expected);
    }

    function thenThePositionIs(expected: number): void {
        expect(position).toBe(expected);
    }

    function thenItReadsBack(expected: number, top: number): void {
        expect(kibAt(position, top)).toBe(expected);
    }

    function thenTheLimitsAre(expected: Limits): void {
        expect(limits).toEqual(expected);
    }
});
