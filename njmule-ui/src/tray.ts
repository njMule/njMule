// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Connection, RateSample, Status } from './model/engine';

// njMule at a glance for the notification area: the icon's tooltip, its menu and MiniMule. The window tells it the
// shell at each change; the small windows show it as they open, and MiniMule every second while it is open.

/** The seconds of the rates MiniMule draws. */
export const GLANCE_SECONDS = 30;

/** What the icon's small windows show of njMule. */
export interface Glance {
    connection: Connection;
    /** On eD2K or on Kad. */
    online: boolean;
    down: number;
    up: number;
    /** The downloads completed since njMule started. */
    completed: number;
    /** The free space where the downloads go, in bytes; null while unknown. */
    free: number | null;
    /** The rates of the last half minute, the newest last. */
    rates: RateSample[];
}

/** The glance of the engine's state: {@code online} on either network, the rates kept so far. */
export function glanceOf(
    connection: Connection,
    online: boolean,
    status: Status | null,
    completed: number,
    rates: RateSample[],
): Glance {
    return {
        connection,
        online,
        down: status?.downloadBytesPerSecond ?? 0,
        up: status?.uploadBytesPerSecond ?? 0,
        completed,
        free: status?.freeSpace?.incoming ?? null,
        rates: rates.slice(-GLANCE_SECONDS),
    };
}

type Translate = (key: string) => string;
type Rate = (bytesPerSecond: number) => string;

/** Connected on either network, trying a server, or not: the menu's word; MiniMule has no word for trying. */
export function stateKey(g: Glance, trying: boolean): string {
    if (g.online) {
        return 'IDS_CONNECTED';
    }
    return trying && g.connection === 'connecting' ? 'IDS_CONNECTING' : 'IDS_NOTCONNECTED';
}

/** The menu's line under njMule's name: the state, then the rates as the status strip writes them. */
export function stateLine(g: Glance, t: Translate, rate: Rate): string {
    return `${t(stateKey(g, true))} · ↓ ${rate(g.down)} · ↑ ${rate(g.up)}`;
}

/** The text shown when the pointer rests on the icon. */
export function tooltipOf(g: Glance, t: Translate, rate: Rate): string {
    return `njMule - ${t('IDS_PW_CON_DOWNLBL')}: ${rate(g.down)} | ${t('IDS_PW_CON_UPLBL')}: ${rate(g.up)}`;
}
