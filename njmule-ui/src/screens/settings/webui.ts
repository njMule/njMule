// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Schemas } from '../../api/client';
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

/** Whether the web interface runs as these settings say: on, with a password. */
export function usable(web: Pick<WebSettings, 'enabled' | 'hasPassword'>): boolean {
    return web.enabled && web.hasPassword;
}
