// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';
import { answer, api } from './api/client';
import { filesHere } from './files';
import type { Platform } from './platform';

// What the desktop window sets in the system of njMule's computer, through the engine: whether njMule opens eD2K links
// and starts when the user logs in. Neither in a browser, nor in a window on another computer's njMule.

export type SystemOption = 'ed2kLinks' | 'autostart';

/** Each option as the system has it, null where it offers none; null as a whole where this window sets none. */
export const systemSettings = shallowRef<Record<SystemOption, boolean | null> | null>(null);

/** The message of a change that failed, with the system's reason. */
export const SYSTEM_FAILURES: Record<SystemOption, string> = {
    ed2kLinks: 'nj.settings.ed2kLinksFailed',
    autostart: 'nj.settings.autostartFailed',
};

export function isSystemOption(local: string | undefined): local is SystemOption {
    return local === 'ed2kLinks' || local === 'autostart';
}

function kept(now: { links: boolean | null; autostart: boolean | null }): void {
    systemSettings.value = { ed2kLinks: now.links, autostart: now.autostart };
}

/** Reads the options from the system, as they may have changed since. */
export async function readSystemSettings(platform: Platform): Promise<void> {
    if (!filesHere(platform)) {
        systemSettings.value = null;
        return;
    }
    try {
        kept(await answer(api().GET('/desktop')));
    } catch {
        systemSettings.value = null;
    }
}

/** Turns {@code option} on or off in the system. */
export async function setSystemOption(option: SystemOption, on: boolean): Promise<void> {
    const body = option === 'ed2kLinks' ? { links: on } : { autostart: on };
    kept(await answer(api().PATCH('/desktop', { body })));
}
