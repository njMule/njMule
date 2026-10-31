// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { computed, ref } from 'vue';

// The theme family and light or dark, Nord following the system by default; the colours are in styles/themes.css.

export type Mode = 'system' | 'light' | 'dark';
export type Family = 'nord' | 'primer' | 'cupertino' | 'dracula';

const KEY = 'njmule.theme';
const system = window.matchMedia('(prefers-color-scheme: dark)');
const systemDark = ref(system.matches);

export const family = ref<Family>('nord');
export const mode = ref<Mode>('system');

/** Whether the window shows dark: Dracula always is. */
export const dark = computed(
    () => family.value === 'dracula' || mode.value === 'dark' || (mode.value === 'system' && systemDark.value),
);

function apply(): void {
    const root = document.documentElement;
    root.classList.toggle('dark', dark.value);
    root.dataset.theme = family.value === 'dracula' ? 'dracula' : `${family.value}-${dark.value ? 'dark' : 'light'}`;
}

function save(): void {
    try {
        localStorage.setItem(KEY, JSON.stringify({ family: family.value, mode: mode.value }));
    } catch {
        // Not kept: the choice holds until the window closes.
    }
}

export function setTheme(nextFamily: Family, nextMode: Mode): void {
    family.value = nextFamily;
    mode.value = nextMode;
    save();
    apply();
}

/** Light or dark, the other one than shown now. */
export function toggleDark(): void {
    setTheme(family.value, dark.value ? 'light' : 'dark');
}

/** Shows the look kept, as chosen here or in another window. */
export function readTheme(): void {
    try {
        const saved = JSON.parse(localStorage.getItem(KEY) ?? '{}') as { family?: Family; mode?: Mode };
        family.value = saved.family ?? 'nord';
        mode.value = saved.mode ?? 'system';
    } catch {
        // The default theme.
    }
    apply();
}

export function followTheme(): void {
    readTheme();
    system.addEventListener('change', (e) => {
        systemDark.value = e.matches;
        apply();
    });
}
