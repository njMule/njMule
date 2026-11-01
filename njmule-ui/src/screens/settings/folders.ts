// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';
import { answer, api } from '../../api/client';
import { filesHere } from '../../files';
import type { Platform } from '../../platform';
import type { Kind } from '../../settings';

// What the paths typed in the folder options name on the engine's computer, which only the engine can tell: a folder
// option takes a folder, or one not there yet, but not a file nor text that is no path there.

export type PathKind = 'folder' | 'file' | 'missing' | 'invalid';

/** What each path checked names, by the path as typed. */
export const pathKinds = shallowRef<Record<string, PathKind>>({});

// The engine is asked once typing pauses.
const PAUSE_MS = 250;
let timer = 0;

/** Asks the engine what {@code path} names, unless it told already. */
export async function checkPath(path: string): Promise<void> {
    if (path === '' || pathKinds.value[path] !== undefined) {
        return;
    }
    try {
        const { kind } = await answer(api().POST('/folders/check', { body: { path } }));
        pathKinds.value = { ...pathKinds.value, [path]: kind };
    } catch {
        // Taken as right: the engine refuses it when it is saved.
    }
}

/** Asks what {@code path} names once typing pauses. */
export function checkPathSoon(path: string): void {
    window.clearTimeout(timer);
    timer = window.setTimeout(() => void checkPath(path), PAUSE_MS);
}

/**
 * Whether Browse chooses the path of an option of {@code kind} here: a folder or a command line of the engine's
 * computer only in the desktop app's window on that computer, a sound or a program of this one in any desktop window;
 * elsewhere the path is typed.
 */
export function browsesHere(kind: Kind, platform: Platform): boolean {
    return kind === 'folder' || kind === 'command' ? filesHere(platform) : platform.desktop;
}

/** Whether a folder option can take {@code path}: empty for the default folder, or a path that names no file. */
export function folderTakes(path: string, kind: PathKind | undefined): boolean {
    return path === '' || (kind !== 'file' && kind !== 'invalid');
}
