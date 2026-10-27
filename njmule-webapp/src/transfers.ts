// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { IconName } from './icons';
import type { Download, DownloadPriority, FileKind, Parts, SourceState } from './types';

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

/** What a source does, as shown: between two asks, one that keeps us in its queue is still queued. */
export function sourceShown(s: { state: SourceState; queueRank: number }): SourceState {
    return s.state === 'waiting' && s.queueRank > 0 ? 'queued' : s.state;
}

/** The dot of a source: downloading, queued, being reached, of no use now, or not asked yet. */
export type SourceDot = 'downloading' | 'queued' | 'reaching' | 'useless' | 'unknown';

export function sourceDot(state: SourceState): SourceDot {
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

/** The sources of a download in their order: those sending first, then by their place in the queues. */
export function bySending<T extends { state: string; queueRank: number }>(sources: T[]): T[] {
    return [...sources].sort(
        (a, b) =>
            Number(a.state !== 'downloading') - Number(b.state !== 'downloading') ||
            Number(a.queueRank === 0) - Number(b.queueRank === 0) ||
            a.queueRank - b.queueRank,
    );
}

/** How each state of a download is worded and shown. */
export const STATES: Record<Shown, { key: string; tone: string; icon: IconName }> = {
    downloading: { key: 'IDS_DOWNLOADING', tone: 'busy', icon: 'DOWNLOAD' },
    waiting: { key: 'IDS_WAITING', tone: '', icon: 'CLOCK' },
    noSources: { key: 'nj.transfers.stateNoSources', tone: 'warn', icon: 'NO_SOURCES' },
    paused: { key: 'IDS_PAUSED', tone: '', icon: 'PAUSE' },
    stopped: { key: 'IDS_STOPPED', tone: '', icon: 'STOP' },
    noSpace: { key: 'IDS_INSUFFICIENT', tone: 'error', icon: 'ERROR' },
    error: { key: 'IDS_ERRORLIKE', tone: 'error', icon: 'ERROR' },
    complete: { key: 'IDS_COMPLETE', tone: 'ok', icon: 'CHECK' },
};

export const PRIORITIES: { value: DownloadPriority; key: string }[] = [
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];

export const SOURCE_STATES: Record<SourceState, string> = {
    waiting: 'IDS_WAITING',
    connecting: 'IDS_CONNECTING',
    asking: 'IDS_ASKING',
    queued: 'IDS_ONQUEUE',
    downloading: 'IDS_TRANSFERRING',
    a4af: 'IDS_ASKED4ANOTHERFILE',
    queue_full: 'IDS_QUEUEFULL',
    no_needed_parts: 'IDS_NONEEDEDPARTS',
};

/** What can be done to a download now, as its state asks; a browser cannot open the folder of its file. */
export type DetailsAction = 'pause' | 'resume' | 'stop' | 'addSource';

export function detailsActions(state: Shown): DetailsAction[] {
    switch (state) {
        // A download short of disk space still runs: it goes on by itself once there is room.
        case 'downloading':
        case 'waiting':
        case 'noSpace':
            return ['pause', 'stop'];
        case 'noSources':
            return ['pause', 'addSource'];
        case 'paused':
            return ['resume', 'stop'];
        case 'stopped':
        case 'error':
            return ['resume'];
        case 'complete':
            return [];
    }
}

const PART = 9_728_000;

/** How many parts of a download have no byte missing, of how many. */
export function partsHave(
    d: Pick<Download, 'size' | 'completed' | 'status'>,
    parts: Parts | null,
): { have: number; total: number } {
    const total = Math.ceil(d.size / PART);
    if (d.status === 'complete') {
        return { have: total, total };
    }
    if (parts === null) {
        return { have: Math.floor(d.completed / PART), total };
    }
    const missing = new Array<boolean>(total).fill(false);
    for (const [start, end] of parts.missing) {
        const last = Math.floor(Math.max(start, end - 1) / PART);
        for (let p = Math.floor(start / PART); p <= last && p < total; p++) {
            missing[p] = true;
        }
    }
    return { have: missing.filter((m) => !m).length, total };
}

/** The names the sources give a download, the most given first. */
export function sourceNames(sources: { reportedName?: string }[]): { name: string; sources: number }[] {
    const counts = new Map<string, number>();
    for (const s of sources) {
        if (s.reportedName) {
            counts.set(s.reportedName, (counts.get(s.reportedName) ?? 0) + 1);
        }
    }
    return [...counts]
        .map(([name, n]) => ({ name, sources: n }))
        .sort((a, b) => b.sources - a.sources || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

const KIND_ICONS: Record<FileKind, IconName> = {
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
export function kindIcon(kind: FileKind | undefined): IconName {
    return KIND_ICONS[kind ?? 'other'];
}
