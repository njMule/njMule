// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';
import type { Schemas } from './api/client';
import { fileLink } from './links';

// The searches and how their results show, as the desktop app's search screen does it.

export type Result = Schemas['SearchResult'];
export type Scope = Schemas['SearchScope'];
export type FileType = '' | 'archive' | 'audio' | 'cd' | 'image' | 'program' | 'video' | 'document';

/** What the filters of a search ask for besides its words. */
export interface Filters {
    type: FileType;
    minMb?: number;
    maxMb?: number;
    availability?: number;
    complete?: number;
    extension: string;
}

/** A search shown in a tab: its title, its words, and its results as the engine gathered them. */
export interface Search {
    /** The engine's search; none for one brought back from the last exit, which the engine no longer runs. */
    id: number | null;
    key: string;
    title: string;
    expression: string;
    scope: Scope;
    results: Result[];
    running: boolean;
}

/** The searches of the window, kept while it moves between places. */
export const searches = ref<Search[]>([]);

// A number field asks for something only with a number in it: one typed and emptied again holds an empty text.
function given(n: number | undefined): n is number {
    return typeof n === 'number' && Number.isFinite(n);
}

/** The words of the search box with the filters as the attributes of the search expression. */
export function expression(words: string, f: Filters): string {
    let e = words.trim();
    if (f.type !== '') {
        e += ` @type=${f.type}`;
    }
    if (given(f.minMb)) {
        e += ` @size>=${f.minMb}m`;
    }
    if (given(f.maxMb)) {
        e += ` @size<=${f.maxMb}m`;
    }
    if (given(f.availability)) {
        e += ` @sources>=${f.availability}`;
    }
    if (given(f.complete)) {
        e += ` @complete>=${f.complete}`;
    }
    const ext = f.extension.trim();
    if (ext !== '') {
        e += ` @ext=${ext.startsWith('.') ? ext.substring(1) : ext}`;
    }
    return e;
}

/** How many of the filters ask for something, for their button. */
export function filterCount(f: Filters): number {
    return [
        f.type !== '',
        given(f.minMb),
        given(f.maxMb),
        given(f.availability),
        given(f.complete),
        f.extension.trim() !== '',
    ].filter((on) => on).length;
}

/** The words of an expression, without its attributes, for a tab's title. */
export function words(expression: string): string {
    return expression
        .split(/\s+/)
        .filter((w) => !w.startsWith('@'))
        .join(' ');
}

/** The eD2K link of a result. */
export function link(r: Pick<Result, 'name' | 'size' | 'hash'>): string {
    return fileLink(r.name, r.size, r.hash);
}

/** How many sources have the whole file, as a percentage of all; unknown where the network does not tell. */
export function completeness(r: Pick<Result, 'sources' | 'completeSources'>): string {
    if (r.completeSources === null) {
        return '?';
    }
    return r.sources > 0 ? `${Math.round((Math.min(r.completeSources, r.sources) * 100) / r.sources)}%` : '0%';
}

/** A playing time as 1:02:03 or 3:25. */
export function playingTime(seconds: number): string {
    const two = (n: number) => (n < 10 ? `0${n}` : String(n));
    const s = Math.max(0, Math.floor(seconds));
    const rest = `${two(Math.floor((s % 3600) / 60))}:${two(s % 60)}`;
    return s >= 3600 ? `${Math.floor(s / 3600)}:${rest}` : `${Math.floor(s / 60)}:${two(s % 60)}`;
}

/** What a result is to us, which tells its colour: ours already, known from before, spam, or new. */
export type Tone = 'ours' | 'known' | 'spam' | 'fresh';

export function tone(r: Pick<Result, 'spam' | 'known'>, downloading: boolean): Tone {
    if (r.spam) {
        return 'spam';
    }
    if (downloading || r.known === 'downloading' || r.known === 'shared') {
        return 'ours';
    }
    return r.known === 'downloaded' || r.known === 'cancelled' ? 'known' : 'fresh';
}

/** How far a new result's colour goes from the text's towards the accent's: one source or none keeps the text's. */
export function shade(sources: number): number {
    return (Math.min(Math.max(sources - 1, 0), 12) * 100) / 13;
}

/** The results whose text in {@code column} holds every word of {@code filter} and none of its words after a minus. */
export function narrowed<T>(results: T[], filter: string, text: (r: T) => string): T[] {
    const all = filter
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    const wanted = all.filter((w) => !w.startsWith('-'));
    const unwanted = all.filter((w) => w.startsWith('-') && w.length > 1).map((w) => w.substring(1));
    if (all.length === 0) {
        return results;
    }
    return results.filter((r) => {
        const value = text(r).toLowerCase();
        return wanted.every((w) => value.includes(w)) && !unwanted.some((w) => value.includes(w));
    });
}

const HISTORY_KEY = 'njmule.search.history';
const HISTORY_SIZE = 30;

/** The searches typed before, the newest first. */
export function history(): string[] {
    try {
        const saved = JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]') as unknown;
        return Array.isArray(saved) ? saved.filter((h): h is string => typeof h === 'string') : [];
    } catch {
        return [];
    }
}

export function remember(query: string): void {
    const next = [query, ...history().filter((h) => h !== query)].slice(0, HISTORY_SIZE);
    try {
        localStorage.setItem(HISTORY_KEY, JSON.stringify(next));
    } catch {
        // Not kept beyond this window.
    }
}
