// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';

// The languages of the interface, whose translations the build converts to i18n/<tag>.json.
export const LANGUAGES = [
    'en',
    'ca',
    'cs',
    'da',
    'de',
    'et',
    'es',
    'fr',
    'gl',
    'it',
    'lv',
    'lt',
    'hu',
    'nl',
    'nb',
    'pl',
    'pt-BR',
    'pt-PT',
    'ro',
    'sq',
    'sl',
    'fi',
    'sv',
    'vi',
    'tr',
    'el',
    'bg',
    'ru',
    'uk',
    'ar',
    'fa',
    'he',
    'zh-CN',
    'zh-TW',
    'ja',
    'ko',
] as const;

const english = ref<Record<string, string>>({});
const chosen = ref<Record<string, string>>({});
export const language = ref('en');

/** The text of {@code key}; an unknown key shows itself, so that it is noticed. */
export function t(key: string, ...args: unknown[]): string {
    const pattern = chosen.value[key] ?? english.value[key];
    if (pattern === undefined) {
        return `[${key}]`;
    }
    return args.length === 0 ? pattern : format(pattern, args);
}

/** Fills in Java's %s, %d, %.1f, %1$s and %% placeholders, which the texts use. */
export function format(pattern: string, args: unknown[]): string {
    let next = 0;
    return pattern.replace(/%(?:(\d+)\$)?(\.\d+)?([sdfcx%])/g, (whole, position, precision, kind) => {
        if (kind === '%') {
            return '%';
        }
        const index = position === undefined ? next++ : Number(position) - 1;
        if (index >= args.length) {
            return whole;
        }
        const value = args[index];
        if (kind === 'f' && typeof value === 'number') {
            return value.toFixed(precision === undefined ? 6 : Number(precision.substring(1)));
        }
        if (kind === 'd' && typeof value === 'number') {
            return Math.trunc(value).toLocaleString(language.value);
        }
        return String(value);
    });
}

// The best of our languages for the browser's list, by language and country first, then by language alone.
export function bestLanguage(wanted: readonly string[]): string {
    const ours: readonly string[] = LANGUAGES;
    for (const tag of wanted) {
        if (ours.includes(tag)) {
            return tag;
        }
        const base = tag.split('-')[0] ?? '';
        if (ours.includes(base)) {
            return base;
        }
        const regional = ours.find((l) => l.startsWith(base + '-'));
        if (regional !== undefined) {
            return regional;
        }
    }
    return 'en';
}

const LANGUAGE_KEY = 'njmule.language';

/** The language chosen in the options, empty while the system's decides. */
export function keptLanguage(): string {
    try {
        return localStorage.getItem(LANGUAGE_KEY) ?? '';
    } catch {
        return '';
    }
}

/** Keeps {@code tag} as the language of the next windows, empty to follow the system. */
export function keepLanguage(tag: string): void {
    try {
        if (tag === '') {
            localStorage.removeItem(LANGUAGE_KEY);
        } else {
            localStorage.setItem(LANGUAGE_KEY, tag);
        }
    } catch {
        // The system's language then.
    }
}

/**
 * The language of the window: one asked for in the address (?lang=it, kept from then on), the one chosen before, or
 * the best of ours for the system's.
 */
export function windowLanguage(wanted: readonly string[]): string {
    const ours: readonly string[] = LANGUAGES;
    const asked = new URLSearchParams(window.location.search).get('lang');
    try {
        if (asked !== null && ours.includes(asked)) {
            localStorage.setItem(LANGUAGE_KEY, asked);
        }
        const kept = localStorage.getItem(LANGUAGE_KEY);
        if (kept !== null && ours.includes(kept)) {
            return kept;
        }
    } catch {
        // No storage: the address or the system decides.
        if (asked !== null && ours.includes(asked)) {
            return asked;
        }
    }
    return bestLanguage(wanted);
}

async function texts(tag: string): Promise<Record<string, string>> {
    const answer = await fetch(`i18n/${tag}.json`);
    return answer.ok ? ((await answer.json()) as Record<string, string>) : {};
}

/** Loads English, the fallback of every text, and the language chosen. */
export async function loadLanguage(tag: string): Promise<void> {
    if (Object.keys(english.value).length === 0) {
        english.value = await texts('en');
    }
    chosen.value = tag === 'en' ? {} : await texts(tag);
    language.value = tag;
    document.documentElement.lang = tag;
    document.documentElement.dir = ['ar', 'fa', 'he'].includes(tag) ? 'rtl' : 'ltr';
}
