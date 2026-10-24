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
