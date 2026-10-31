// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The kinds of values the options ask for, as the desktop app's Inputs: the characters each field keeps, the longest
// text it takes, what it takes in words and whether a text is one. The checks are the engine's, so that a value is
// refused while it is typed rather than when it is saved.

/** A field's rules: the characters it keeps, its longest text, its hint and its check. */
export interface InputRules {
    allowed: (c: string) => boolean;
    max: number;
    /** What the field takes, in words: its tooltip, and the line under a wrong value. */
    hint: string;
    /** Whether the text, stripped, is a value. */
    valid: (text: string) => boolean;
    /** The tooltip for a text, naming the line to fix when there is one. */
    tip?: (text: string) => string;
}

type Translate = (key: string, ...args: unknown[]) => string;

export const MAX_RATE_KIBPS = 10_000_000;
const MAX_HOST = 253;
const MAX_TEXT = 4096;
const MAX_LIST_ADDRESS = 2048;
export const MAX_IP_FILTER_LISTS = 16;
export const MAX_WHITELIST = 16_384;
export const MAX_WEBHOOK_URL = 2048;
export const MAX_NIGHT_HOURS = 13;

/** Anything but a control character, as Java's Character.isISOControl tells them. */
export function printable(c: string): boolean {
    return !/[\u0000-\u001f\u007f-\u009f]/.test(c);
}

export function digit(c: string): boolean {
    return c >= '0' && c <= '9';
}

/** A character of a text area: a printable one or a line break. */
export function lineChar(c: string): boolean {
    return c === '\n' || printable(c);
}

// Embeddings, overrides and isolates would make a name read otherwise than it is: the engine refuses them.
export function nickChar(c: string): boolean {
    const code = c.codePointAt(0) ?? 0;
    return printable(c) && !(code >= 0x202a && code <= 0x202e) && !(code >= 0x2066 && code <= 0x2069);
}

// Host names, IPv4 and IPv6 addresses, with a port.
export function addressChar(c: string): boolean {
    return /^[A-Za-z0-9.\-:[\]]$/.test(c);
}

export function timeChar(c: string): boolean {
    return digit(c) || c === ':';
}

export function rateChar(c: string): boolean {
    return digit(c) || c === '.' || c === ',';
}

export function nightChar(c: string): boolean {
    return digit(c) || c === ':' || c === '-' || c === ' ';
}

/**
 * What a field keeps of {@code next}, typed or pasted over {@code previous}: the characters it may hold, and the
 * previous text when the change would make it too long.
 */
export function keepTyped(previous: string, next: string, allowed: (c: string) => boolean, max: number): string {
    const kept = [...next].filter(allowed).join('');
    return kept.length > max ? previous : kept;
}

function lines(text: string): string[] {
    return text.split(/\r\n|\r|\n/);
}

// ------------------------------------------------------------------ addresses

const OCTET = '(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)';
const IPV4 = new RegExp(`^${OCTET}(\\.${OCTET}){3}$`);

function isIpv6(host: string): boolean {
    if (!host.includes(':')) {
        return false;
    }
    try {
        // The URL parser reads IPv6 addresses as RFC 4291 writes them.
        new URL(`http://[${host}]/`);
        return true;
    } catch {
        return false;
    }
}

// A domain name as Guava's InternetDomainName.isValid reads it, which the engine uses.
function isDomainName(host: string): boolean {
    const name = host.endsWith('.') ? host.slice(0, -1) : host;
    if (name.length > MAX_HOST) {
        return false;
    }
    const parts = name.split('.');
    if (parts.length > 127) {
        return false;
    }
    return parts.every((part, i) => {
        if (part.length < 1 || part.length > 63) {
            return false;
        }
        const ascii = part.replace(/[^\u0000-\u007f]/g, '');
        if (!/^[A-Za-z0-9_-]*$/.test(ascii) || /^[-_]|[-_]$/.test(part)) {
            return false;
        }
        return !(i === parts.length - 1 && digit(part.charAt(0)));
    });
}

/** A host name or an IP address, as the engine accepts them. */
export function isValidHost(host: string): boolean {
    return IPV4.test(host) || isIpv6(host) || isDomainName(host);
}

// What Java's URI refuses: spaces and the characters RFC 2396 leaves out, and a % not followed by two hex digits.
function uriParts(text: string): URL | null {
    if (/[\s"<>\\^`{|}]/.test(text) || /%(?![0-9A-Fa-f]{2})/.test(text)) {
        return null;
    }
    try {
        return new URL(text);
    } catch {
        return null;
    }
}

/** An http or https address with a host. */
export function isWebAddress(text: string): boolean {
    const url = uriParts(text);
    return url !== null && (url.protocol === 'http:' || url.protocol === 'https:') && url.hostname !== '';
}

/** Nothing, or an https address with a host, at most 2048 characters: a web hook. */
export function isWebhookUrl(text: string): boolean {
    if (text.trim() === '') {
        return true;
    }
    if (text.length > MAX_WEBHOOK_URL) {
        return false;
    }
    const url = uriParts(text.trim());
    return url !== null && url.protocol === 'https:' && url.hostname !== '';
}

/** The http and https addresses among the lines of {@code text}, as the server lists take them. */
export function webAddresses(text: string): string[] {
    return lines(text)
        .map((l) => l.trim())
        .filter((l) => l !== '' && isWebAddress(l));
}

// ------------------------------------------------------------------ the IP filter

function listLines(text: string): string[] {
    return lines(text)
        .map((l) => l.trim())
        .filter((l) => l !== '');
}

/** The first line of {@code text} that is not the http or https address of a list, if any. */
export function invalidListEntry(text: string): string | undefined {
    return listLines(text).find((l) => l.length > MAX_LIST_ADDRESS || !isWebAddress(l));
}

/** Whether {@code text} names no more lists than a filter takes. */
export function fitsListCount(text: string): boolean {
    return listLines(text).length <= MAX_IP_FILTER_LISTS;
}

// An IPv4 address at {@code from}, as ipfilter.dat writes them: four parts of up to three digits.
function ipAt(line: string, from: number): [number, number] | null {
    let value = 0;
    let i = from;
    for (let part = 0; part < 4; part++) {
        if (part > 0) {
            if (line.charAt(i) !== '.') {
                return null;
            }
            i++;
        }
        let digits = 0;
        let octet = 0;
        while (i < line.length && digits < 3 && digit(line.charAt(i))) {
            octet = octet * 10 + Number(line.charAt(i));
            i++;
            digits++;
        }
        if (digits === 0 || octet > 255) {
            return null;
        }
        value = value * 256 + octet;
    }
    return [value, i];
}

function skipSpaces(line: string, from: number): number {
    let i = from;
    while (line.charAt(i) === ' ' || line.charAt(i) === '\t') {
        i++;
    }
    return i;
}

// {@code a.b.c.d - e.f.g.h} at {@code from}, spaces allowed: its start and end, or null.
function rangeAt(line: string, from: number): [number, number] | null {
    const start = ipAt(line, skipSpaces(line, from));
    if (start === null) {
        return null;
    }
    const dash = skipSpaces(line, start[1]);
    if (line.charAt(dash) !== '-') {
        return null;
    }
    const end = ipAt(line, skipSpaces(line, dash + 1));
    return end === null ? null : [start[0], end[0]];
}

// One ipfilter.dat or PeerGuardian line holding a range, as the engine reads a filter file.
function filterLine(text: string): boolean {
    let line = text.trim();
    if (line === '' || line.startsWith('#') || line.startsWith('/') || line.length < 15) {
        return false;
    }
    if (line.includes('>') && line.includes('<')) {
        line = line.substring(line.lastIndexOf('>') + 1);
    }
    let range = rangeAt(line, 0);
    if (range === null) {
        const colon = line.indexOf(':');
        if (colon < 0 || rangeAt(line, colon + 1) === null) {
            return false;
        }
        range = rangeAt(line, line.lastIndexOf(':') + 1);
    }
    return range !== null && range[0] <= range[1];
}

/** Whether {@code entry} is an address, a CIDR block or an ipfilter.dat range. */
export function isWhitelistEntry(entry: string): boolean {
    if (/[-,:]/.test(entry)) {
        return filterLine(entry);
    }
    const slash = entry.indexOf('/');
    if (slash < 0) {
        return filterLine(`${entry} - ${entry}`);
    }
    const prefix = entry.substring(slash + 1).trim();
    const address = entry.substring(0, slash).trim();
    return /^\d{1,2}$/.test(prefix) && Number(prefix) <= 32 && address !== '' && filterLine(`${address} - ${address}`);
}

/** The first entry of a whitelist that is none of its forms, if any; lines starting with # or // are comments. */
export function invalidWhitelistEntry(text: string): string | undefined {
    return lines(text)
        .map((l) => l.trim())
        .filter((l) => l !== '' && !l.startsWith('#') && !l.startsWith('//'))
        .find((e) => !isWhitelistEntry(e));
}

// ------------------------------------------------------------------ times and command lines

const HOURS = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Night hours as 03:00-05:00, which must not start when they end. */
export function isNightHours(text: string): boolean {
    const parts = text.trim().split('-');
    if (parts.length !== 2) {
        return false;
    }
    const [start, end] = parts.map((p) => p.trim()) as [string, string];
    return HOURS.test(start) && HOURS.test(end) && start !== end;
}

/** A time of the day as H:mm, the hour in one or two digits, written as hh:mm; null when it is none. */
export function timeOfDay(text: string): string | null {
    const found = /^(\d{1,2}):([0-5]\d)$/.exec(text.trim());
    if (found === null || Number(found[1]) > 23) {
        return null;
    }
    return `${found[1]!.padStart(2, '0')}:${found[2]}`;
}

/** Whether every quote of a command line is closed: an argument with spaces goes in double or single quotes. */
export function isCommandLine(text: string): boolean {
    let quote = '';
    for (let i = 0; i < text.length; i++) {
        const c = text.charAt(i);
        if (quote !== '') {
            if (c === quote && text.charAt(i + 1) === quote) {
                i++;
            } else if (c === quote) {
                quote = '';
            }
        } else if (c === '"' || c === "'") {
            quote = c;
        }
    }
    return quote === '';
}

/** Nothing, or the full path of a file on the engine's computer, Windows or not. */
/** Whether {@code text} is the full path of a sound the window plays, a WAV or MP3 file, or empty for none. */
export function isSoundFile(text: string): boolean {
    return text.trim() === '' || (isFullPath(text) && SOUND_FILE.test(text.trim()));
}

const SOUND_FILE = /\.(wav|mp3)$/i;

export function isFullPath(text: string): boolean {
    return text === '' || /^([A-Za-z]:[\\/]|[\\/]{2}|\/)/.test(text);
}

// ------------------------------------------------------------------ speeds

/** A unit a speed is typed in, with the bytes per second one of it is. */
export type RateUnit = 'KB' | 'MB' | 'MBIT';

const UNIT_BYTES: Record<RateUnit, number> = { KB: 1024, MB: 1024 * 1024, MBIT: 125_000 };
export const MAX_RATE_TEXT = 12;

/** The KiB/s of a number typed in {@code unit}: undefined for nothing typed, null when it is not a speed. */
export function rateKib(typed: string, unit: RateUnit, min: number): number | undefined | null {
    const text = typed.trim();
    if (text === '') {
        return undefined;
    }
    const decimal = text.replaceAll(',', '.');
    if (!/^(\d+\.?\d*|\.\d+)$/.test(decimal)) {
        return null;
    }
    const kib = Math.round((Number(decimal) * UNIT_BYTES[unit]) / 1024);
    return kib < min || kib > MAX_RATE_KIBPS ? null : kib;
}

// The language's decimal mark when it is a comma; a point otherwise, which the fields take in every language.
function decimalMark(locale: string): string {
    try {
        return (1.5).toLocaleString(locale).includes(',') ? ',' : '.';
    } catch {
        return '.';
    }
}

/** {@code kib} in {@code unit}, as a field shows it: two decimals at most, one for a line, with the language's mark. */
export function rateText(kib: number, unit: RateUnit, locale: string): string {
    const scale = unit === 'MBIT' ? 10 : 100;
    const value = Math.round(((kib * 1024) / UNIT_BYTES[unit]) * scale) / scale;
    return String(value).replace('.', decimalMark(locale));
}

/** The unit a limit of {@code kib} shows in: MB/s from 1024 KB/s on. */
export function suitedUnit(kib: number): RateUnit {
    return kib >= 1024 ? 'MB' : 'KB';
}

/** The text of a field typed in {@code from}, written again in {@code to}; as it was when it is no speed. */
export function convertRate(typed: string, from: RateUnit, to: RateUnit, locale: string): string {
    const kib = rateKib(typed, from, 0);
    return kib === null || kib === undefined ? typed : rateText(kib, to, locale);
}

// ------------------------------------------------------------------ the rules of each kind of field

function digits(max: number): number {
    return String(max).length;
}

/** A whole number in [min, max]. */
export function numberRules(min: number, max: number, t: Translate): InputRules {
    return {
        allowed: digit,
        max: digits(max),
        hint: t('nj.input.number', min, max),
        valid: (text) => /^\d+$/.test(text) && Number(text) >= min && Number(text) <= max,
    };
}

/** Nothing, or a whole number in [min, max]. */
export function optionalNumberRules(min: number, max: number, t: Translate): InputRules {
    return {
        allowed: digit,
        max: digits(max),
        hint: t('nj.input.optionalNumber', min, max),
        valid: (text) => text === '' || (/^\d+$/.test(text) && Number(text) >= min && Number(text) <= max),
    };
}

/** Printable text of at most {@code max} characters. */
export function textRules(max: number, t: Translate): InputRules {
    return { allowed: printable, max, hint: t('nj.input.optionalName', max), valid: () => true };
}

/** A user name of 1 to {@code max} printable characters, without text direction controls. */
export function nickRules(max: number, t: Translate): InputRules {
    return { allowed: nickChar, max, hint: t('nj.input.name', max), valid: (text) => text !== '' };
}

/** A host name or an IP address; empty only while not {@code required}. */
export function hostRules(required: boolean, t: Translate): InputRules {
    return {
        allowed: addressChar,
        max: MAX_HOST,
        hint: t('nj.input.host'),
        valid: (text) => (text === '' ? !required : isValidHost(text)),
    };
}

/** A time of the day, H:mm. */
export function timeRules(t: Translate): InputRules {
    return { allowed: timeChar, max: 5, hint: t('nj.input.time'), valid: (text) => timeOfDay(text) !== null };
}

/** A speed in [min KiB/s, the most]; nothing too when {@code optional}. */
export function rateRules(unit: RateUnit, min: number, optional: boolean, t: Translate, rate: (b: number) => string) {
    return {
        allowed: rateChar,
        max: MAX_RATE_TEXT,
        hint: t('nj.input.rate', rate(min * 1024), rate(MAX_RATE_KIBPS * 1024)),
        valid: (text: string) => {
            const kib = rateKib(text, unit, min);
            return kib !== null && (optional || kib !== undefined);
        },
    } satisfies InputRules;
}

/**
 * The rules of the checks the options name; a folder is checked by {@code options.folder}, which knows what a path
 * names on the engine's computer.
 */
export function checkRules(
    check: string,
    t: Translate,
    options: { max?: number; required?: boolean; placeholders?: string[]; folder?: (text: string) => boolean } = {},
): InputRules {
    switch (check) {
        case 'nick':
            return nickRules(options.max ?? 50, t);
        case 'host':
            return hostRules(options.required ?? false, t);
        case 'webhook':
            return { allowed: printable, max: MAX_WEBHOOK_URL, hint: 'https://ntfy.sh/…', valid: isWebhookUrl };
        case 'nightHours':
            return {
                allowed: nightChar,
                max: MAX_NIGHT_HOURS,
                hint: t('nj.update.nightHours.hint'),
                valid: isNightHours,
            };
        case 'soundFile':
            return { allowed: printable, max: MAX_TEXT, hint: t('nj.input.soundFile'), valid: isSoundFile };
        case 'folder':
            return {
                allowed: printable,
                max: MAX_TEXT,
                hint: t('nj.input.folder'),
                valid: (text) => options.folder?.(text) ?? true,
            };
        case 'commandLine':
            return {
                allowed: printable,
                max: MAX_TEXT,
                hint: t('nj.input.commandLine', (options.placeholders ?? []).join(' ')),
                valid: isCommandLine,
            };
        case 'whitelist': {
            const hint = t('nj.settings.whitelistHint');
            return {
                allowed: lineChar,
                max: MAX_WHITELIST,
                hint,
                valid: (text) => invalidWhitelistEntry(text) === undefined,
                tip: (text) => {
                    const entry = invalidWhitelistEntry(text);
                    return entry === undefined ? hint : `${hint}\n${t('nj.settings.whitelistInvalid', entry)}`;
                },
            };
        }
        case 'lists': {
            const hint = t('nj.settings.ipFilterListsHint');
            return {
                allowed: lineChar,
                max: MAX_IP_FILTER_LISTS * (MAX_LIST_ADDRESS + 1),
                hint,
                valid: (text) => invalidListEntry(text) === undefined && fitsListCount(text),
                tip: (text) => {
                    const entry = invalidListEntry(text);
                    return entry === undefined ? hint : `${hint}\n${t('nj.settings.ipFilterListInvalid', entry)}`;
                },
            };
        }
        default:
            return textRules(options.max ?? MAX_TEXT, t);
    }
}
