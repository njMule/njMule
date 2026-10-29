// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref, watch } from 'vue';
import type { IconName } from './icons';

// The places of the sidebar in groups: what one does first comes first, the network and the statistics after, the
// options at the bottom. Ctrl+1 ... Ctrl+7 open them in this order.

export type Place = 'transfers' | 'search' | 'shared' | 'messages' | 'network' | 'statistics' | 'settings';

export interface PlaceInfo {
    id: Place;
    title: string;
    icon: IconName;
}

export const GROUPS: PlaceInfo[][] = [
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
