// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';

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

export interface Result {
    hash: string;
    name: string;
    size: number;
    sources: number;
    completeSources: number | null;
    type?: string;
    spamRating: number;
    spam: boolean;
    names: { name: string; sources: number; completeSources: number | null }[];
    known?: 'downloading' | 'shared' | 'downloaded' | 'cancelled';
    media?: { artist: string; album: string; title: string; length: number; bitrate: number; codec: string };
}

/** A playing time as 1:02:03 or 3:25. */
export function playingTime(seconds: number): string {
    const two = (n: number) => (n < 10 ? `0${n}` : String(n));
    const s = Math.max(0, Math.floor(seconds));
    const rest = `${two(Math.floor((s % 3600) / 60))}:${two(s % 60)}`;
    return s >= 3600 ? `${Math.floor(s / 3600)}:${rest}` : `${Math.floor(s / 60)}:${two(s % 60)}`;
}

/** A search shown in a tab: its words, and its results as the engine gathered them. */
export interface Search {
    id: number;
    expression: string;
    results: Result[];
    // Whether more results may come.
    running?: boolean;
}

/** The searches of this page, kept while it moves between places. */
export const searches = ref<Search[]>([]);

/** The words of the search box with the filters as the attributes of the search expression. */
export function expression(words: string, f: Filters): string {
    let e = words.trim();
    if (f.type !== '') {
        e += ` @type=${f.type}`;
    }
    if (f.minMb !== undefined) {
        e += ` @size>=${f.minMb}m`;
    }
    if (f.maxMb !== undefined) {
        e += ` @size<=${f.maxMb}m`;
    }
    if (f.availability !== undefined) {
        e += ` @sources>=${f.availability}`;
    }
    if (f.complete !== undefined) {
        e += ` @complete>=${f.complete}`;
    }
    const ext = f.extension.trim();
    if (ext !== '') {
        e += ` @ext=${ext.startsWith('.') ? ext.substring(1) : ext}`;
    }
    return e;
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
    return `ed2k://|file|${encodeURIComponent(r.name)}|${r.size}|${r.hash}|/`;
}

/** How many sources have the whole file, as a percentage of all; a file of one part is complete wherever it is. */
export function completeness(r: Pick<Result, 'sources' | 'completeSources'>): string {
    if (r.completeSources === null) {
        return '?';
    }
    return r.sources > 0 ? `${Math.round((Math.min(r.completeSources, r.sources) * 100) / r.sources)}%` : '0%';
}

/** What a result is to us, which tells its colour as in the desktop app. */
export type Tone = 'ours' | 'before' | 'spam' | 'fresh';

/**
 * Ours already when downloading or shared, known from before when downloaded or cancelled, spam, or new; {@code
 * downloading} also says whether its file is downloading now.
 */
export function tone(r: Pick<Result, 'spam' | 'known'>, downloading: boolean): Tone {
    if (r.spam) {
        return 'spam';
    }
    if (downloading || r.known === 'downloading' || r.known === 'shared') {
        return 'ours';
    }
    return r.known === 'downloaded' || r.known === 'cancelled' ? 'before' : 'fresh';
}

/** How far towards the accent's colour a new result goes: none with one source, a thirteenth more for each other. */
export function shade(sources: number): number {
    return (Math.min(Math.max(sources - 1, 0), 12) * 100) / 13;
}
