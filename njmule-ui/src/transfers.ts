// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { IconName } from './icons';
import type { Download, Source } from './model/engine';

// How the transfers show a download and what can be done to it, as the desktop app does.

/** The state a download shows: a running one is downloading, waiting for its sources or without any. */
export type Shown = 'downloading' | 'waiting' | 'noSources' | 'paused' | 'stopped' | 'noSpace' | 'error' | 'complete';

export function shown(d: Pick<Download, 'status' | 'transferring' | 'sources'>): Shown {
    switch (d.status) {
        case 'downloading':
            return d.transferring > 0 ? 'downloading' : d.sources > 0 ? 'waiting' : 'noSources';
        case 'no_space':
            return 'noSpace';
        default:
            return d.status;
    }
}

/** The tone of a state, for its chip and its bar: pastel, saturated only for real problems. */
export type Tone = 'accent' | 'warning' | 'danger' | 'success' | 'neutral';

export const STATES: Record<Shown, { key: string; tone: Tone; icon: IconName }> = {
    downloading: { key: 'IDS_DOWNLOADING', tone: 'accent', icon: 'ARROW_DOWN' },
    waiting: { key: 'IDS_WAITING', tone: 'neutral', icon: 'CLOCK' },
    noSources: { key: 'nj.transfers.stateNoSources', tone: 'warning', icon: 'NO_SOURCES' },
    paused: { key: 'IDS_PAUSED', tone: 'neutral', icon: 'PAUSE' },
    stopped: { key: 'IDS_STOPPED', tone: 'neutral', icon: 'STOP' },
    noSpace: { key: 'IDS_INSUFFICIENT', tone: 'danger', icon: 'ERROR' },
    error: { key: 'IDS_ERRORLIKE', tone: 'danger', icon: 'ERROR' },
    complete: { key: 'IDS_COMPLETE', tone: 'success', icon: 'CHECK_CIRCLE' },
};

/** The states the chips narrow the downloads to; paused and stopped go together, as a full disk and other errors do. */
export type StateFilter = 'all' | 'downloading' | 'waiting' | 'noSources' | 'paused' | 'errors' | 'complete';

export const STATE_FILTERS: { value: StateFilter; key: string }[] = [
    { value: 'all', key: 'IDS_ALL' },
    { value: 'downloading', key: 'IDS_DOWNLOADING' },
    { value: 'waiting', key: 'IDS_WAITING' },
    { value: 'noSources', key: 'nj.transfers.stateNoSources' },
    { value: 'paused', key: 'IDS_PAUSED' },
    { value: 'errors', key: 'IDS_ERRORLIKE' },
    { value: 'complete', key: 'IDS_COMPLETE' },
];

export function keeps(filter: StateFilter, d: Download): boolean {
    const state = shown(d);
    switch (filter) {
        case 'all':
            return true;
        case 'paused':
            return state === 'paused' || state === 'stopped';
        case 'errors':
            return state === 'noSpace' || state === 'error';
        default:
            return state === filter;
    }
}

/** Downloads whose name holds every word of {@code text}, in any case. */
export function named(text: string): (d: Pick<Download, 'name'>) => boolean {
    const words = text
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return (d) => words.every((w) => d.name.toLowerCase().includes(w));
}

/** Seconds left at the current rate; -1 when nothing arrives. */
export function remaining(d: Pick<Download, 'size' | 'completed' | 'bytesPerSecond'>): number {
    const left = d.size - d.completed;
    if (left <= 0) {
        return 0;
    }
    return d.bytesPerSecond <= 0 ? -1 : Math.floor(left / d.bytesPerSecond);
}

/** What a download still lacks, sorted by the time and then the bytes. */
export function left(d: Pick<Download, 'size' | 'completed' | 'bytesPerSecond'>): { seconds: number; bytes: number } {
    return { seconds: remaining(d), bytes: Math.max(0, d.size - d.completed) };
}

export function pausable(d: Pick<Download, 'status'>): boolean {
    return d.status === 'downloading' || d.status === 'no_space';
}

export function resumable(d: Pick<Download, 'status'>): boolean {
    return d.status === 'paused' || d.status === 'stopped' || d.status === 'error';
}

export function stoppable(d: Pick<Download, 'status'>): boolean {
    return d.status !== 'stopped' && d.status !== 'complete';
}

/** The priority a download acts with: an automatic one follows its rarity. */
export function effectivePriority(d: Pick<Download, 'priority' | 'sources'>): 'low' | 'normal' | 'high' {
    if (d.priority !== 'auto') {
        return d.priority;
    }
    // As the engine decides it (DownloadPriority.effective): rare files first. The API could report it instead.
    return d.sources > 100 ? 'low' : d.sources > 20 ? 'normal' : 'high';
}

export function priorityKey(d: Pick<Download, 'priority' | 'sources'>): string {
    const effective = effectivePriority(d);
    if (d.priority !== 'auto') {
        return { low: 'IDS_PRIOLOW', normal: 'IDS_PRIONORMAL', high: 'IDS_PRIOHIGH' }[effective];
    }
    return { low: 'IDS_PRIOAUTOLOW', normal: 'IDS_PRIOAUTONORMAL', high: 'IDS_PRIOAUTOHIGH' }[effective];
}

/** What a source does, as shown: between two asks, one that keeps us in its queue is still queued. */
export function sourceShown(s: Pick<Source, 'state' | 'queueRank'>): Source['state'] {
    return s.state === 'waiting' && s.queueRank > 0 ? 'queued' : s.state;
}

/** The dot of a source: downloading, queued, being reached, of no use now, or not asked yet. */
export type SourceDot = 'downloading' | 'queued' | 'reaching' | 'useless' | 'unknown';

export function sourceDot(state: Source['state']): SourceDot {
    switch (state) {
        case 'downloading':
        case 'queued':
            return state;
        case 'connecting':
        case 'asking':
            return 'reaching';
        case 'waiting':
            return 'unknown';
        default:
            return 'useless';
    }
}

export const SOURCE_STATES: Record<Source['state'], string> = {
    waiting: 'IDS_WAITING',
    connecting: 'IDS_CONNECTING',
    asking: 'IDS_ASKING',
    queued: 'IDS_ONQUEUE',
    downloading: 'IDS_TRANSFERRING',
    a4af: 'IDS_ASKED4ANOTHERFILE',
    queue_full: 'IDS_QUEUEFULL',
    no_needed_parts: 'IDS_NONEEDEDPARTS',
};

/** The sources of a download in their order: those sending first, then by their place in the queues. */
export function bySending<T extends { state: string; queueRank: number }>(list: T[]): T[] {
    return [...list].sort(
        (a, b) =>
            Number(a.state !== 'downloading') - Number(b.state !== 'downloading') ||
            Number(a.queueRank === 0) - Number(b.queueRank === 0) ||
            a.queueRank - b.queueRank,
    );
}

const KIND_ICONS: Record<NonNullable<Download['kind']>, IconName> = {
    video: 'FILE_VIDEO',
    audio: 'FILE_AUDIO',
    image: 'FILE_IMAGE',
    document: 'FILE_DOCUMENT',
    archive: 'FILE_ARCHIVE',
    disc_image: 'FILE_DISC',
    program: 'FILE_APP',
    other: 'FILE',
};

/** The icon of a file of {@code kind}. */
export function kindIcon(kind: Download['kind']): IconName {
    return KIND_ICONS[kind ?? 'other'];
}
