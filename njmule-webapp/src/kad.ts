// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

/** The bars of the distance chart: one per leading bit our ids share, the last one for all that share more. */
export const BARS = 24;
const ID_BITS = 128;

/**
 * Our Kad contacts by their distance, the XOR of their id and ours in hexadecimal, from the closest to the farthest,
 * as the desktop app counts them.
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
