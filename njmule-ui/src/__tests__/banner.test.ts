// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { type BannerKind, bannerKind, type UnreachableText, unreachableText } from '../banner';
import type { Connection, Status } from '../model/engine';

type Kad = NonNullable<Status['kad']>;
type Forwarding = NonNullable<Status['forwarding']>;

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

    it("should tell Kad's port held by another program before no network", () => {
        givenTheEngine({ kadPortTaken: 4672 });
        whenTheBannerLooks();
        thenItTells('kadPort');
    });

    it("should tell Kad's port held by another program while a server is connected", () => {
        givenTheEngine({ kadPortTaken: 4672 });
        givenConnection('connected');
        whenTheBannerLooks();
        thenItTells('kadPort');
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

describe('the text of the banner for others not reaching us', () => {
    let status: Status | null = null;
    let text: UnreachableText = 'nj.banner.unreachable';

    it('should advise turning UPnP on while the router is not asked', () => {
        givenTheRouterAnswered('off');
        whenTheTextIsChosen();
        thenItIs('nj.banner.unreachable');
    });

    it('should advise turning UPnP on before the engine tells what the router answered', () => {
        givenTheRouterAnswered(undefined);
        whenTheTextIsChosen();
        thenItIs('nj.banner.unreachable');
    });

    it.each<Forwarding>(['asking', 'no_router', 'refused', 'partly', 'failed'])(
        'should send to the router itself while UPnP is on and it answered %s',
        (forwarding) => {
            givenTheRouterAnswered(forwarding);
            whenTheTextIsChosen();
            thenItIs('nj.banner.unreachableUpnp');
        },
    );

    it('should send to the firewalls when the router forwards the port', () => {
        givenTheRouterAnswered('forwarded');
        whenTheTextIsChosen();
        thenItIs('nj.banner.unreachableForwarded');
    });

    function givenTheRouterAnswered(forwarding: Forwarding | undefined): void {
        status = { tcpPort: 4662, lowId: true, clientId: 0, kad: null, forwarding } as Status;
    }

    function whenTheTextIsChosen(): void {
        text = unreachableText(status);
    }

    function thenItIs(expected: UnreachableText): void {
        expect(text).toBe(expected);
    }
});
