// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Connection, Status } from './model/engine';

/**
 * What the banner over the screens tells of: nothing, the network interface the options bind to gone (connecting would
 * not help), Kad's UDP port held by another program, no network at all, or a network others cannot reach us on.
 */
export type BannerKind = 'none' | 'interface' | 'kadPort' | 'offline' | 'unreachable';

/**
 * Offline when no server is connected or tried and Kad does not run; unreachable when a network is up but others
 * cannot connect to us on any.
 */
export function bannerKind(connection: Connection, status: Status | null): BannerKind {
    if (status === null) {
        return 'none';
    }
    if (status.missingInterface !== undefined) {
        return 'interface';
    }
    if (status.kadPortTaken !== undefined) {
        return 'kadPort';
    }
    const kad = status.kad ?? null;
    if (connection === 'disconnected' && kad === null) {
        return 'offline';
    }
    const lowId = connection === 'connected' && status.lowId;
    const kadUp = kad !== null && kad.connected;
    const reachable = (connection === 'connected' && !status.lowId) || (kadUp && !kad.firewalled);
    return (lowId || (kadUp && kad.firewalled)) && !reachable ? 'unreachable' : 'none';
}
