// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Schemas } from './api/client';
import { t } from './i18n';

type Kad = Schemas['Kad'];

/** The bars of the distance chart: one per leading bit our ids share, the last one for all that share more. */
export const BARS = 24;
const ID_BITS = 128;

/**
 * Our Kad contacts by their distance, the XOR of their id and ours in hexadecimal, from the closest to the farthest.
 */
export function distanceBars(contacts: { distance: string }[]): number[] {
    const counts = new Array<number>(BARS).fill(0);
    for (const c of contacts) {
        if (!/^[0-9A-Fa-f]{1,32}$/.test(c.distance)) {
            continue;
        }
        const value = BigInt('0x' + c.distance);
        const bits = value === 0n ? 0 : value.toString(2).length;
        const shared = ID_BITS - bits;
        const bar = BARS - 1 - Math.min(shared, BARS - 1);
        counts[bar] = (counts[bar] ?? 0) + 1;
    }
    return counts;
}

/** Whether other nodes reach us over UDP, in words: unknown until one has told us. */
export function udpState(k: Kad | null | undefined): string {
    if (!k?.connected) {
        return '-';
    }
    if (!k.udpVerified && !k.udpFirewalled) {
        return t('IDS_UNKNOWN');
    }
    return t(k.udpFirewalled ? 'IDS_FIREWALLED' : 'IDS_KADOPEN');
}

const BUDDY: Record<Kad['buddy'], string> = {
    none: 'IDS_BUDDYNONE',
    connecting: 'IDS_CONNECTING',
    connected: 'IDS_CONNECTED',
    serving: 'nj.kad.buddyServing',
};

/** The node that relays to us while we are firewalled, or the one we relay to, in words. */
export function buddyState(k: Kad | null | undefined): string {
    return k?.connected ? t(BUDDY[k.buddy]) : '-';
}

/** An IPv4 address as the unsigned number Kad uses for its id; empty for anything else. */
export function addressNumber(ip: string): string {
    const parts = ip.split('.').map(Number);
    if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) {
        return '';
    }
    return String(parts.reduce((n, p) => n * 256 + p, 0));
}
