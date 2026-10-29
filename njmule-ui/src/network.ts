// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { count } from './format';
import { format, t } from './i18n';
import type { Connection, Server, Status } from './model/engine';

// The networks' state in words, as the network's control, its place and the status strip tell it.

export interface NetworkWords {
    /** Connected, Connecting or Not connected, for both networks together. */
    state: string;
    /** What each network is at, while one runs: "High ID · Kad: Open". */
    detail: string;
    ed2k: string;
    kad: string;
    up: boolean;
    trying: boolean;
    ed2kUp: boolean;
    kadUp: boolean;
    kadRuns: boolean;
}

export function networkWords(connection: Connection, status: Status | null, serverName: string): NetworkWords {
    const kad = status?.kad ?? null;
    const kadUp = kad !== null && kad.connected;
    const ed2k =
        'eD2K: ' +
        (connection === 'disconnected'
            ? t('IDS_NOTCONNECTED')
            : connection === 'connecting'
              ? `${t('IDS_CONNECTING')} ${serverName}`
              : serverName + (status?.lowId ? ' (LowID)' : status?.clientId ? ' (HighID)' : ''));
    let kadText: string;
    if (kad === null) {
        kadText = 'Kad: ' + t('nj.kad.off');
    } else if (!kadUp) {
        kadText = 'Kad: ' + t('IDS_NOTCONNECTED');
    } else {
        // Behind a TCP firewall, whether UDP gets through tells if others still reach us, once tested.
        const udp =
            kad.firewalled && (kad.udpVerified || kad.udpFirewalled)
                ? ', ' + t(kad.udpFirewalled ? 'nj.kad.udpFirewalled' : 'nj.kad.udpOpen')
                : '';
        kadText = 'Kad: ' + (kad.firewalled ? t('IDS_FIREWALLED') : t('IDS_KADOPEN')) + udp;
    }
    const up = connection === 'connected' || kadUp;
    const trying = !up && (connection === 'connecting' || kad !== null);
    const ed2kShort =
        connection === 'connected'
            ? status?.lowId
                ? t('IDS_IDLOW')
                : status?.clientId
                  ? t('IDS_IDHIGH')
                  : serverName
            : connection === 'connecting'
              ? 'eD2K: ' + t('IDS_CONNECTING')
              : 'eD2K: ' + t('IDS_NOTCONNECTED');
    return {
        state: t(up ? 'IDS_CONNECTED' : trying ? 'IDS_CONNECTING' : 'IDS_NOTCONNECTED'),
        // Nothing to add while neither network runs: the state says it all.
        detail: up || trying ? `${ed2kShort} · ${kadText}` : '',
        ed2k,
        kad: kadText,
        up,
        trying,
        ed2kUp: connection === 'connected',
        kadUp,
        kadRuns: kad !== null,
    };
}

/**
 * The users and files of the networks we are on: on eD2K, all the {@code servers} of the list that answer; on Kad, the
 * estimate of the network, in brackets when both are up.
 */
export function networkCounts(status: Status | null, servers: Server[]): string {
    const kad = status?.kad?.connected ? status.kad : null;
    let users = 0;
    let files = 0;
    if (status?.server) {
        for (const s of servers) {
            if (s.failed === 0) {
                users += s.users;
                files += s.files;
            }
        }
        if (kad !== null) {
            return format(t('nj.status.networks'), [count(users), count(kad.users), count(files), count(kad.files)]);
        }
    } else if (kad !== null) {
        users = kad.users;
        files = kad.files;
    }
    return format(t('nj.status.server'), [count(users), count(files)]);
}
