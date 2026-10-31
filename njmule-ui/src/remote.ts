// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Endpoint } from './api/client';

// The njMule of another computer, shown and driven through its API in a desktop window of its own.

// How long another njMule may take to answer the first call.
const REACH_MS = 20_000;

/**
 * The API's address as typed: http:// and /api/v1 are added when missing, as in "nas:4711"; null when it is no address
 * of a web interface.
 */
export function remoteApi(typed: string): string | null {
    const text = typed.trim();
    if (text === '') {
        return null;
    }
    let url: URL;
    try {
        url = new URL(text.includes('://') ? text : `http://${text}`);
    } catch {
        return null;
    }
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.hostname === '') {
        return null;
    }
    const path = url.pathname === '' || url.pathname === '/' ? '/api/v1' : url.pathname;
    return `${url.protocol}//${url.host}${path}`;
}

/** Whether there is an address and a token to connect with: a remembered address may leave its token empty. */
export function connectable(api: string | null, token: string, remembered: readonly string[]): api is string {
    return api !== null && (token.trim() !== '' || remembered.includes(api));
}

/** Why the API at {@code endpoint} cannot be reached with its token, in the API's words when it says; null if it can. */
export async function unreachable(endpoint: Endpoint, fetcher: typeof fetch = fetch): Promise<string | null> {
    try {
        const answer = await fetcher(`${endpoint.url}/status`, {
            headers: { Authorization: `Bearer ${endpoint.token ?? ''}` },
            credentials: 'omit',
            signal: AbortSignal.timeout(REACH_MS),
        });
        if (answer.ok) {
            return null;
        }
        const said = await answer.json().then(
            (body: { error?: unknown }) => body.error,
            () => undefined,
        );
        return typeof said === 'string' && said !== '' ? said : `${answer.status} ${answer.statusText}`.trim();
    } catch (e) {
        return (e as Error).message;
    }
}
