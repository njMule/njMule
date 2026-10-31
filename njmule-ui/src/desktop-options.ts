// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref, shallowRef } from 'vue';
import type { DesktopSettings, Platform } from './platform';

// The options of the desktop app that only its shell applies: starting minimised, hiding in the notification area,
// asking before quitting and watching the clipboard. The shell keeps them, as some apply before this page runs; a
// browser page has none.

export type DesktopOption = keyof DesktopSettings;

export const DESKTOP_DEFAULTS: DesktopSettings = {
    startMinimized: false,
    minimizeToTray: true,
    confirmExit: true,
    watchClipboard: false,
};

/** The options as the shell keeps them; null where they do not apply. */
export const desktopSettings = shallowRef<DesktopSettings | null>(null);
/** Whether this system has the notification area the window hides in. */
export const trayAvailable = ref(false);

/** Whether {@code option} applies here: in the desktop app, the notification area only where there is one. */
export function usable(option: DesktopOption, settings: DesktopSettings | null, tray: boolean): boolean {
    return settings !== null && (option !== 'minimizeToTray' || tray);
}

export function isDesktopOption(local: string | undefined): local is DesktopOption {
    return (Object.keys(DESKTOP_DEFAULTS) as (string | undefined)[]).includes(local);
}

/** Reads the options the shell keeps, once. */
export async function readDesktopSettings(platform: Platform): Promise<void> {
    const options = await platform.desktopSettings();
    desktopSettings.value = options?.settings ?? null;
    trayAvailable.value = options?.tray ?? false;
}

/** Sets {@code option}, kept by the shell from now on. */
export async function setDesktopOption(platform: Platform, option: DesktopOption, value: boolean): Promise<void> {
    const now = desktopSettings.value;
    if (now === null) {
        return;
    }
    const next = { ...now, [option]: value };
    desktopSettings.value = next;
    await platform.setDesktopSettings(next);
}
