// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The engine's remote-control API (openapi.yaml), called with the login session: every change carries the session's
// token in a header, which no page of another site can send.

const PREFIX = 'api/v1';

/** A call the engine refused or could not answer; the message is meant for the user. */
export class ApiError extends Error {
    constructor(
        readonly status: number,
        message: string,
    ) {
        super(message);
    }
}

function csrf(): string {
    return document.querySelector<HTMLMetaElement>('meta[name="njmule-csrf"]')?.content ?? '';
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (method !== 'GET') {
        headers['X-NjMule-CSRF'] = csrf();
    }
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }
    const answer = await fetch(`${PREFIX}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'same-origin',
    });
    if (answer.status === 401) {
        // The session ended: log in again.
        window.location.assign('login');
        throw new ApiError(401, 'Log in again.');
    }
    const text = await answer.text();
    const json: unknown = text === '' ? undefined : JSON.parse(text);
    if (!answer.ok) {
        const message = (json as { error?: string } | undefined)?.error ?? answer.statusText;
        throw new ApiError(answer.status, message);
    }
    return json as T;
}

export const api = {
    get: <T>(path: string) => call<T>('GET', path),
    post: <T>(path: string, body?: unknown) => call<T>('POST', path, body ?? {}),
    put: <T>(path: string, body: unknown) => call<T>('PUT', path, body),
    patch: <T>(path: string, body: unknown) => call<T>('PATCH', path, body),
    delete: <T>(path: string) => call<T>('DELETE', path),
};

/** The engine's events as they happen, typed by their name; the browser reconnects by itself. */
export function events(handler: (type: string, data: unknown) => void): EventSource {
    const source = new EventSource(`${PREFIX}/events`);
    source.onmessage = (e) => handler('message', JSON.parse(e.data as string));
    return source;
}
