// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';
import type { IconName } from './icons';

// Short messages floating over the window: two at most, the newest at the bottom.

export interface Toast {
    id: number;
    text: string;
    icon: IconName;
    tone: 'info' | 'success' | 'danger';
    action?: { label: string; run: () => void };
}

const SHOWN = 2;
const SHORT_MS = 6000;
const OFFER_MS = 10000;

export const toasts = ref<Toast[]>([]);
let next = 0;

function add(toast: Omit<Toast, 'id'>, ms: number | null): void {
    const id = ++next;
    toasts.value = [...toasts.value, { ...toast, id }].slice(-SHOWN);
    if (ms !== null) {
        window.setTimeout(() => close(id), ms);
    }
}

export function close(id: number): void {
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
