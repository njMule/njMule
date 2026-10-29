// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { EventSourceParserStream } from 'eventsource-parser/stream';
import { endpoint, proofHeaders } from './client';

// The engine's events as server-sent events. Read with fetch rather than EventSource, which cannot send the desktop
// shell's token in a header.

/** The engine's events this interface reacts to, by their name in openapi.yaml. */
export interface EventData {
    'download.completed': { download?: { name?: string } };
    'download.added': unknown;
    'download.removed': unknown;
    'server.connecting': { server?: { name?: string; address?: string } };
    'server.connected': { server?: { name?: string; address?: string } };
    'server.disconnected': unknown;
    'server.message': { server?: { name?: string; address?: string }; text?: string };
    'message.received': { nick?: string; address?: string };
    'preferences.changed': unknown;
    log: { message?: string; level?: string };
    // The engine dropped events this stream had not read yet: read everything again.
    lost: unknown;
}

export type EventName = keyof EventData;
type Handler = <K extends EventName>(name: K, data: EventData[K]) => void;

const RETRY_MS = 5000;

/** Follows the events named {@code types} until the returned function is called; reconnects after a failure. */
export function followEvents(types: EventName[], handler: Handler): () => void {
    const stop = new AbortController();
    void (async () => {
        while (!stop.signal.aborted) {
            try {
                await read(types, handler, stop.signal);
            } catch {
                // Gone or refused: tried again in a moment.
            }
            if (!stop.signal.aborted) {
                await new Promise((resolve) => setTimeout(resolve, RETRY_MS));
                // What happened meanwhile is not in the stream: the screens read it all again.
                handler('lost', undefined);
            }
        }
    })();
    return () => stop.abort();
}

async function read(types: EventName[], handler: Handler, signal: AbortSignal): Promise<void> {
    const wanted = types.filter((t) => t !== 'lost').join(',');
    const answer = await fetch(`${endpoint().url}/events?types=${encodeURIComponent(wanted)}`, {
        headers: { Accept: 'text/event-stream', ...proofHeaders() },
        credentials: endpoint().token === undefined ? 'same-origin' : 'omit',
        signal,
    });
    if (!answer.ok || answer.body === null) {
        throw new Error(answer.statusText);
    }
    const events = answer.body.pipeThrough(new TextDecoderStream()).pipeThrough(new EventSourceParserStream());
    for await (const e of events) {
        const name = (e.event ?? 'message') as EventName;
        handler(name, (e.data === '' ? undefined : JSON.parse(e.data)) as EventData[typeof name]);
    }
}
