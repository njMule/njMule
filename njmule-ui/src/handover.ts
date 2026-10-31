// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { answer, api, ApiError } from './api/client';
import { t } from './i18n';
import { message, problem } from './toasts';
import { addLinks } from './transfers-actions';

// eD2K links the system hands over, such as one clicked in a browser: files to download, a server to add, or a list of
// servers or of Kad contacts at an address to read.

/** An eD2K link to something other than a file. */
export type OtherLink =
    { kind: 'server'; host: string; port: number } | { kind: 'serverlist' | 'nodeslist'; url: string };

/** The server, server list and nodes list links among {@code texts}, each a whole link. */
export function otherLinks(texts: string[]): OtherLink[] {
    const found: OtherLink[] = [];
    for (const text of texts) {
        let link = text.trim();
        // Browsers hand links to the program they open with the separators escaped.
        if (!link.includes('|') && /^ed2k:\/\/%7c/i.test(link)) {
            link = link.replace(/%7c/gi, '|');
        }
        const tokens = link.split('|');
        if (tokens[0]?.toLowerCase() !== 'ed2k://') {
            continue;
        }
        const kind = tokens[1];
        if (kind === 'server' && tokens[4] === '/' && /^\d{1,5}$/.test(tokens[3] ?? '')) {
            const port = Number(tokens[3]);
            if (tokens[2] && port > 0 && port < 65536) {
                found.push({ kind, host: tokens[2], port });
            }
        } else if ((kind === 'serverlist' || kind === 'nodeslist') && tokens[3] === '/' && tokens[2]) {
            found.push({ kind, url: tokens[2] });
        }
    }
    return found;
}

/** A host and a port as the API and the messages write them: [address]:port for IPv6. */
export function hostPort(host: string, port: number): string {
    return host.includes(':') && !host.startsWith('[') ? `[${host}]:${port}` : `${host}:${port}`;
}

/** Downloads the files of {@code links}, adds their servers and reads their lists, telling each one done. */
export async function handOver(links: string[]): Promise<void> {
    await addLinks(links.join('\n'));
    for (const link of otherLinks(links)) {
        try {
            if (link.kind === 'server') {
                const address = hostPort(link.host, link.port);
                await addServer(address);
                message(t('nj.servers.added', address));
            } else {
                const read = await answer(
                    link.kind === 'serverlist'
                        ? api().POST('/servers/import', { body: { url: link.url } })
                        : api().POST('/kad/nodes', { body: { url: link.url } }),
                );
                message(
                    t(
                        link.kind === 'serverlist' ? 'nj.servers.fromUrl' : 'nj.kad.fromUrl',
                        read.added,
                        hostOf(link.url),
                    ),
                );
            }
        } catch (e) {
            problem((e as Error).message);
        }
    }
}

// A server known already stays as it is.
async function addServer(address: string): Promise<void> {
    try {
        await answer(api().POST('/servers', { body: { address } }));
    } catch (e) {
        if (!(e instanceof ApiError && e.status === 409)) {
            throw e;
        }
    }
}

function hostOf(url: string): string {
    try {
        return new URL(url).hostname;
    } catch {
        return url;
    }
}
