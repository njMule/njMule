// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api, ApiError } from './api/client';
import { ask, askRisky } from './ask';
import { t } from './i18n';
import { fileLinks, htmlLinks } from './links';
import { readDownloads, type Download } from './model/engine';
import { message, offer, problem } from './toasts';
import { appliesTo, type Command } from './transfers';

// What the transfers ask the engine to do to the downloads, telling the user how it went.

/**
 * Adds the eD2K file links in {@code text} as downloads, in {@code category} or that of their names, paused or as the
 * preferences say, telling each one added when {@code tell}; returns how many it found. Links handed over by the system
 * are told; those chosen in a dialog are not.
 */
export async function addLinks(text: string, category?: number, paused?: boolean, tell = true): Promise<number> {
    const links = fileLinks(text).map((l) => l.link);
    if (links.length === 0) {
        return 0;
    }
    try {
        const added = await answer(
            api().POST('/downloads', {
                body: {
                    links,
                    ...(category === undefined ? {} : { category }),
                    ...(paused === undefined ? {} : { paused }),
                },
            }),
        );
        for (const d of tell ? added : []) {
            message(t('nj.transfers.added', d.name));
        }
    } catch (e) {
        problem((e as Error).message);
    }
    await readDownloads();
    return links.length;
}

type HashCall = '/downloads/{hash}/pause' | '/downloads/{hash}/resume' | '/downloads/{hash}/stop';

const COMMANDS: Record<HashCall, Command> = {
    '/downloads/{hash}/pause': 'pause',
    '/downloads/{hash}/resume': 'resume',
    '/downloads/{hash}/stop': 'stop',
};

/** Pauses, resumes or stops each of {@code downloads} the command applies to; the others stay as they are. */
export async function each(path: HashCall, downloads: Download[]): Promise<void> {
    await run(
        appliesTo(COMMANDS[path], downloads).map((d) =>
            answer(api().POST(path, { params: { path: { hash: d.hash } } })),
        ),
    );
}

/** The names a cancel question lists at most. */
const CANCEL_NAMES_SHOWN = 5;

/** Cancels the unfinished ones of {@code downloads} once the user says so: what they got is deleted. */
export async function askCancel(downloads: Download[]): Promise<void> {
    const list = downloads.filter((d) => d.status !== 'complete');
    if (list.length === 0) {
        return;
    }
    const names = list.slice(0, CANCEL_NAMES_SHOWN).map((d) => d.name);
    const yes = await askRisky(
        list.length === 1 ? t('nj.transfers.cancelTitleOne') : t('nj.transfers.cancelTitleMany', list.length),
        t('nj.transfers.cancelText'),
        list.length > CANCEL_NAMES_SHOWN ? [...names, '…'] : names,
        t(list.length === 1 ? 'nj.transfers.cancelOne' : 'nj.transfers.cancelMany'),
        t('nj.transfers.keepDownloading'),
    );
    if (yes) {
        await cancel(list);
    }
}

/** Cancels {@code downloads}; the engine keeps what they got for a while, and the message offers to take it back. */
export async function cancel(downloads: Download[]): Promise<void> {
    await run(downloads.map((d) => answer(api().DELETE('/downloads/{hash}', { params: { path: { hash: d.hash } } }))));
    offer(t('nj.transfers.cancelled', downloads.length), t('IDS_UNDO'), () => void restore(downloads));
}

/** Takes back downloads cancelled moments ago, with what they had got. */
export async function restore(downloads: Download[]): Promise<void> {
    await run(
        downloads.map((d) => answer(api().POST('/downloads/{hash}/restore', { params: { path: { hash: d.hash } } }))),
    );
}

/** Takes the completed ones of {@code downloads} off the list; their files stay. */
export async function clearCompletedOf(downloads: Download[]): Promise<void> {
    const done = downloads.filter((d) => d.status === 'complete');
    if (done.length > 0) {
        await run(
            done.map((d) =>
                answer(api().DELETE('/downloads/completed/{hash}', { params: { path: { hash: d.hash } } })),
            ),
        );
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

/**
 * Puts the links of {@code downloads} on the clipboard, one a line, as HTML anchors named after their files when
 * {@code html}; with none chosen it stays as it was.
 */
export async function copyLinks(downloads: Download[], html = false): Promise<void> {
    if (downloads.length === 0) {
        return;
    }
    try {
        await navigator.clipboard.writeText(html ? htmlLinks(downloads) : downloads.map((d) => d.link).join('\n'));
    } catch (e) {
        problem((e as Error).message);
    }
}

/** Asks for the first and last parts of {@code downloads} first, so that their files can be previewed early, or not. */
export async function setPreviewFirst(downloads: Download[], previewFirst: boolean): Promise<void> {
    await run(
        downloads.map((d) =>
            answer(
                api().PUT('/downloads/{hash}/preview', { params: { path: { hash: d.hash } }, body: { previewFirst } }),
            ),
        ),
    );
}

/** Whether sources asked for other files come to {@code downloads} whenever they are free to move. */
export async function setAutoSwap(downloads: Download[], autoSwap: boolean): Promise<void> {
    await run(
        downloads.map((d) =>
            answer(api().PUT('/downloads/{hash}/autoswap', { params: { path: { hash: d.hash } }, body: { autoSwap } })),
        ),
    );
}

/**
 * Moves the sources {@code downloads} share with other files to them now ({@code here}), or theirs to the other files,
 * telling how many moved for each.
 */
export async function swapSources(downloads: Download[], direction: 'here' | 'away'): Promise<void> {
    await run(
        downloads.map(async (d) => {
            const moved = await answer(
                api().POST('/downloads/{hash}/swap', { params: { path: { hash: d.hash } }, body: { direction } }),
            );
            message(t('nj.a4af.swapped', moved.added));
        }),
    );
}

/** Adds a source the user knows of, typed as host:port, to {@code d}. */
export async function askSource(d: Download): Promise<void> {
    const typed = await ask({
        title: t('nj.transfers.addSourceTitle'),
        text: t('nj.transfers.sourceFor', d.name),
        field: { label: t('nj.transfers.sourceAddress'), value: '', hint: t('nj.transfers.sourceHint') },
        yes: t('nj.transfers.addSource'),
        no: t('IDS_CANCEL'),
    });
    const address = typed?.field.trim() ?? '';
    if (address === '') {
        return;
    }
    try {
        await answer(
            api().POST('/downloads/{hash}/sources', { params: { path: { hash: d.hash } }, body: { address } }),
        );
        message(t('nj.transfers.sourceAdded', address));
    } catch (e) {
        // An odd address is refused as such; a source known already or refused by the IP filter is not added.
        problem(
            e instanceof ApiError && e.status === 400
                ? t('nj.servers.badAddress', address)
                : t('nj.transfers.sourceRefused', address),
        );
    }
}

/** Asks the source at {@code address}, asked for another download, for {@code file} next. */
export async function swapSource(file: string, address: string): Promise<void> {
    try {
        const { moved } = await answer(
            api().POST('/downloads/{hash}/sources/swap', { params: { path: { hash: file } }, body: { address } }),
        );
        if (!moved) {
            problem(t('nj.a4af.cannotSwap'));
        }
    } catch (e) {
        problem((e as Error).message);
    }
}

/** Renames a download; the engine makes the name safe for any file system. */
export async function rename(d: Download, name: string): Promise<void> {
    await run([answer(api().PUT('/downloads/{hash}/name', { params: { path: { hash: d.hash } }, body: { name } }))]);
}

/** {@code name} without the words the preferences take out of new names; as it was when the engine cannot say. */
export async function cleanName(name: string): Promise<string> {
    try {
        return (await answer(api().POST('/names/cleanup', { body: { name } }))).name;
    } catch (e) {
        problem((e as Error).message);
        return name;
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
