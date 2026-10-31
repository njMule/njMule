// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import { isValidHost } from './inputs';

// The web interface's own settings, kept apart from the engine's preferences and followed from the next start; only
// the desktop app's window on njMule's computer changes them.

export type WebSettings = Schemas['WebInterface'];

/** The settings before anyone changes them. */
export const WEB_DEFAULTS = { enabled: false, port: 4711, lan: false, api: false } as const;

/** The fewest characters of a password, and the most a field takes. */
export const MIN_PASSWORD = 8;
export const MAX_PASSWORD = 1024;
/** The most a field of names or proxies takes. */
export const MAX_NAMES = 4096;

/** The names of a text that separates them with commas or white space. */
export function names(text: string): string[] {
    return text.split(/[,\s]+/).filter((n) => n !== '');
}

/** A character of a host name or a proxy: browsers send names in ASCII. */
export function asciiChar(c: string): boolean {
    const code = c.codePointAt(0) ?? 0;
    return code > 0x1f && code < 0x7f;
}

/** Whether each name is a host name or an IP address, an IPv6 one in brackets or not, without a port or a scheme. */
export function hostNamesValid(text: string): boolean {
    return names(text).every((n) => isValidHost(n.startsWith('[') && n.endsWith(']') ? n.slice(1, -1) : n));
}

/** Whether a new password can be taken: none, which keeps the one set, or one long enough. */
export function passwordValid(text: string): boolean {
    return text === '' || text.length >= MIN_PASSWORD;
}

/** The settings as last read or saved; none while they cannot be read. */
export const webSettings = shallowRef<WebSettings | undefined>();

export async function readWebSettings(): Promise<void> {
    try {
        webSettings.value = await answer(api().GET('/webui'));
    } catch {
        // As they were: shown disabled while none were read.
    }
}

/** The words of the page's options, by which the box of the Options finds it. */
export const WEB_WORDS = [
    'nj.webui.enabled',
    'nj.webui.port',
    'nj.webui.lan',
    'nj.webui.hosts',
    'nj.webui.hostsHint',
    'nj.webui.proxies',
    'nj.webui.proxiesHint',
    'nj.webui.password',
    'nj.webui.guestPassword',
    'nj.webui.guestHint',
    'nj.webui.api',
    'nj.webui.copyToken',
    'nj.webui.newToken',
];

/** How many options differ from their defaults; the passwords have none. */
export function changedWebOptions(web: WebSettings | undefined): number {
    if (web === undefined) {
        return 0;
    }
    return [
        web.enabled !== WEB_DEFAULTS.enabled,
        web.port !== WEB_DEFAULTS.port,
        web.lan !== WEB_DEFAULTS.lan,
        web.hosts.length > 0,
        web.proxies.length > 0,
        web.api !== WEB_DEFAULTS.api,
    ].filter(Boolean).length;
}

/** Whether the web interface runs as these settings say: on, with a password. */
export function usable(web: Pick<WebSettings, 'enabled' | 'hasPassword'>): boolean {
    return web.enabled && web.hasPassword;
}
