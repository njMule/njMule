// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import createClient, { type Client, type Middleware } from 'openapi-fetch';
import type { components, paths } from '@generated/api';

// The engine's remote-control API (openapi.yaml): the one way this interface reaches the engine, wherever it runs.

export type Schemas = components['schemas'];

/** Where the engine answers and how this interface proves itself to it. */
export interface Endpoint {
    /** The API's address, such as api/v1 beside the page or http://127.0.0.1:port/api/v1. */
    url: string;
    /** The desktop shell's token, sent as a bearer token; without it the login session's cookie goes. */
    token?: string;
    /** The login session's token for changes, which no page of another site can send. */
    csrf?: string;
}

/** A call the engine refused or could not answer; the message is meant for the user. */
export class ApiError extends Error {
    constructor(
        readonly status: number,
        message: string,
    ) {
        super(message);
    }
}

let client: Client<paths> = createClient<paths>({ baseUrl: 'api/v1' });
let current: Endpoint = { url: 'api/v1' };
let unauthorized: () => void = () => {};

/** Talks to the engine at {@code endpoint} from now on; {@code onUnauthorized} runs when it refuses the session. */
export function connect(endpoint: Endpoint, onUnauthorized: () => void): void {
    current = endpoint;
    unauthorized = onUnauthorized;
    client = createClient<paths>({
        baseUrl: endpoint.url,
        credentials: endpoint.token === undefined ? 'same-origin' : 'omit',
    });
    client.use(proof(endpoint));
}

export function endpoint(): Endpoint {
    return current;
}

/** The headers that prove this interface to the engine. */
export function proofHeaders(): Record<string, string> {
    return current.token === undefined ? {} : { Authorization: `Bearer ${current.token}` };
}

function proof(endpoint: Endpoint): Middleware {
    return {
        onRequest({ request }) {
            if (endpoint.token !== undefined) {
                request.headers.set('Authorization', `Bearer ${endpoint.token}`);
            } else if (request.method !== 'GET') {
                request.headers.set('X-NjMule-CSRF', endpoint.csrf ?? '');
            }
            return request;
        },
    };
}

/** The typed client of the API. */
export function api(): Client<paths> {
    return client;
}

/** The data of an answer, or an {@link ApiError} with the engine's words. */
export async function answer<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
    const { data, error, response } = await call;
    if (response.status === 401) {
        unauthorized();
        throw new ApiError(401, 'Log in again.');
    }
    if (!response.ok) {
        const message = (error as { error?: string } | undefined)?.error ?? response.statusText;
        throw new ApiError(response.status, message);
    }
    return data as T;
}
