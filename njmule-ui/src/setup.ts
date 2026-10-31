// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api } from './api/client';

// The first start: whether the engine waits for its questions, the pages that ask them, and the preferences their
// answers make. Every page starts from the settings as they are, so that Next leaves them so.

/** A line with its speeds in Mbit/s. */
export interface Line {
    down: number;
    up: number;
}

export const LINES: Line[] = [
    { down: 20, up: 1 },
    { down: 100, up: 20 },
    { down: 300, up: 50 },
    { down: 1000, up: 300 },
    { down: 2500, up: 1000 },
];

/** Bits per second to the KiB/s the settings use. */
export function lineKib(mbits: number): number {
    return Math.round((mbits * 1_000_000) / 8 / 1024);
}

export type WizardPage = 'start' | 'nick' | 'folders' | 'connection' | 'emule';

/** The pages in order; the eMule page only when an eMule profile was found. */
export function wizardPages(emuleFound: boolean): WizardPage[] {
    const pages: WizardPage[] = ['start', 'nick', 'folders', 'connection'];
    return emuleFound ? [...pages, 'emule'] : pages;
}

/** The answers of the pages; a field that is wrong is null and keeps the setting it had. */
export interface Answers {
    nick: string | null;
    incoming: string | null;
    tcpPort: number | null;
    udpPort: number | null;
    downloadKiBps: number | null;
    uploadKiBps: number | null;
    upnp: boolean;
}

/**
 * The preferences the answers change, by key, among {@code saved}; the incoming folder as it was shown, the engine's
 * own, stays the default one, which follows the data folder.
 */
export function changedPreferences(
    answers: Answers,
    saved: Record<string, string>,
    shownIncoming: string,
): Record<string, string> {
    const wanted: Record<string, string | null> = {
        'user.nick': answers.nick,
        'dirs.incoming': answers.incoming === shownIncoming ? null : answers.incoming,
        'net.tcpPort': answers.tcpPort === null ? null : String(answers.tcpPort),
        'net.udpPort': answers.udpPort === null ? null : String(answers.udpPort),
        'limits.downloadCapacityKiBps': answers.downloadKiBps === null ? null : String(answers.downloadKiBps),
        'limits.uploadCapacityKiBps': answers.uploadKiBps === null ? null : String(answers.uploadKiBps),
        'net.upnp': String(answers.upnp),
    };
    return Object.fromEntries(
        Object.entries(wanted).filter((e): e is [string, string] => e[1] !== null && e[1] !== saved[e[0]]),
    );
}

/** The ports and UPnP among the changes, which a test of the ports applies first. */
export function portPreferences(changes: Record<string, string>): Record<string, string> {
    return Object.fromEntries(
        Object.entries(changes).filter(([key]) => ['net.tcpPort', 'net.udpPort', 'net.upnp'].includes(key)),
    );
}

/** Whether the engine waits for the questions of its first start; never for a page it refuses them. */
export async function setupPending(): Promise<boolean> {
    try {
        return (await answer(api().GET('/setup'))).pending;
    } catch {
        return false;
    }
}

/** Tells the engine the questions were answered or put aside: no window asks them again. */
export async function finishSetup(): Promise<void> {
    try {
        await answer(api().POST('/setup/done'));
    } catch {
        // Asked again at the next start.
    }
}
