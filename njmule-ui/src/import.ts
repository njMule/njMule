// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api } from './api/client';
import type { IconName } from './icons';
import type { Tone } from './transfers';

// Taking an eMule profile over: where eMule keeps it on the engine's computer, what the engine says of each file, in
// the interface's language, and what the dialog shows of it.

type Translate = (key: string, ...args: unknown[]) => string;

/** The configuration folder of an eMule on the engine's computer, null when there is none. */
export async function emuleProfile(): Promise<string | null> {
    try {
        return (await answer(api().GET('/import/emule'))).path ?? null;
    } catch {
        return null;
    }
}

/** What the engine tells of a check or an import. */
export interface ImportReport {
    dryRun: boolean;
    startAtLogin: boolean;
    items: ImportItem[];
}

export interface ImportItem {
    file: string;
    outcome: Outcome;
    detail: string;
    phrases: { key: string; args: (string | number)[] }[];
}

export type Outcome = 'imported' | 'would_import' | 'skipped' | 'missing' | 'failed';

// The phrases of a detail, written out so that the build takes their texts into the translations.
const PHRASES = new Set([
    'nj.import.d.added',
    'nj.import.d.addresses',
    'nj.import.d.aichTrees',
    'nj.import.d.bootstrapNodes',
    'nj.import.d.categories',
    'nj.import.d.closeEmule',
    'nj.import.d.comments',
    'nj.import.d.copied',
    'nj.import.d.credits',
    'nj.import.d.download',
    'nj.import.d.entries',
    'nj.import.d.files',
    'nj.import.d.folders',
    'nj.import.d.friends',
    'nj.import.d.identityKey',
    'nj.import.d.insideEmule',
    'nj.import.d.kadContacts',
    'nj.import.d.kadId',
    'nj.import.d.knownFiles',
    'nj.import.d.leftOut',
    'nj.import.d.nextStart',
    'nj.import.d.noCategory',
    'nj.import.d.noDataFile',
    'nj.import.d.noDownloads',
    'nj.import.d.noSection',
    'nj.import.d.noSharedFolder',
    'nj.import.d.notAsked',
    'nj.import.d.notShared',
    'nj.import.d.ourOwn',
    'nj.import.d.ownIdentity',
    'nj.import.d.pauseFirst',
    'nj.import.d.portsOurs',
    'nj.import.d.preferences',
    'nj.import.d.ranges',
    'nj.import.d.searches',
    'nj.import.d.sections',
    'nj.import.d.servers',
    'nj.import.d.shortLived',
    'nj.import.d.singleFiles',
    'nj.import.d.spamMarkers',
    'nj.import.d.startAtLogin',
    'nj.import.d.text',
    'nj.import.d.together',
    'nj.import.d.userHash',
    'nj.import.d.withoutPatterns',
]);

/** An item's detail in the interface's language, phrase by phrase; as the engine wrote it if a phrase is unknown. */
export function detail(item: ImportItem, t: Translate): string {
    if (item.phrases.some((p) => !PHRASES.has(p.key))) {
        return item.detail;
    }
    return item.phrases.map((p) => t(p.key, ...p.args)).join('; ');
}

/** What happens to a file, as a chip: taken in the success tone, left out neutral, unreadable in danger. */
export const OUTCOMES: Record<Outcome, { key: string; icon: IconName; tone: Tone }> = {
    imported: { key: 'nj.import.outcome.imported', icon: 'CHECK_CIRCLE', tone: 'success' },
    would_import: { key: 'nj.import.outcome.would_import', icon: 'CHECK_CIRCLE', tone: 'success' },
    skipped: { key: 'nj.import.outcome.skipped', icon: 'REMOVE_CIRCLE', tone: 'neutral' },
    missing: { key: 'nj.import.outcome.missing', icon: 'REMOVE_CIRCLE', tone: 'neutral' },
    failed: { key: 'nj.import.outcome.failed', icon: 'ERROR', tone: 'danger' },
};

/** The line under a report: how many files were read and how many could not be. */
export function reportSummary(report: ImportReport, t: Translate): string {
    const failed = report.items.filter((i) => i.outcome === 'failed').length;
    return t(report.dryRun ? 'nj.import.checked' : 'nj.import.done', report.items.length, failed);
}

/**
 * Import and Close in the order the platform ({@code navigator.platform}) gives a dialog's answers, after Check; and
 * whether Check stands apart from them, as on Windows and macOS.
 */
export function importButtons(platform: string): { order: ('take' | 'close')[]; apart: boolean } {
    return {
        order: /^Win/.test(platform) ? ['take', 'close'] : ['close', 'take'],
        apart: /^(Win|Mac)/.test(platform),
    };
}
