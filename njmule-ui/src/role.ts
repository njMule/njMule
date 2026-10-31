// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

/** Whether this page is a guest's, who may only look: the web interface refuses a guest any change anyway. */
export const guest = document.querySelector<HTMLMetaElement>('meta[name="njmule-role"]')?.content === 'guest';

/** The places a guest may open: the transfers, the searches, the network and the statistics. */
export const GUEST_PLACES = ['/transfers', '/search', '/network', '/statistics'];

/** Whether the place at {@code path} is open to this page. */
export function allowed(path: string): boolean {
    return !guest || (GUEST_PLACES.some((p) => path === p || path.startsWith(`${p}/`)) && path !== '/network/log');
}
