// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { hostPort, type OtherLink, otherLinks } from '../handover';

describe('the links the system hands over', () => {
    let found: OtherLink[] = [];
    let address = '';

    it('should find a server', () => {
        whenTheLinksAreRead(['ed2k://|server|192.0.2.7|4661|/']);
        thenTheyAre([{ kind: 'server', host: '192.0.2.7', port: 4661 }]);
    });

    it('should find a list of servers', () => {
        whenTheLinksAreRead(['ed2k://|serverlist|http://example.org/server.met|/']);
        thenTheyAre([{ kind: 'serverlist', url: 'http://example.org/server.met' }]);
    });

    it('should find a list of Kad contacts', () => {
        whenTheLinksAreRead(['ed2k://|nodeslist|https://example.org/nodes.dat|/']);
        thenTheyAre([{ kind: 'nodeslist', url: 'https://example.org/nodes.dat' }]);
    });

    it('should read a link a browser escaped', () => {
        whenTheLinksAreRead(['ed2k://%7Cserver%7Cexample.org%7C4242%7C/']);
        thenTheyAre([{ kind: 'server', host: 'example.org', port: 4242 }]);
    });

    it('should leave out files and broken links', () => {
        whenTheLinksAreRead([
            'ed2k://|file|a.iso|1|0123456789ABCDEF0123456789ABCDEF|/',
            'ed2k://|server|example.org|99999|/',
            'ed2k://|serverlist||/',
        ]);
        thenTheyAre([]);
    });

    it('should write an IPv6 server in brackets', () => {
        whenTheAddressIsWritten('2001:db8::7', 4661);
        thenTheAddressIs('[2001:db8::7]:4661');
    });

    function whenTheLinksAreRead(links: string[]): void {
        found = otherLinks(links);
    }

    function whenTheAddressIsWritten(host: string, port: number): void {
        address = hostPort(host, port);
    }

    function thenTheyAre(expected: OtherLink[]): void {
        expect(found).toEqual(expected);
    }

    function thenTheAddressIs(expected: string): void {
        expect(address).toBe(expected);
    }
});
