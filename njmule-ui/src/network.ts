// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Schemas } from './api/client';
import { count } from './format';
import { format, t } from './i18n';
import type { IconName } from './icons';
import type { Connection, Server, Status } from './model/engine';

// The networks' state in words, as the network's control, its place and the status strip tell it.

export type Tone = 'connected' | 'connecting' | '';

export interface NetworkWords {
    /** Connected, Connecting or Not connected, for both networks together. */
    state: string;
    /** What each network is at, while one runs: "High ID · Kad: Open". */
    detail: string;
    ed2k: string;
    kad: string;
    /** The server's users and files while connected, empty otherwise. */
    ed2kNote: string;
    /** Kad's contacts while it is up, empty otherwise. */
    kadNote: string;
    /** The tone of each network's dot: connected, partly (connecting, or Kad behind a firewall) or neither. */
    ed2kTone: Tone;
    kadTone: Tone;
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
        ed2kNote:
            connection === 'connected' && status?.server
                ? t('nj.status.server', count(status.server.users), count(status.server.files))
                : '',
        kadNote: kadUp ? `${t('IDS_KADCONTACTLAB')}: ${count(kad.contacts)}` : '',
        ed2kTone: connection === 'disconnected' ? '' : connection,
        kadTone: kad === null ? '' : kadUp && !kad.firewalled ? 'connected' : 'connecting',
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

// What the router answered through UPnP, by the answer's name in the API.
const FORWARDING: Record<NonNullable<Status['forwarding']>, string> = {
    off: 'nj.ports.forwarding.OFF',
    asking: 'nj.ports.forwarding.ASKING',
    forwarded: 'nj.ports.forwarding.FORWARDED',
    partly: 'nj.ports.forwarding.PARTLY',
    refused: 'nj.ports.forwarding.REFUSED',
    no_router: 'nj.ports.forwarding.NO_ROUTER',
    failed: 'nj.ports.forwarding.FAILED',
};

/** A card of the network screen: its headline, the icon and tone beside it, and the lines under it. */
export interface Card {
    headline: string;
    icon: 'CHECK_CIRCLE' | 'WARNING' | 'HOURGLASS' | 'CLOUD_OFF' | '';
    tone: 'success' | 'warning' | 'connecting' | '';
    detail: string[];
}

/** The state of eD2K, Kad and the ports in plain words, as the network screen's cards tell it. */
export function networkCards(
    connection: Connection,
    status: Status | null,
    serverName: string,
    preferences: Record<string, string>,
): { ed2k: Card; kad: Card; ports: Card } {
    let ed2k: Card;
    if (connection === 'connected') {
        const low = status?.lowId ?? false;
        const s = status?.server;
        ed2k = {
            headline: t(low ? 'nj.network.unreachable' : 'nj.network.reachable'),
            icon: low ? 'WARNING' : 'CHECK_CIRCLE',
            tone: low ? 'warning' : 'success',
            detail: [serverName, ...(s ? [t('nj.status.server', count(s.users), count(s.files))] : [])],
        };
    } else if (connection === 'connecting') {
        ed2k = { headline: t('IDS_CONNECTING'), icon: 'HOURGLASS', tone: 'connecting', detail: [serverName] };
    } else {
        ed2k = { headline: t('IDS_NOTCONNECTED'), icon: 'CLOUD_OFF', tone: '', detail: [] };
    }
    const k = status?.kad ?? null;
    let kad: Card;
    if (k === null) {
        kad = { headline: t('nj.kad.off'), icon: 'CLOUD_OFF', tone: '', detail: [] };
    } else if (!k.connected) {
        kad = { headline: t('IDS_CONNECTING'), icon: 'HOURGLASS', tone: 'connecting', detail: [] };
    } else {
        // Behind a TCP firewall, whether UDP gets through tells if others still reach us, once tested.
        kad = {
            headline: t(k.firewalled ? 'nj.network.unreachable' : 'nj.network.reachable'),
            icon: k.firewalled ? 'WARNING' : 'CHECK_CIRCLE',
            tone: k.firewalled ? 'warning' : 'success',
            detail: [
                `${t('IDS_KADCONTACTLAB')}: ${count(k.contacts)}`,
                ...(k.firewalled && (k.udpVerified || k.udpFirewalled)
                    ? [t(k.udpFirewalled ? 'nj.kad.udpFirewalled' : 'nj.kad.udpOpen')]
                    : []),
            ],
        };
    }
    // The numbers read left to right in every language; UDP is open while Kad runs.
    const forwarding = FORWARDING[status?.forwarding ?? 'off'];
    const ports: Card = {
        headline: `TCP ${preferences['net.tcpPort'] ?? status?.tcpPort ?? ''}${k ? ` · UDP ${k.udpPort}` : ''}`,
        icon: '',
        tone: '',
        detail: [
            t(forwarding, status?.router ?? ''),
            ...(status?.publicIpv6 ? [`IPv6: ${status.publicIpv6}`] : []),
            `${t('nj.network.natTraversal')}: ${t(status?.routerTraversal && k !== null ? 'IDS_YES' : 'IDS_NO')}`,
        ],
    };
    return { ed2k, kad, ports };
}

export type PortTest = Schemas['PortTest'];

/** What a test of the ports found of one thing: its kind, which gives the icon and its tone, and its words. */
export interface PortLine {
    kind: 'open' | 'closed' | 'testing' | 'unknown' | 'warning' | 'off';
    icon: IconName;
    text: string;
}

const PORT_ICONS: Record<PortLine['kind'], IconName> = {
    open: 'CHECK_CIRCLE',
    closed: 'BLOCK',
    testing: 'HOURGLASS',
    unknown: 'HELP',
    warning: 'WARNING',
    off: 'REMOVE_CIRCLE',
};

const REACH: Record<PortTest['tcp'], string> = {
    testing: 'nj.ports.reach.TESTING',
    open: 'nj.ports.reach.OPEN',
    closed: 'nj.ports.reach.CLOSED',
    unknown: 'nj.ports.reach.UNKNOWN',
};

function portLine(kind: PortLine['kind'], text: string): PortLine {
    return { kind, icon: PORT_ICONS[kind], text };
}

/**
 * What {@code test} found as far as it went: the router's answer through UPnP, then whether others reach the TCP and
 * the UDP port; Kad's slow UDP test says so while it runs.
 */
export function portLines(test: PortTest): PortLine[] {
    const forwarding =
        test.forwarding === 'off'
            ? 'off'
            : test.forwarding === 'asking'
              ? 'testing'
              : test.forwarding === 'forwarded'
                ? 'open'
                : 'warning';
    const udp =
        test.udpPort === 0
            ? portLine('off', t('nj.ports.noKad'))
            : portLine(
                  test.udp,
                  t(REACH[test.udp], 'UDP', String(test.udpPort)) +
                      (test.udp === 'testing' ? ` ${t('nj.ports.udpSlow')}` : ''),
              );
    return [
        portLine(forwarding, t(FORWARDING[test.forwarding], test.router)),
        portLine(test.tcp, t(REACH[test.tcp], 'TCP', String(test.tcpPort))),
        udp,
    ];
}
