// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api } from './api/client';
import { t } from './i18n';
import { fileLinks } from './links';
import { readDownloads, type Download } from './model/engine';
import { message, problem } from './toasts';

// What the transfers ask the engine to do to the downloads, telling the user how it went.

/** Adds the eD2K file links in {@code text} as downloads, telling each one added; returns how many it found. */
export async function addLinks(text: string, category?: number, paused = false): Promise<number> {
    const links = fileLinks(text).map((l) => l.link);
    if (links.length === 0) {
        return 0;
    }
    try {
        const added = await answer(
            api().POST('/downloads', { body: { links, ...(category === undefined ? {} : { category }), paused } }),
        );
        for (const d of added) {
            message(t('nj.transfers.added', d.name));
        }
    } catch (e) {
        problem((e as Error).message);
    }
    await readDownloads();
    return links.length;
}

type HashCall = '/downloads/{hash}/pause' | '/downloads/{hash}/resume' | '/downloads/{hash}/stop';

/** Pauses, resumes or stops each of {@code downloads}. */
export async function each(path: HashCall, downloads: Download[]): Promise<void> {
    await run(downloads.map((d) => answer(api().POST(path, { params: { path: { hash: d.hash } } }))));
}

/** Cancels {@code downloads}, deleting what was downloaded. */
export async function cancel(downloads: Download[]): Promise<void> {
    await run(downloads.map((d) => answer(api().DELETE('/downloads/{hash}', { params: { path: { hash: d.hash } } }))));
    if (downloads.length > 1) {
        message(t('nj.transfers.cancelled', downloads.length));
    }
}

export async function prioritize(downloads: Download[], priority: Download['priority']): Promise<void> {
    await run(
        downloads.map((d) =>
            answer(api().PUT('/downloads/{hash}/priority', { params: { path: { hash: d.hash } }, body: { priority } })),
        ),
    );
}

export async function categorize(downloads: Download[], category: number): Promise<void> {
    await run(
        downloads.map((d) =>
            answer(api().PUT('/downloads/{hash}/category', { params: { path: { hash: d.hash } }, body: { category } })),
        ),
    );
}

export async function clearCompleted(): Promise<void> {
    await run([answer(api().DELETE('/downloads/completed'))]);
}

/** Puts the links of {@code downloads} on the clipboard, one a line. */
export async function copyLinks(downloads: Download[]): Promise<void> {
    try {
        await navigator.clipboard.writeText(downloads.map((d) => d.link).join('\n'));
    } catch (e) {
        problem((e as Error).message);
    }
}

async function run(calls: Promise<unknown>[]): Promise<void> {
    const outcomes = await Promise.allSettled(calls);
    for (const o of outcomes) {
        if (o.status === 'rejected') {
            problem((o.reason as Error).message);
        }
    }
    await readDownloads();
}
