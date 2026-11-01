// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';
import type { Schemas } from './api/client';
import { fileLink } from './links';

// The searches and how their results show.

export type Result = Schemas['SearchResult'];
export type Scope = Schemas['SearchScope'];
export type FileType = '' | 'archive' | 'audio' | 'cd' | 'image' | 'program' | 'video' | 'document';

/** The filters of a search besides its words, as typed in their fields. */
export interface Filters {
    type: FileType;
    minMb: string;
    maxMb: string;
    availability: string;
    complete: string;
    extension: string;
}

export const NO_FILTERS: Filters = { type: '', minMb: '', maxMb: '', availability: '', complete: '', extension: '' };

/** The largest size a filter takes, in MB, and the most sources. */
export const MAX_SIZE_MB = 256 * 1024;
export const MAX_SOURCES = 1_000_000;
export const MAX_EXTENSION = 10;

/** A search shown in a tab: its title, its words, and its results as the engine gathered them. */
export interface Search {
    /** The engine's search; none for one brought back from the last exit, which the engine no longer runs. */
    id: number | null;
    key: string;
    title: string;
    expression: string;
    /** The network searched; an automatic search knows which it picked. */
    scope: Exclude<Scope, 'automatic'>;
    results: Result[];
    running: boolean;
}

/** The searches of the window, kept while it moves between places. */
export const searches = ref<Search[]>([]);

/** A search the engine keeps the results of: one started through the web interface or the API. */
export interface Listed {
    id: number;
    /** What it searched for; empty when not noted. */
    query: string;
    /** Where it searched, as asked; empty when not noted. */
    scope: string;
    running: boolean;
}

/**
 * The tabs of the searches the engine lists, as a guest sees them: those open stay as they are, those the engine
 * forgot go, and the new ones come after them, their results still to read.
 */
export function listedSearches(open: Search[], listed: Listed[]): Search[] {
    const ids = new Set(open.map((s) => s.id));
    return [
        ...open.filter((s) => listed.some((l) => l.id === s.id)),
        ...listed
            .filter((l) => !ids.has(l.id))
            .map((l): Search => ({
                id: l.id,
                key: `e${l.id}`,
                title: '',
                expression: l.query,
                scope: l.scope === 'global' || l.scope === 'kad' ? l.scope : 'server',
                results: [],
                running: l.running,
            })),
    ];
}

/** Whether {@code text} is empty or a whole number from {@code min} to {@code max}. */
export function optionalNumber(text: string, min: number, max: number): boolean {
    const n = text.trim();
    return n === '' || (/^\d+$/.test(n) && Number(n) >= min && Number(n) <= max);
}

/** The least a maximum size takes: never below the minimum typed. */
export function lowestMaxSize(minMb: string): number {
    return minMb.trim() !== '' && optionalNumber(minMb, 0, MAX_SIZE_MB) ? Math.max(1, Number(minMb)) : 1;
}

/** Whether {@code text} is empty or a file extension of letters and digits, which may start with a dot. */
export function optionalExtension(text: string): boolean {
    const ext = text.trim();
    return ext === '' || /^\.?[\p{L}\p{Nd}]+$/u.test(ext);
}

/** The words of the search box with the filters as the attributes of the search expression. */
export function expression(words: string, f: Filters): string {
    let e = words.trim();
    const number = (text: string) => Number(text.trim());
    if (f.type !== '') {
        e += ` @type=${f.type}`;
    }
    if (f.minMb.trim() !== '') {
        e += ` @size>=${number(f.minMb)}m`;
    }
    if (f.maxMb.trim() !== '') {
        e += ` @size<=${number(f.maxMb)}m`;
    }
    if (f.availability.trim() !== '') {
        e += ` @sources>=${number(f.availability)}`;
    }
    if (f.complete.trim() !== '') {
        e += ` @complete>=${number(f.complete)}`;
    }
    const ext = f.extension.trim();
    if (ext !== '') {
        e += ` @ext=${ext.startsWith('.') ? ext.substring(1) : ext}`;
    }
    return e;
}

/** How many of the filters ask for something, for their button. */
export function filterCount(f: Filters): number {
    return (
        [f.minMb, f.maxMb, f.availability, f.complete, f.extension].filter((v) => v.trim() !== '').length +
        (f.type === '' ? 0 : 1)
    );
}

// A big server is worth more than Kad, but only in a short server list: a long one may be full of fake servers.
const BIG_SERVER_MIN_USERS = 40_000;
const BIG_SERVER_MAX_USERS = 2_000_000;
const BIG_SERVER_MIN_FILES = 5_000_000;
const SHORT_SERVER_LIST = 40;

/**
 * Where an automatic search goes: the network connected, and Kad when both are, unless the server is a static one or
 * a big one in a short list; nowhere with neither.
 */
export function automaticScope(
    serverConnected: boolean,
    kadConnected: boolean,
    server: { users: number; files: number; static: boolean } | null,
    knownServers: number,
): 'server' | 'kad' | undefined {
    if (!serverConnected) {
        return kadConnected ? 'kad' : undefined;
    }
    const big =
        server !== null &&
        server.users > BIG_SERVER_MIN_USERS &&
        server.users < BIG_SERVER_MAX_USERS &&
        server.files > BIG_SERVER_MIN_FILES &&
        knownServers < SHORT_SERVER_LIST;
    return !kadConnected || server?.static === true || big ? 'server' : 'kad';
}

/** The words of an expression, without its attributes, for a tab's title. */
export function words(expression: string): string {
    return expression.replace(/\s*@\S+/g, '').trim();
}

/** The results as they are now, in the order they first came: those known keep their place, new ones go last. */
export function merged(before: Result[], now: Result[]): Result[] {
    const fresh = new Map(now.map((r) => [r.hash, r]));
    const known = new Set(before.map((r) => r.hash));
    return [...before.flatMap((r) => fresh.get(r.hash) ?? []), ...now.filter((r) => !known.has(r.hash))];
}

/** A row of a search's results: a file, or, under an open file, one of the names it was found under. */
export interface ResultRow {
    id: string;
    /** The file, or the file as found under the row's name. */
    result: Result;
    file: Result;
    isName: boolean;
    /** The place of the row while no column sorts: files as they came, each with its names under it. */
    place: number;
}

/** Whether a result was found under several names, which its row can open to show. */
export function hasNames(r: Pick<Result, 'names'>): boolean {
    return r.names.length > 1;
}

/**
 * The rows of {@code results} in their order, one for each file and, under each file of {@code open} found under
 * several names, one for each name: the file as found under that name, with its own sources.
 */
export function resultRows(results: Result[], open: ReadonlySet<string> = new Set()): ResultRow[] {
    return results
        .flatMap((file) => {
            const row = { id: file.hash, result: file, file, isName: false };
            if (!open.has(file.hash) || !hasNames(file)) {
                return [row];
            }
            return [
                row,
                ...file.names.map((n) => ({
                    // A name has no line break: it keeps the row of a name apart from its file's.
                    id: `${file.hash}\n${n.name}`,
                    result: {
                        ...file,
                        name: n.name,
                        sources: n.sources,
                        completeSources: n.completeSources,
                        names: [],
                    },
                    file,
                    isName: true,
                })),
            ];
        })
        .map((row, place) => ({ ...row, place }));
}

/** A column a list is sorted by, and which way. */
export interface ColumnSort {
    id: string;
    desc: boolean;
}

/** How the values of a column compare, the least first; those a result lacks go last whichever way it sorts. */
export interface ColumnOrder {
    compare: (a: Result, b: Result) => number;
    missing?: (r: Result) => boolean;
}

// Columns chosen before that still break ties.
const EARLIER = 3;

function sameSorting(a: ColumnSort[], b: ColumnSort[]): boolean {
    return a.length === b.length && a.every((s, i) => s.id === b[i]?.id && s.desc === b[i]?.desc);
}

/** What a list was sorted by: the columns chosen before those that sort it now still break their ties. */
export class SortMemory {
    private chosen: ColumnSort[] = [];
    private before: ColumnSort[] = [];

    /** The columns chosen before {@code now}, the latest first, each the way it sorted when it was left. */
    earlier(now: ColumnSort[]): ColumnSort[] {
        if (!sameSorting(now, this.chosen)) {
            for (const left of this.chosen.filter((c) => !now.some((n) => n.id === c.id))) {
                this.before = [left, ...this.before.filter((e) => e.id !== left.id)];
            }
            this.before = this.before.filter((e) => !now.some((n) => n.id === e.id)).slice(0, EARLIER);
            this.chosen = now.map((s) => ({ ...s }));
        }
        return this.before;
    }
}

/**
 * The order of the rows of a list sorted by {@code sorting}: spam last in either direction, then by the columns
 * chosen and those chosen before, then as the files came. The names of a file stay under it: by name when that column
 * sorts first, else most sources first, turned round when the first column sorts the other way.
 */
export function rowOrder(
    sorting: ColumnSort[],
    earlier: ColumnSort[],
    columns: Record<string, ColumnOrder>,
): (a: ResultRow, b: ResultRow) => number {
    const by = [...sorting, ...earlier].flatMap((s) => {
        const column = columns[s.id];
        return column === undefined ? [] : [{ column, way: s.desc ? -1 : 1 }];
    });
    const files = (a: Result, b: Result): number => {
        if (a.spam !== b.spam) {
            return a.spam ? 1 : -1;
        }
        for (const { column, way } of by) {
            const noA = column.missing?.(a) ?? false;
            const noB = column.missing?.(b) ?? false;
            const order = noA || noB ? Number(noA) - Number(noB) : way * column.compare(a, b);
            if (order !== 0) {
                return order;
            }
        }
        return 0;
    };
    const first = sorting[0];
    const names = (a: Result, b: Result): number =>
        (first?.desc ? -1 : 1) * (first?.id === 'name' ? (columns['name']?.compare(a, b) ?? 0) : b.sources - a.sources);
    return (a, b) => {
        if (a.file.hash !== b.file.hash) {
            return files(a.file, b.file) || a.place - b.place;
        }
        if (a.isName !== b.isName) {
            return a.isName ? 1 : -1;
        }
        return (a.isName ? names(a.result, b.result) : 0) || a.place - b.place;
    };
}

/** The type of a file, then its extension, which files without one follow. */
export function kindOrder(a: Pick<Result, 'type' | 'name'>, b: Pick<Result, 'type' | 'name'>): number {
    const typeA = a.type ?? '';
    const typeB = b.type ?? '';
    if (typeA !== typeB) {
        return typeA < typeB ? -1 : 1;
    }
    const extA = extension(a.name);
    const extB = extension(b.name);
    if ((extA === '') !== (extB === '')) {
        return extA === '' ? 1 : -1;
    }
    const x = extA.toLowerCase();
    const y = extB.toLowerCase();
    return x < y ? -1 : x > y ? 1 : 0;
}

function extension(name: string): string {
    const dot = name.lastIndexOf('.');
    return dot < 0 ? '' : name.substring(dot + 1);
}

/** The eD2K link of a result, which carries its AICH hash so that a download from it can check and repair the file. */
export function link(r: Pick<Result, 'name' | 'size' | 'hash' | 'aich'>): string {
    return fileLink(r.name, r.size, r.hash, r.aich);
}

// The size of a part: a file of one part is whole wherever it is shared.
const PART_SIZE = 9_728_000;

/** The share of a result's sources with the whole file, in percent; -1 where the network does not tell (Kad). */
export function completePercent(r: Pick<Result, 'size' | 'sources' | 'completeSources'>): number {
    if (r.completeSources === null) {
        return r.size <= PART_SIZE ? 100 : -1;
    }
    return r.sources <= 0 ? 0 : Math.trunc((100 * r.completeSources) / r.sources);
}

/** The share of complete sources as shown: a percentage, "?" when unknown, or {@code yes} when whole without a count. */
export function completeness(r: Pick<Result, 'size' | 'sources' | 'completeSources'>, yes: string): string {
    if (r.completeSources === null) {
        return r.size <= PART_SIZE ? yes : '?';
    }
    if (r.completeSources > 0) {
        return r.sources > 0 ? `${completePercent(r)}%` : yes;
    }
    return '0%';
}

/** Whether a result is a song or a film: its type says so, or it has media details. */
export function isMedia(r: Pick<Result, 'type' | 'media'>): boolean {
    const type = (r.type ?? '').toLowerCase();
    return type === 'audio' || type === 'video' || r.media !== undefined;
}

/** A playing time as 1:02:03 or 3:25. */
export function playingTime(seconds: number): string {
    const two = (n: number) => (n < 10 ? `0${n}` : String(n));
    const s = Math.max(0, Math.floor(seconds));
    const rest = `${two(Math.floor((s % 3600) / 60))}:${two(s % 60)}`;
    return s >= 3600 ? `${Math.floor(s / 3600)}:${rest}` : `${Math.floor(s / 60)}:${two(s % 60)}`;
}

/** What we did or do with a result's file, in the order the states go when sorted; spam last. */
export const KNOWN = ['downloading', 'shared', 'downloaded', 'cancelled', 'spam'] as const;
export type Known = (typeof KNOWN)[number];

/** The files of ours now, by hash in capitals: those downloading and those shared. */
export interface Ours {
    downloading: Set<string>;
    shared: Set<string>;
}

/** What a result is to us: what we did with its file tells more than a spam mark. */
export function knownOf(r: Pick<Result, 'hash' | 'known' | 'spam'>, ours: Ours): Known | undefined {
    const hash = r.hash.toUpperCase();
    if (ours.downloading.has(hash) || r.known === 'downloading') {
        return 'downloading';
    }
    if (ours.shared.has(hash) || r.known === 'shared') {
        return 'shared';
    }
    if (r.known === 'downloaded' || r.known === 'cancelled') {
        return r.known;
    }
    return r.spam ? 'spam' : undefined;
}

/** What a result is to us, which tells its colour: ours already, known from before, spam, or new. */
export type Tone = 'ours' | 'known' | 'spam' | 'fresh';

export function tone(known: Known | undefined): Tone {
    switch (known) {
        case 'downloading':
        case 'shared':
            return 'ours';
        case 'downloaded':
        case 'cancelled':
            return 'known';
        case 'spam':
            return 'spam';
        default:
            return 'fresh';
    }
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
const HISTORY_SIZE = 10;

/** The searches typed before, the newest first. */
export function history(): string[] {
    try {
        const saved = JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]') as unknown;
        return Array.isArray(saved)
            ? saved.filter((h): h is string => typeof h === 'string').slice(0, HISTORY_SIZE)
            : [];
    } catch {
        return [];
    }
}

export function remember(query: string): void {
    keepHistory([query, ...history().filter((h) => h !== query)]);
}

/** Starts an empty history with {@code typed}, the newest first, such as the searches of the eMule imported. */
export function seedHistory(typed: string[]): void {
    if (history().length === 0) {
        keepHistory([...new Set(typed.map((h) => h.trim()).filter((h) => h !== ''))]);
    }
}

function keepHistory(searches: string[]): void {
    try {
        localStorage.setItem(HISTORY_KEY, JSON.stringify(searches.slice(0, HISTORY_SIZE)));
    } catch {
        // Not kept beyond this window.
    }
}
