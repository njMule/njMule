// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref, watch } from 'vue';
import type { IconName } from './icons';
import { allowed } from './role';

// The places of the sidebar in groups: what one does first comes first, the network and the statistics after, the
// options at the bottom. Ctrl+1 ... Ctrl+7 open them in this order. A guest's page has only those it may open.

export type Place = 'transfers' | 'search' | 'shared' | 'messages' | 'network' | 'statistics' | 'settings';

export interface PlaceInfo {
    id: Place;
    title: string;
    icon: IconName;
}

const EVERY_GROUP: PlaceInfo[][] = [
    [
        { id: 'transfers', title: 'IDS_EM_TRANS', icon: 'TRANSFERS' },
        { id: 'search', title: 'IDS_EM_SEARCH', icon: 'SEARCH' },
        { id: 'shared', title: 'IDS_EM_FILES', icon: 'SHARED' },
        { id: 'messages', title: 'IDS_EM_MESSAGES', icon: 'MESSAGE' },
    ],
    [
        { id: 'network', title: 'IDS_NETWORK', icon: 'NETWORK' },
        { id: 'statistics', title: 'IDS_EM_STATISTIC', icon: 'STATISTICS' },
    ],
    [{ id: 'settings', title: 'IDS_EM_PREFS', icon: 'OPTIONS' }],
];

/** The groups of the places this page may open; a group left empty stays, so that the last one keeps to the bottom. */
export const GROUPS: PlaceInfo[][] = EVERY_GROUP.map((group) => group.filter((p) => allowed(`/${p.id}`)));

export const PLACES: PlaceInfo[] = GROUPS.flat();

const KEY = 'njmule.place';

function fromHash(): Place | undefined {
    const id = window.location.hash.replace(/^#\/?/, '');
    return PLACES.find((p) => p.id === id)?.id;
}

function saved(): Place | undefined {
    try {
        const id = localStorage.getItem(KEY);
        return PLACES.find((p) => p.id === id)?.id;
    } catch {
        return undefined;
    }
}

/** The place shown: the address says it in a browser, the last one shown otherwise. */
export const current = ref<Place>(fromHash() ?? saved() ?? 'transfers');

watch(current, (place) => {
    // A place this page may not open, asked for from elsewhere, gives way to the transfers.
    if (!PLACES.some((p) => p.id === place)) {
        current.value = 'transfers';
        return;
    }
    if (fromHash() !== place) {
        window.history.replaceState(null, '', `#/${place}`);
    }
    try {
        localStorage.setItem(KEY, place);
    } catch {
        // The next window opens on the transfers.
    }
});

window.addEventListener('hashchange', () => {
    const place = fromHash();
    if (place !== undefined) {
        current.value = place;
    }
});

type NetworkPart = 'servers' | 'kad' | 'log';

const PART_KEY = 'njmule.networkPart';

/** Whether this page may show the log, which a guest may not read. */
export const logAllowed = allowed('/network/log');

function savedPart(): NetworkPart {
    try {
        const part = localStorage.getItem(PART_KEY);
        return part === 'kad' || (part === 'log' && logAllowed) ? part : 'servers';
    } catch {
        return 'servers';
    }
}

/**
 * The part of the network shown: the servers, Kad or the log; the network's place opens the one shown last, in this
 * window and the next.
 */
export const networkPart = ref<NetworkPart>(savedPart());

watch(networkPart, (part) => {
    if (part === 'log' && !logAllowed) {
        networkPart.value = 'servers';
        return;
    }
    try {
        localStorage.setItem(PART_KEY, part);
    } catch {
        // The next window opens on the servers.
    }
});

/** Counts the times the log was asked to show its own tab rather than the servers' messages or ours. */
export const logTabRequest = ref(0);

/** Opens the log at its own tab, as the last line of the status strip does. */
export function showLog(): void {
    if (!logAllowed) {
        return;
    }
    networkPart.value = 'log';
    current.value = 'network';
    logTabRequest.value++;
}

/** Words to search the network for, asked from elsewhere (the palette); the search screen takes and clears them. */
export const searchRequest = ref('');

/** The shortcut of the place at {@code index}, as the platform writes it: Ctrl+1, or ⌘1. */
export function shortcut(index: number): string {
    return `${mac() ? '⌘' : 'Ctrl+'}${index + 1}`;
}

export function mac(): boolean {
    return /Mac|iPhone|iPad/.test(navigator.platform);
}

/** The keys of {@code key} with the platform's command key: Ctrl+K, or ⌘K. */
export function withShortcutKey(key: string): string {
    return mac() ? `⌘${key}` : `Ctrl+${key}`;
}
