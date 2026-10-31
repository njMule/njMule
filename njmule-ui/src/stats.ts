// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { count, duration, percent, rate, ratio, size } from './format';
import { t } from './i18n';
import type { Server, SharedFile, Source, Status, Upload } from './model/engine';

/** A line of the statistics tree and the lines under it. */
export interface StatsEntry {
    text: string;
    children: StatsEntry[];
}

function entry(text: string, ...children: StatsEntry[]): StatsEntry {
    return { text, children };
}

const GIB = 1024 ** 3;

/**
 * The top of the rate graph's scale: the highest rate shown, rounded up to 1, 2 or 5 times a power of ten KB/s, MB/s
 * or GB/s. From a thousand KB/s on the rates read in MB/s, and from a thousand MB/s in GB/s.
 */
export function scaleTop(highest: number): number {
    let unit = 1024;
    for (;;) {
        const top = roundUp(Math.max(1, Math.ceil(highest / unit)));
        if (top < 1000 || unit === GIB) {
            return top * unit;
        }
        unit *= 1024;
    }
}

// Up to 1, 2 or 5 times a power of ten.
function roundUp(value: number): number {
    for (let step = 1; ; step *= 10) {
        for (const m of [1, 2, 5]) {
            if (m * step >= value) {
                return m * step;
            }
        }
    }
}

/** Upload to download, as 1 : 2.50. */
export function upDownRatio(up: number, down: number): string {
    if (up === 0) {
        return down === 0 ? '-' : '0 : 1';
    }
    return `1 : ${ratio(down / up)}`;
}

/** The clients we download from or upload to, each once, counted by their software: the most used first. */
export function clientsBySoftware(
    sources: Source[],
    uploads: Upload[],
): { total: number; bySoftware: [string, number][] } {
    const byAddress = new Map<string, string>();
    for (const c of [...sources, ...uploads]) {
        byAddress.set(c.address, c.software);
    }
    const bySoftware = new Map<string, number>();
    for (const software of byAddress.values()) {
        const name = software.trim() === '' ? '?' : software;
        bySoftware.set(name, (bySoftware.get(name) ?? 0) + 1);
    }
    return {
        total: byAddress.size,
        bySoftware: [...bySoftware].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])),
    };
}

/** What the statistics tree reads. */
export interface StatsFigures {
    status: Status;
    sources: Source[];
    uploads: Upload[];
    servers: Server[];
    shared: SharedFile[];
    /** The downloads completed while the window was open. */
    completed: number;
    /** The highest rates seen, bytes per second. */
    maxDown: number;
    maxUp: number;
    /** The temporary and incoming folders as set, empty for the default ones. */
    dirs: { temp: string; incoming: string };
}

/** Every figure: transfers, uploads, downloads, connection, time, clients, servers, shared files and disk space. */
export function statsTree(f: StatsFigures): StatsEntry[] {
    const s = f.status;
    const seconds = Math.max(1, s.sessionSeconds);
    const session = t('IDS_STATS_SESSION');
    const cumulative = t('IDS_STATS_CUMULATIVE');
    return [
        entry(
            t('IDS_EM_TRANS'),
            entry(`${t('IDS_STATS_SRATIO')} ${upDownRatio(s.uploaded, s.downloaded)}`),
            entry(`${t('IDS_STATS_CRATIO')} ${upDownRatio(s.totalUploaded, s.totalDownloaded)}`),
        ),
        entry(
            t('IDS_TW_UPLOADS'),
            entry(
                session,
                entry(t('IDS_STATS_UDATA', size(s.uploaded))),
                entry(`${t('IDS_STATS_OVRHD')}: ${size(s.overheadUploaded)}`),
                entry(t('IDS_STATS_ACTUL', s.uploads)),
                entry(t('IDS_STATS_WAITINGUSERS', s.queued)),
            ),
            entry(cumulative, entry(t('IDS_STATS_UDATA', size(s.totalUploaded)))),
        ),
        entry(
            t('IDS_TW_DOWNLOADS'),
            entry(
                session,
                entry(t('IDS_STATS_DDATA', size(s.downloaded))),
                entry(`${t('IDS_STATS_OVRHD')}: ${size(s.overheadDownloaded)}`),
                entry(t('IDS_STATS_ACTDL', s.downloads)),
                entry(t('IDS_STATS_FOUNDSRC', f.sources.length)),
                entry(`${t('IDS_STATS_COMPDL')}: ${f.completed}`),
            ),
            entry(cumulative, entry(t('IDS_STATS_DDATA', size(s.totalDownloaded)))),
        ),
        entry(
            t('IDS_CONNECTION'),
            entry(
                session,
                entry(t('IDS_STATS_AVGDL', rate(s.downloaded / seconds))),
                entry(t('IDS_STATS_AVGUL', rate(s.uploaded / seconds))),
                entry(t('IDS_STATS_MAXDL', rate(f.maxDown))),
                entry(`${t('IDS_STATS_MAXUL')}: ${rate(f.maxUp)}`),
            ),
        ),
        entry(
            t('IDS_STATS_TIMESTATS'),
            entry(`${session}: ${duration(s.sessionSeconds)}`),
            entry(`${cumulative}: ${duration(s.totalSeconds)}`),
        ),
        clients(f.sources, f.uploads),
        servers(f.servers),
        shared(f.shared),
        entry(
            t('IDS_STATS_FREESPACE'),
            free(t('IDS_PW_TEMP'), s.freeSpace?.temp, f.dirs.temp),
            free(t('IDS_PW_INCOMING'), s.freeSpace?.incoming, f.dirs.incoming),
        ),
    ];
}

function clients(sources: Source[], uploads: Upload[]): StatsEntry {
    const { total, bySoftware } = clientsBySoftware(sources, uploads);
    return entry(
        `${t('IDS_CLIENTS')} (${total})`,
        ...bySoftware.map(([software, n]) => entry(`${software}: ${n} (${percent(n, total)})`)),
    );
}

function servers(all: Server[]): StatsEntry {
    let working = 0;
    let failed = 0;
    let users = 0;
    let files = 0;
    let allUsers = 0;
    let allFiles = 0;
    for (const s of all) {
        allUsers += s.users;
        allFiles += s.files;
        if (s.failed > 0) {
            failed++;
        } else if (s.ping > 0 || s.users > 0) {
            working++;
            users += s.users;
            files += s.files;
        }
    }
    return entry(
        t('IDS_EM_SERVER'),
        entry(`${t('IDS_SF_WORKING')}: ${working}`),
        entry(`${t('IDS_SF_FAIL')}: ${failed}`),
        entry(`${t('IDS_SF_TOTAL')}: ${all.length}`),
        entry(`${t('IDS_SF_WUSER')}: ${count(users)}`),
        entry(`${t('IDS_SF_WFILE')}: ${count(files)}`),
        entry(`${t('IDS_SF_USER')}: ${count(allUsers)}`),
        entry(`${t('IDS_SF_FILE')}: ${count(allFiles)}`),
    );
}

function shared(files: SharedFile[]): StatsEntry {
    const total = files.reduce((sum, f) => sum + f.size, 0);
    const largest = files.reduce<SharedFile | null>((big, f) => (big === null || f.size > big.size ? f : big), null);
    return entry(
        t('IDS_SF_FILES'),
        entry(t('IDS_STATS_TOTAL', files.length)),
        entry(t('IDS_SF_SIZE', size(total))),
        entry(t('IDS_SF_AVERAGESIZE', size(files.length === 0 ? 0 : total / files.length))),
        ...(largest === null ? [] : [entry(`${t('IDS_STATS_LARGESTFILE')}: ${largest.name} (${size(largest.size)})`)]),
    );
}

// The free space of a folder's disk, and the folder when it is not the default one.
function free(name: string, bytes: number | undefined, dir: string): StatsEntry {
    return entry(`${name}: ${bytes === undefined ? '-' : size(bytes)}${dir === '' ? '' : ` (${dir})`}`);
}

/** A line of the tree as it shows: its place among the lines (as 0.2.1), its depth from 1, and its parent's place. */
export interface TreeRow {
    key: string;
    text: string;
    level: number;
    parent: string | null;
    branch: boolean;
    open: boolean;
}

/** The lines that show, branches open as {@code open} says, in their order. */
export function treeRows(entries: StatsEntry[], open: ReadonlySet<string>, parent: string | null = null): TreeRow[] {
    const rows: TreeRow[] = [];
    entries.forEach((e, i) => {
        const key = parent === null ? String(i) : `${parent}.${i}`;
        const branch = e.children.length > 0;
        const shown = branch && open.has(key);
        rows.push({ key, text: e.text, level: key.split('.').length, parent, branch, open: shown });
        if (shown) {
            rows.push(...treeRows(e.children, open, key));
        }
    });
    return rows;
}

/** What a key does to the tree: the line chosen after it, and the branch it opens or closes, if any. */
export interface TreeMove {
    chosen: string;
    toggle?: string;
}

/**
 * The keys of a tree: the arrows up and down go from line to line, right opens a branch or goes into it, left closes
 * it or goes up to its parent, Home and End go to the first and the last line.
 */
export function treeKey(rows: TreeRow[], chosen: string, key: string): TreeMove | null {
    if (rows.length === 0) {
        return null;
    }
    const at = rows.findIndex((r) => r.key === chosen);
    const row = rows[at];
    switch (key) {
        case 'ArrowDown':
            return { chosen: rows[Math.min(rows.length - 1, at + 1)]!.key };
        case 'ArrowUp':
            return { chosen: rows[Math.max(0, at - 1)]!.key };
        case 'Home':
            return { chosen: rows[0]!.key };
        case 'End':
            return { chosen: rows[rows.length - 1]!.key };
        case 'ArrowRight':
            if (row === undefined || !row.branch) {
                return row === undefined ? { chosen: rows[0]!.key } : null;
            }
            return row.open ? { chosen: rows[at + 1]!.key } : { chosen: row.key, toggle: row.key };
        case 'ArrowLeft':
            if (row === undefined) {
                return { chosen: rows[0]!.key };
            }
            if (row.branch && row.open) {
                return { chosen: row.key, toggle: row.key };
            }
            return row.parent === null ? null : { chosen: row.parent };
        default:
            return null;
    }
}

/** Download and upload rates, bytes per second. */
export interface Peaks {
    down: number;
    up: number;
}

/** The higher of {@code peak} and every sample's rates. */
export function highest(peak: Peaks, samples: Peaks[]): Peaks {
    let down = peak.down;
    let up = peak.up;
    for (const s of samples) {
        down = Math.max(down, s.down);
        up = Math.max(up, s.up);
    }
    return down === peak.down && up === peak.up ? peak : { down, up };
}
