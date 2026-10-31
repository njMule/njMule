// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api } from './api/client';
import { rate } from './format';
import { t } from './i18n';
import { preferences } from './model/engine';

// The download and upload limits as sliders over the line's capacity, as the desktop app's speed limits.

/**
 * The slider's positions: the first is no limit, the others go from 1 KiB/s to the top on a logarithmic scale, so that
 * a few KB/s and many MB/s are both in reach.
 */
export const STEPS = 1000;

/** The limit at {@code position} of a slider whose top is {@code top} KiB/s; 0 is no limit. */
export function kibAt(position: number, top: number): number {
    if (position < 1) {
        return 0;
    }
    if (position >= STEPS) {
        return top;
    }
    const value = Math.exp((Math.log(Math.max(1, top)) * (position - 1)) / (STEPS - 1));
    return Math.max(1, roundTwoDigits(value));
}

/** Where {@code kib} sits on a slider whose top is {@code top} KiB/s. */
export function positionOf(kib: number, top: number): number {
    if (kib <= 0) {
        return 0;
    }
    if (top <= 1 || kib >= top) {
        return STEPS;
    }
    return 1 + (Math.log(kib) / Math.log(top)) * (STEPS - 1);
}

// Two significant digits: 1,237 KiB/s is set as 1,200, which reads 1.17 MB/s rather than an odd number.
function roundTwoDigits(value: number): number {
    if (value < 100) {
        return Math.round(value);
    }
    const scale = 10 ** (Math.floor(Math.log10(value)) - 1);
    return Math.round(Math.round(value / scale) * scale);
}

/** A limit in words: no limit, or the rate. */
export function limitText(kib: number): string {
    return kib === 0 ? t('nj.rate.noLimit') : rate(kib * 1024);
}

/** The limits and the line's capacity in KiB/s, as the preferences hold them. */
export interface Limits {
    down: number;
    up: number;
    downCapacity: number;
    upCapacity: number;
}

export function limitsOf(p: Record<string, string>): Limits {
    const value = (key: string) => Number(p[key] ?? 0) || 0;
    return {
        down: value('limits.downloadKiBps'),
        up: value('limits.uploadKiBps'),
        downCapacity: value('limits.downloadCapacityKiBps'),
        upCapacity: value('limits.uploadCapacityKiBps'),
    };
}

/** Sets the limits named, in KiB/s, and keeps the preferences shown as the engine now holds them. */
export async function setLimits(change: { downloadKiBps?: number; uploadKiBps?: number }): Promise<void> {
    const now = await answer(api().PUT('/limits', { body: change }));
    preferences.value = {
        ...preferences.value,
        'limits.downloadKiBps': String(now.downloadKiBps),
        'limits.uploadKiBps': String(now.uploadKiBps),
    };
}

/** The two settings one reaches for: the line's whole capacity, or as little as a limit can be. */
export function setPreset(fast: boolean): Promise<void> {
    const l = limitsOf(preferences.value);
    return setLimits(
        fast ? { downloadKiBps: l.downCapacity, uploadKiBps: l.upCapacity } : { downloadKiBps: 1, uploadKiBps: 1 },
    );
}
