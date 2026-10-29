// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import type { Connection, Status } from '../model/engine';
import { type Card, networkCards } from '../network';

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
