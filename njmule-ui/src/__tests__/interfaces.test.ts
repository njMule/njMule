// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { interfaceChoices, interfaceName, type NetworkInterface } from '../screens/settings/interfaces';

const t = (key: string, ...args: unknown[]) => (args.length > 0 ? `${key}(${args.join()})` : key);

describe('the network interface to bind to', () => {
    const ETHERNET: NetworkInterface = { name: 'eth0', displayName: 'Ethernet', addresses: ['192.168.1.5'] };
    let up: NetworkInterface[] = [];
    let chosen = '';
    let choices: string[] = [];
    let words = '';

    it('should offer any first, then the interfaces up', () => {
        givenTheInterfacesUp(ETHERNET);
        givenTheChoice('');
        whenTheChoicesAreListed();
        thenTheChoicesAre('', 'eth0');
    });

    it('should keep offering a choice that is not up now', () => {
        givenTheInterfacesUp(ETHERNET);
        givenTheChoice('tun0');
        whenTheChoicesAreListed();
        thenTheChoicesAre('', 'eth0', 'tun0');
    });

    it('should name an interface with its addresses', () => {
        givenTheInterfacesUp(ETHERNET);
        whenNamed('eth0');
        thenTheWordsAre('Ethernet (192.168.1.5)');
    });

    it('should name an address with its interface', () => {
        givenTheInterfacesUp(ETHERNET);
        whenNamed('192.168.1.5');
        thenTheWordsAre('192.168.1.5 (Ethernet)');
    });

    it('should tell a choice that is not there', () => {
        givenTheInterfacesUp(ETHERNET);
        whenNamed('tun0');
        thenTheWordsAre('nj.settings.bindInterface.missing(tun0)');
    });

    it('should name the empty choice any', () => {
        givenTheInterfacesUp();
        whenNamed('');
        thenTheWordsAre('nj.settings.bindInterface.any');
    });

    function givenTheInterfacesUp(...all: NetworkInterface[]): void {
        up = all;
    }

    function givenTheChoice(value: string): void {
        chosen = value;
    }

    function whenTheChoicesAreListed(): void {
        choices = interfaceChoices(chosen, up);
    }

    function whenNamed(choice: string): void {
        words = interfaceName(choice, up, t);
    }

    function thenTheChoicesAre(...expected: string[]): void {
        expect(choices).toEqual(expected);
    }

    function thenTheWordsAre(expected: string): void {
        expect(words).toBe(expected);
    }
});
