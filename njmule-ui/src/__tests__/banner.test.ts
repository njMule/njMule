// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { type BannerKind, bannerKind } from '../banner';
import type { Connection, Status } from '../model/engine';

type Kad = NonNullable<Status['kad']>;

describe('the banner over the screens', () => {
    let connection: Connection = 'disconnected';
    let status: Status | null = null;
    let kind: BannerKind = 'none';

    it('should tell nothing before the engine answers', () => {
        whenTheBannerLooks();
        thenItTells('none');
    });

    it('should tell no network while neither runs', () => {
        givenTheEngine({});
        whenTheBannerLooks();
        thenItTells('offline');
    });

    it('should tell the missing interface before the rest', () => {
        givenTheEngine({ missingInterface: 'eth1' });
        whenTheBannerLooks();
        thenItTells('interface');
    });

    it('should tell nothing while a server is being tried', () => {
        givenTheEngine({});
        givenConnection('connecting');
        whenTheBannerLooks();
        thenItTells('none');
    });

    it('should tell others cannot reach us on a low id alone', () => {
        givenTheEngine({ lowId: true });
        givenConnection('connected');
        whenTheBannerLooks();
        thenItTells('unreachable');
    });

    it('should tell nothing on a low id while Kad is open', () => {
        givenTheEngine({ lowId: true, kad: kad(false) });
        givenConnection('connected');
        whenTheBannerLooks();
        thenItTells('none');
    });

    it('should tell others cannot reach us on Kad behind a firewall alone', () => {
        givenTheEngine({ kad: kad(true) });
        whenTheBannerLooks();
        thenItTells('unreachable');
    });

    it('should tell nothing behind Kad firewall with a high id', () => {
        givenTheEngine({ kad: kad(true) });
        givenConnection('connected');
        whenTheBannerLooks();
        thenItTells('none');
    });

    function givenTheEngine(fields: Partial<Status>): void {
        connection = 'disconnected';
        status = { tcpPort: 4662, lowId: false, clientId: 0, kad: null, ...fields } as Status;
    }

    function givenConnection(now: Connection): void {
        connection = now;
    }

    function whenTheBannerLooks(): void {
        kind = bannerKind(connection, status);
    }

    function thenItTells(expected: BannerKind): void {
        expect(kind).toBe(expected);
    }

    function kad(firewalled: boolean): Kad {
        return { connected: true, firewalled, contacts: 10, udpPort: 4672 } as Kad;
    }
});
