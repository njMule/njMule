// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ask } from './ask';
import { t } from './i18n';
import { fileLinks, type FileLink } from './links';
import type { Platform } from './platform';
import { addLinks } from './transfers-actions';

// eD2K file links copied in another program become downloads while the options ask for it, once the user says so, as
// they may be old or unwanted. The shell looks at the clipboard; the window asks.

/** The most names a question lists; "…" stands for the others. */
const NAMES_ASKED = 10;

// What njMule copied itself, left while it stays on the clipboard; null once something else was copied.
let ours: string | null = null;

/** Notes {@code text} as copied by njMule: its own links are not downloaded. */
export function copiedHere(text: string): void {
    ours = text;
}

/** The file links of {@code text}, new on the clipboard, unless njMule copied it itself. */
export function copiedLinks(text: string): FileLink[] {
    if (text === ours) {
        return [];
    }
    ours = null;
    return fileLinks(text);
}

/** The names of {@code links} the question lists. */
export function askedNames(links: FileLink[]): string[] {
    const names = links.slice(0, NAMES_ASKED).map((l) => l.name);
    return links.length > NAMES_ASKED ? [...names, '…'] : names;
}

async function offerDownloads(platform: Platform, text: string): Promise<void> {
    const links = copiedLinks(text);
    if (links.length === 0) {
        return;
    }
    // The window comes back to ask, from the notification area too.
    platform.showWindow();
    const answer = await ask({
        title: t('IDS_ADDDOWNLOADSFROMCB').trim(),
        names: askedNames(links),
        yes: t('IDS_DOWNLOAD'),
        no: t('nj.links.leave'),
    });
    if (answer !== null) {
        await addLinks(links.map((l) => l.link).join('\n'));
    }
}

/** Offers the links the shell finds on the clipboard; whatever this window copies is noted as njMule's own. */
export function watchClipboard(platform: Platform): void {
    const clipboard = navigator.clipboard as Clipboard | undefined;
    if (!platform.desktop || clipboard === undefined) {
        return;
    }
    // Every copy of the window goes through here, whichever screen makes it.
    const write = clipboard.writeText.bind(clipboard);
    clipboard.writeText = (text: string) => {
        copiedHere(text);
        return write(text);
    };
    platform.onClipboard((text) => void offerDownloads(platform, text));
}
