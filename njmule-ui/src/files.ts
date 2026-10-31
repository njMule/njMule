// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api, ApiError, endpoint } from './api/client';
import { t } from './i18n';
import { on, preferences, shared } from './model/engine';
import { engineHere, type Platform } from './platform';
import { message } from './toasts';

// The files of the downloads, opened in the programs of this computer: the data so far in the player of the options, a
// completed file in the program of its kind or in its folder. Only the desktop app's window on the engine's own
// computer reaches them.

// Where the downloads completed in this run went.
const went = new Map<string, string>();
on('download.completed', (e) => {
    if (e.download?.hash && e.file) {
        went.set(e.download.hash, e.file);
    }
});

/** Whether the files of the engine are on this computer, for this window to open. */
export function filesHere(platform: Platform): boolean {
    return platform.desktop && engineHere(endpoint().url);
}

/** Whether the player of the options names a program: a path copied from a file manager may come in quotes. */
export function playerSet(player: string): boolean {
    const program = player.trim();
    const quoted = program.length >= 2 && (program[0] === '"' || program[0] === "'") && program.endsWith(program[0]);
    return (quoted ? program.slice(1, -1).trim() : program) !== '';
}

/** Where the completed download {@code hash} is: where it went in this run, or the shared file it became. */
export function completedFile(hash: string): string | undefined {
    return went.get(hash) ?? shared.value.find((f) => f.hash === hash)?.path;
}

/** Shows the data of the download {@code hash} so far in the player of the options. */
export async function preview(platform: Platform, hash: string): Promise<void> {
    const player = preferences.value['preview.player'] ?? '';
    if (!playerSet(player)) {
        message(t('nj.preview.noPlayer'));
        return;
    }
    try {
        const data = await answer(api().GET('/downloads/{hash}/data', { params: { path: { hash } } }));
        await platform.preview(player, preferences.value['preview.args'] ?? '', data.path);
    } catch (e) {
        message(e instanceof ApiError && e.status === 404 ? t('nj.preview.notYet') : (e as Error).message);
    }
}

/** Opens the file {@code path}, named {@code name}, in the program of its kind; a file that is gone is told. */
export function openFile(platform: Platform, name: string, path: string | undefined): Promise<void> {
    return onFile(platform, name, path, (file) => platform.openFile(file));
}

/** Shows the folder of the file {@code path}, the file selected where it can be; a file that is gone is told. */
export function revealFile(platform: Platform, name: string, path: string | undefined): Promise<void> {
    return onFile(platform, name, path, (file) => platform.revealFile(file));
}

async function onFile(
    platform: Platform,
    name: string,
    path: string | undefined,
    act: (file: string) => Promise<boolean>,
): Promise<void> {
    if (!filesHere(platform)) {
        message(t('nj.remote.elsewhere'));
        return;
    }
    try {
        if (path === undefined || !(await act(path))) {
            message(`${t('IDS_ERR_REQ_FNF')}: ${name}`);
        }
    } catch (e) {
        message((e as Error).message);
    }
}
