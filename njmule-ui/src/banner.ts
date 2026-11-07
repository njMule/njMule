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

/** The text of the unreachable banner, by what the router answered through UPnP. */
export type UnreachableText = 'nj.banner.unreachable' | 'nj.banner.unreachableUpnp' | 'nj.banner.unreachableForwarded';

/**
 * Turning UPnP on is advice only while the router is not asked; once asked, the router itself is what to look at: its
 * UPnP, or its firewall and the computer's when it forwards the port and still nobody gets through.
 */
export function unreachableText(status: Status | null): UnreachableText {
    switch (status?.forwarding ?? 'off') {
        case 'off':
            return 'nj.banner.unreachable';
        case 'forwarded':
            return 'nj.banner.unreachableForwarded';
        default:
            return 'nj.banner.unreachableUpnp';
    }
}
