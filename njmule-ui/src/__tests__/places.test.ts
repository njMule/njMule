// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';

describe('the places', () => {
    let places: typeof import('../places') | undefined;

    afterEach(() => {
        document.head.innerHTML = '';
        window.history.replaceState(null, '', '#');
        localStorage.clear();
        places = undefined;
        vi.resetModules();
    });

    it('should give a guest only the transfers, the searches, the network and the statistics', async () => {
        givenThePageOfA('guest');

        await whenThePlacesAreRead();

        thenTheGroupsAre([['transfers', 'search'], ['network', 'statistics'], []]);
    });

    it('should give the admin every place', async () => {
        givenThePageOfA('admin');

        await whenThePlacesAreRead();

        thenTheGroupsAre([['transfers', 'search', 'shared', 'messages'], ['network', 'statistics'], ['settings']]);
    });

    it('should open the transfers to a guest whose address names the options', async () => {
        givenThePageOfA('guest');
        givenTheAddress('#/settings');

        await whenThePlacesAreRead();

        thenThePlaceShownIs('transfers');
    });

    it('should open the servers to a guest who last saw the log', async () => {
        givenThePageOfA('guest');
        givenTheNetworkPartLastShown('log');

        await whenThePlacesAreRead();

        thenTheNetworkPartIs('servers');
    });

    it('should show the transfers to a guest sent to the options', async () => {
        givenThePageOfA('guest');
        await givenThePlacesRead();

        await whenThePlaceIsSetTo('settings');

        thenThePlaceShownIs('transfers');
    });

    it('should not open the log for a guest', async () => {
        givenThePageOfA('guest');
        await givenThePlacesRead();

        await whenTheLogIsAskedFor();

        thenThePlaceShownIs('transfers');
        thenTheNetworkPartIs('servers');
    });

    it('should open the log for the admin', async () => {
        givenThePageOfA('admin');
        await givenThePlacesRead();

        await whenTheLogIsAskedFor();

        thenThePlaceShownIs('network');
        thenTheNetworkPartIs('log');
    });

    function givenThePageOfA(role: string): void {
        const meta = document.createElement('meta');
        meta.name = 'njmule-role';
        meta.content = role;
        document.head.append(meta);
    }

    function givenTheAddress(hash: string): void {
        window.history.replaceState(null, '', hash);
    }

    function givenTheNetworkPartLastShown(part: string): void {
        localStorage.setItem('njmule.networkPart', part);
    }

    async function givenThePlacesRead(): Promise<void> {
        await whenThePlacesAreRead();
    }

    async function whenThePlacesAreRead(): Promise<void> {
        places = await import('../places');
    }

    async function whenThePlaceIsSetTo(place: 'settings'): Promise<void> {
        read().current.value = place;
        await nextTick();
    }

    async function whenTheLogIsAskedFor(): Promise<void> {
        read().showLog();
        await nextTick();
    }

    function thenTheGroupsAre(expected: string[][]): void {
        expect(read().GROUPS.map((g) => g.map((p) => p.id))).toEqual(expected);
    }

    function thenThePlaceShownIs(expected: string): void {
        expect(read().current.value).toBe(expected);
    }

    function thenTheNetworkPartIs(expected: string): void {
        expect(read().networkPart.value).toBe(expected);
    }

    function read(): typeof import('../places') {
        expect(places).toBeDefined();
        return places!;
    }
});
