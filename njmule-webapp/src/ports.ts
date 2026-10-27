// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { t } from './i18n';

/** What the router answered when asked through UPnP to forward our ports. */
export type Forwarding = 'off' | 'asking' | 'forwarded' | 'partly' | 'refused' | 'no_router' | 'failed';

const FORWARDING: Record<Forwarding, string> = {
    off: 'nj.ports.forwarding.OFF',
    asking: 'nj.ports.forwarding.ASKING',
    forwarded: 'nj.ports.forwarding.FORWARDED',
    partly: 'nj.ports.forwarding.PARTLY',
    refused: 'nj.ports.forwarding.REFUSED',
    no_router: 'nj.ports.forwarding.NO_ROUTER',
    failed: 'nj.ports.forwarding.FAILED',
};

/** The router's answer in words, naming the router that gave it. */
export function forwardingText(forwarding: Forwarding, router: string): string {
    return t(FORWARDING[forwarding], router);
}
