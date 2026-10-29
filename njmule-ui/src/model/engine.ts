// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { computed, ref, shallowRef } from 'vue';
import { answer, api, ApiError, type Schemas } from '../api/client';
import { followEvents, type EventData, type EventName } from '../api/events';

// What the screens show of the engine: read through the API at the desktop app's pace (the status and the downloads
// every second, the uploads and the sources every two, the servers every ten, the clients met every thirty) and kept
// current between two readings by the engine's events.

export type Status = Schemas['Status'];
export type Download = Schemas['Download'];
export type Source = Schemas['Source'];
export type Upload = Schemas['Upload'];
export type Ban = Schemas['Ban'];
export type KnownClient = Schemas['KnownClient'];
export type Category = Schemas['Category'];
export type Server = Schemas['Server'];
export type SharedFile = Schemas['SharedFile'];
export type SharedDirectory = Schemas['SharedDirectory'];

/** Whether we are on an eD2K server, trying one, or on none. */
export type Connection = 'disconnected' | 'connecting' | 'connected';

/** A second of the rates, for the sparkline and the graphs. */
export interface RateSample {
    down: number;
    up: number;
}

// The rates kept: an hour of seconds.
const RATE_HISTORY = 3600;

export const status = shallowRef<Status | null>(null);
export const downloads = shallowRef<Download[]>([]);
export const sources = shallowRef<Source[]>([]);
export const uploads = shallowRef<Upload[]>([]);
export const bans = shallowRef<Ban[]>([]);
export const known = shallowRef<KnownClient[]>([]);
export const servers = shallowRef<Server[]>([]);
export const shared = shallowRef<SharedFile[]>([]);
export const categories = shallowRef<Category[]>([]);
export const preferences = shallowRef<Record<string, string>>({});
export const rates = shallowRef<RateSample[]>([]);
export const connection = ref<Connection>('disconnected');
export const serverName = ref('');
export const lastLog = ref('');
/** What the servers said in this window's time: each line with the server's name. */
export const serverNotes = shallowRef<{ time: string; server: string; text: string }[]>([]);
/** Why the engine could not be read, empty while it answers. */
export const unreachable = ref('');

/** The downloads running now, for the sidebar's count. */
export const running = computed(() => downloads.value.filter((d) => d.status === 'downloading').length);

type Listener<K extends EventName> = (data: EventData[K]) => void;
const listeners = new Map<EventName, Listener<never>[]>();

/** Runs {@code listener} on each event {@code name} of the engine. */
export function on<K extends EventName>(name: K, listener: Listener<K>): void {
    listeners.set(name, [...(listeners.get(name) ?? []), listener as Listener<never>]);
}

let ticks = 0;
let statusPoll = 0;

/** Starts reading the engine: the first readings at once, then once a second. */
export function follow(): void {
    void readAll();
    window.setInterval(() => void tick(), 1000);
    followEvents(
        [
            'server.connecting',
            'server.connected',
            'server.disconnected',
            'server.message',
            'download.added',
            'download.removed',
            'download.completed',
            'message.received',
            'preferences.changed',
            'log',
        ],
        (name, data) => {
            react(name, data);
            for (const listener of listeners.get(name) ?? []) {
                (listener as Listener<typeof name>)(data);
            }
        },
    );
}

function react<K extends EventName>(name: K, data: EventData[K]): void {
    switch (name) {
        case 'server.connecting':
        case 'server.connected': {
            const server = (data as EventData['server.connected']).server;
            connection.value = name === 'server.connected' ? 'connected' : 'connecting';
            serverName.value = server?.name || server?.address || '';
            break;
        }
        case 'server.disconnected':
            connection.value = 'disconnected';
            serverName.value = '';
            break;
        case 'download.added':
        case 'download.removed':
        case 'download.completed':
            void read(api().GET('/downloads'), downloads);
            break;
        case 'preferences.changed':
            void readPreferences();
            break;
        case 'server.message': {
            const note = data as EventData['server.message'];
            serverNotes.value = [
                ...serverNotes.value,
                {
                    time: new Date().toISOString(),
                    server: note.server?.name || note.server?.address || '',
                    text: note.text ?? '',
                },
            ].slice(-500);
            break;
        }
        case 'log':
            lastLog.value = (data as EventData['log']).message ?? '';
            break;
        case 'lost':
            void readAll();
            break;
        default:
            break;
    }
}

async function tick(): Promise<void> {
    const poll = ++ticks;
    void readStatus(poll);
    void read(api().GET('/downloads'), downloads);
    if (ticks % 2 === 0) {
        void read(api().GET('/uploads'), uploads);
        void read(api().GET('/sources'), sources);
        void read(api().GET('/bans'), bans);
    }
    if (ticks % 10 === 0) {
        void read(api().GET('/servers'), servers);
    }
    if (ticks % 15 === 0) {
        void read(api().GET('/shared'), shared);
    }
    if (ticks % 30 === 0) {
        void read(api().GET('/clients/known'), known);
    }
}

async function readAll(): Promise<void> {
    await Promise.all([
        readStatus(++ticks),
        read(api().GET('/downloads'), downloads),
        read(api().GET('/uploads'), uploads),
        read(api().GET('/sources'), sources),
        read(api().GET('/bans'), bans),
        read(api().GET('/servers'), servers),
        read(api().GET('/clients/known'), known),
        read(api().GET('/shared'), shared),
        read(api().GET('/categories'), categories),
        readPreferences(),
        readRates(),
        readLastLog(),
    ]);
}

async function readStatus(poll: number): Promise<void> {
    try {
        const s = await answer(api().GET('/status'));
        // A slow answer to an earlier poll must not hide the newer state already shown.
        if (poll < statusPoll) {
            return;
        }
        statusPoll = poll;
        status.value = s;
        unreachable.value = '';
        if (s.server !== null && connection.value !== 'connected') {
            connection.value = 'connected';
            serverName.value = s.server.name || s.server.address;
        } else if (s.server === null && connection.value === 'connected') {
            connection.value = 'disconnected';
            serverName.value = '';
        }
        const next = [...rates.value, { down: s.downloadBytesPerSecond, up: s.uploadBytesPerSecond }];
        rates.value = next.length > RATE_HISTORY ? next.slice(next.length - RATE_HISTORY) : next;
    } catch (e) {
        unreachable.value = e instanceof ApiError ? e.message : String(e);
    }
}

async function read<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>, into: { value: T }) {
    try {
        into.value = await answer(call);
    } catch {
        // The status tells when the engine does not answer.
    }
}

async function readPreferences(): Promise<void> {
    try {
        preferences.value = await answer(api().GET('/preferences'));
    } catch {
        // Kept as they were.
    }
}

// The last minutes of the rates the engine kept, so that the sparkline starts full.
async function readRates(): Promise<void> {
    try {
        const history = await answer(api().GET('/rates', { params: { query: { span: 600 } } }));
        if (rates.value.length <= 1) {
            rates.value = history.rates.map(([down, up]) => ({ down: down ?? 0, up: up ?? 0 }));
        }
    } catch {
        // The sparkline fills from now on.
    }
}

async function readLastLog(): Promise<void> {
    try {
        const lines = await answer(api().GET('/log'));
        lastLog.value = lines.at(-1)?.message ?? '';
    } catch {
        // Empty until the next line.
    }
}

/** Reads the categories again, after they changed. */
export function readCategories(): Promise<void> {
    return read(api().GET('/categories'), categories);
}

/** Reads the shared files again at once, after a change of ours. */
export function readShared(): Promise<void> {
    return read(api().GET('/shared'), shared);
}

/** Reads the downloads again at once, after a change of ours. */
export function readDownloads(): Promise<void> {
    return read(api().GET('/downloads'), downloads);
}
