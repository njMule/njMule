// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';

export type Mode = 'system' | 'light' | 'dark';

const KEY = 'njmule.theme';
const system = window.matchMedia('(prefers-color-scheme: dark)');

/** Light or dark as the system, unless this browser chose one. */
export const mode = ref<Mode>(saved());

function saved(): Mode {
    try {
        const value = localStorage.getItem(KEY);
        return value === 'light' || value === 'dark' ? value : 'system';
    } catch {
        return 'system';
    }
}

function apply(): void {
    const dark = mode.value === 'dark' || (mode.value === 'system' && system.matches);
    document.documentElement.classList.toggle('dark', dark);
}

export function setMode(next: Mode): void {
    mode.value = next;
    try {
        if (next === 'system') {
            localStorage.removeItem(KEY);
        } else {
            localStorage.setItem(KEY, next);
        }
    } catch {
        // Not kept: the choice holds until the page reloads.
    }
    apply();
}

export function followTheme(): void {
    system.addEventListener('change', apply);
    apply();
}
