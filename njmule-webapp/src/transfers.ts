// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Download } from './types';

/** The state a download shows: a running one is downloading, waiting for its sources or without any. */
export type Shown = 'downloading' | 'waiting' | 'noSources' | 'paused' | 'stopped' | 'noSpace' | 'error' | 'complete';

export function shown(d: Pick<Download, 'status' | 'transferring' | 'sources'>): Shown {
    switch (d.status) {
        case 'downloading':
            return d.transferring > 0 ? 'downloading' : d.sources > 0 ? 'waiting' : 'noSources';
        case 'no_space':
            return 'noSpace';
        default:
            return d.status;
    }
}
