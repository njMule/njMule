// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';
import type { IconName } from './icons';

// Short messages floating over the window: two at most, the newest at the bottom; the same message again counts up on
// the one shown instead of pushing another out.

export interface Toast {
    id: number;
    text: string;
    icon: IconName;
    tone: 'info' | 'success' | 'danger';
    action?: { label: string; run: () => void };
    /** How many times the same message came, shown as ×2, ×3 and so on. */
    count: number;
}

const SHOWN = 2;
const SHORT_MS = 6000;
// Within the time the engine keeps what an undo needs.
const OFFER_MS = 10000;

export const toasts = ref<Toast[]>([]);
let next = 0;
const timers = new Map<number, number>();

function fadeLater(id: number, ms: number | null): void {
    window.clearTimeout(timers.get(id));
    if (ms !== null) {
        timers.set(
            id,
            window.setTimeout(() => close(id), ms),
        );
    }
}

function add(toast: Omit<Toast, 'id' | 'count'>, ms: number | null): void {
    // The same message again goes last, nearest the eye, and stays its whole time again; an offer is never the same.
    const same = toast.action
        ? undefined
        : toasts.value.find((t) => !t.action && t.text === toast.text && t.tone === toast.tone);
    const shown = same ? { ...same, count: same.count + 1 } : { ...toast, id: ++next, count: 1 };
    const all = [...toasts.value.filter((t) => t.id !== shown.id), shown];
    for (const gone of all.slice(0, Math.max(0, all.length - SHOWN))) {
        window.clearTimeout(timers.get(gone.id));
        timers.delete(gone.id);
    }
    toasts.value = all.slice(-SHOWN);
    fadeLater(shown.id, ms);
}

export function close(id: number): void {
    window.clearTimeout(timers.get(id));
    timers.delete(id);
    toasts.value = toasts.value.filter((t) => t.id !== id);
}

/** A short message for a few seconds. */
export function message(text: string): void {
    add({ text, icon: 'INFO', tone: 'info' }, SHORT_MS);
}

/** That something went well, such as a finished download. */
export function success(text: string): void {
    add({ text, icon: 'CHECK_CIRCLE', tone: 'success' }, SHORT_MS);
}

/** A message with a button, for longer: an undo has to be found in time. */
export function offer(text: string, label: string, run: () => void): void {
    add({ text, icon: 'INFO', tone: 'info', action: { label, run } }, OFFER_MS);
}

/** A problem, which stays until the user closes it. */
export function problem(text: string): void {
    add({ text, icon: 'ERROR', tone: 'danger' }, null);
}
