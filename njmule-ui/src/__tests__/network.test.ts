// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import type { Connection, Status } from '../model/engine';
import { type Card, networkCards, type PortLine, portLines, type PortTest } from '../network';

type Kad = NonNullable<Status['kad']>;

describe('the network cards', () => {
    let connection: Connection = 'disconnected';
    let status: Status | null = null;
    let cards: { ed2k: Card; kad: Card; ports: Card } | null = null;

    it('should show eD2K reachable with a high id', () => {
        givenConnected(false);
        whenTheCardsAreMade();
        thenTheEd2kCardIs('success', 'CHECK_CIRCLE');
    });

    it('should warn on eD2K with a low id', () => {
        givenConnected(true);
        whenTheCardsAreMade();
        thenTheEd2kCardIs('warning', 'WARNING');
    });

    it('should warn while Kad is behind a firewall', () => {
        givenKad({ connected: true, firewalled: true });
        whenTheCardsAreMade();
        thenTheKadCardIs('warning', 'WARNING');
    });

    it('should tell Kad connecting until it is up', () => {
        givenKad({ connected: false, firewalled: true });
        whenTheCardsAreMade();
        thenTheKadCardIs('connecting', 'HOURGLASS');
    });

    it('should name the UDP port only while Kad runs', () => {
        givenConnected(false);
        whenTheCardsAreMade();
        thenThePortsReadAs('TCP 4662');
    });

    it('should name both ports while Kad runs', () => {
        givenKad({ connected: true, firewalled: false });
        whenTheCardsAreMade();
        thenThePortsReadAs('TCP 4662 · UDP 4672');
    });

    function givenConnected(lowId: boolean): void {
        connection = 'connected';
        status = engineStatus({ lowId, clientId: lowId ? 42 : 3_325_256_727 });
    }

    function givenKad(kad: Partial<Kad>): void {
        connection = 'disconnected';
        status = engineStatus({
            kad: { contacts: 10, udpPort: 4672, udpVerified: false, udpFirewalled: false, ...kad } as Kad,
        });
    }

    function whenTheCardsAreMade(): void {
        cards = networkCards(connection, status, 'Lighthouse', {});
    }

    function thenTheEd2kCardIs(tone: Card['tone'], icon: Card['icon']): void {
        expect([cards?.ed2k.tone, cards?.ed2k.icon]).toEqual([tone, icon]);
    }

    function thenTheKadCardIs(tone: Card['tone'], icon: Card['icon']): void {
        expect([cards?.kad.tone, cards?.kad.icon]).toEqual([tone, icon]);
    }

    function thenThePortsReadAs(headline: string): void {
        expect(cards?.ports.headline).toBe(headline);
    }

    function engineStatus(fields: Partial<Status>): Status {
        return { tcpPort: 4662, lowId: false, clientId: 0, ...fields } as Status;
    }
});

describe('the test of the ports', () => {
    let test: PortTest | null = null;
    let lines: PortLine[] = [];

    it('should tell the router forwarding, then both ports reached', () => {
        givenATest({ forwarding: 'forwarded', tcp: 'open', udp: 'open', done: true });
        whenTheLinesAreMade();
        thenTheKindsAre(['open', 'open', 'open']);
        thenTheIconsAre(['CHECK_CIRCLE', 'CHECK_CIRCLE', 'CHECK_CIRCLE']);
    });

    it('should warn on a router that refused and block a closed port', () => {
        givenATest({ forwarding: 'refused', tcp: 'closed', udp: 'unknown', done: true });
        whenTheLinesAreMade();
        thenTheKindsAre(['warning', 'closed', 'unknown']);
        thenTheIconsAre(['WARNING', 'BLOCK', 'HELP']);
    });

    it('should say the UDP test is slow only while it runs', () => {
        givenATest({ forwarding: 'asking', tcp: 'testing', udp: 'testing', done: false });
        whenTheLinesAreMade();
        thenTheKindsAre(['testing', 'testing', 'testing']);
        thenOnlyTheUdpLineSaysItIsSlow();
    });

    it('should leave UDP untested while Kad is off', () => {
        givenATest({ forwarding: 'off', tcp: 'open', udp: 'unknown', udpPort: 0, done: true });
        whenTheLinesAreMade();
        thenTheKindsAre(['off', 'open', 'off']);
        thenTheUdpLineSays('nj.ports.noKad');
    });

    function givenATest(fields: Partial<PortTest>): void {
        test = { tcpPort: 4662, udpPort: 4672, router: '192.168.1.1', ...fields } as PortTest;
    }

    function whenTheLinesAreMade(): void {
        lines = portLines(test!);
    }

    function thenTheKindsAre(kinds: PortLine['kind'][]): void {
        expect(lines.map((l) => l.kind)).toEqual(kinds);
    }

    function thenTheIconsAre(icons: PortLine['icon'][]): void {
        expect(lines.map((l) => l.icon)).toEqual(icons);
    }

    function thenOnlyTheUdpLineSaysItIsSlow(): void {
        expect(lines.map((l) => l.text.includes('nj.ports.udpSlow'))).toEqual([false, false, true]);
    }

    function thenTheUdpLineSays(key: string): void {
        expect(lines[2]?.text).toContain(key);
    }
});
