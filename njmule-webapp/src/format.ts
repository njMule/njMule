// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { language, t } from './i18n';

// Sizes, rates and durations as the desktop app writes them, with the units of the language.

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// A translated unit, or the English one when a language has none or an odd one.
function unit(key: string, english: string): string {
    const text = t(key).trim();
    return text === '' || text.length > 12 || text.startsWith('[') ? english : text;
}

function decimals(value: number, digits: number): string {
    return value.toLocaleString(language.value, { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** {@code bytes} with a binary unit: 999 B, 1.00 KB, 12.3 MB, 1.21 GB. */
export function size(bytes: number): string {
    const units = [
        unit('IDS_BYTESPERSEC', 'B'),
        unit('IDS_KBYTES', 'KB'),
        unit('IDS_MBYTES', 'MB'),
        unit('IDS_GBYTES', 'GB'),
        unit('IDS_TBYTES', 'TB'),
    ];
    if (bytes < 1024) {
        return `${Math.max(0, bytes)} ${units[0]}`;
    }
    let value = bytes;
    let u = 0;
    while (value >= 1024 && u < units.length - 1) {
        value /= 1024;
        u++;
    }
    // The form follows the rounded value: 99.995 shows as 100.0, 999.95 as 1,000.
    if (value >= 999.95) {
        return `${Math.round(value).toLocaleString(language.value)} ${units[u]}`;
    }
    return `${decimals(value, value < 99.995 ? 2 : 1)} ${units[u]}`;
}

/** A rate in the unit that suits it: 12.3 KB/s, 1.26 MB/s, 1.05 GB/s. */
export function rate(bytesPerSecond: number): string {
    const units = [
        unit('IDS_KBYTESPERSEC', 'KB/s'),
        unit('IDS_MBYTESPERSEC', 'MB/s'),
        unit('IDS_GBYTESPERSEC', 'GB/s'),
    ];
    let value = Math.max(0, bytesPerSecond) / 1024;
    if (value < 999.95) {
        return `${decimals(value, 1)} ${units[0]}`;
    }
    let u = 1;
    value /= 1024;
    if (value >= 999.95) {
        u = 2;
        value /= 1024;
    }
    return `${decimals(value, value < 99.995 ? 2 : 1)} ${units[u]}`;
}

/** A duration, the largest unit first: 2 d 3 h, 1:05 h, 4:07 mins, 9 secs. */
export function duration(seconds: number): string {
    const s = Math.max(0, Math.floor(seconds));
    const days = unit('IDS_DAYS', 'd');
    const hours = unit('IDS_HOURS', 'h');
    if (s >= DAY) {
        return `${Math.floor(s / DAY)} ${days} ${Math.floor((s % DAY) / HOUR)} ${hours}`;
    }
    if (s >= HOUR) {
        return `${Math.floor(s / HOUR)}:${twoDigits(Math.floor((s % HOUR) / MINUTE))} ${hours}`;
    }
    if (s >= MINUTE) {
        return `${Math.floor(s / MINUTE)}:${twoDigits(s % MINUTE)} ${unit('IDS_MINS', 'mins')}`;
    }
    return `${s} ${unit('IDS_SECS', 'secs')}`;
}

/** {@code part} of {@code whole} as a percentage with one decimal. */
export function percent(part: number, whole: number): string {
    const ratio = whole <= 0 ? 0 : Math.min(1, part / whole);
    return `${decimals(ratio * 100, 1)} %`;
}

export function count(value: number): string {
    return value.toLocaleString(language.value);
}

function twoDigits(value: number): string {
    return value < 10 ? `0${value}` : String(value);
}
