// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { hostNamesValid, names, passwordValid, usable } from '../screens/settings/webui';

describe('the web interface settings', () => {
    let text = '';
    let list: string[] = [];
    let valid = false;

    it('should split the names by commas and white space', () => {
        givenTheText(' nas.local,  njmule.example.com\t10.0.0.2 ');
        whenSplit();
        thenTheNamesAre('nas.local', 'njmule.example.com', '10.0.0.2');
    });

    it('should take host names and addresses, an IPv6 one in brackets', () => {
        givenTheText('nas.local, 192.168.1.5, [::1], fe80::1');
        whenTheHostsAreChecked();
        thenItIsValid(true);
    });

    it('should refuse a host name with a port', () => {
        givenTheText('nas.local:4711');
        whenTheHostsAreChecked();
        thenItIsValid(false);
    });

    it('should keep the password set when none is typed', () => {
        givenTheText('');
        whenThePasswordIsChecked();
        thenItIsValid(true);
    });

    it('should refuse a password shorter than eight characters', () => {
        givenTheText('seven77');
        whenThePasswordIsChecked();
        thenItIsValid(false);
    });

    it('should not run without a password even when it is on', () => {
        whenItsStateIsTold(true, false);
        thenItIsValid(false);
    });

    function givenTheText(value: string): void {
        text = value;
    }

    function whenSplit(): void {
        list = names(text);
    }

    function whenTheHostsAreChecked(): void {
        valid = hostNamesValid(text);
    }

    function whenThePasswordIsChecked(): void {
        valid = passwordValid(text);
    }

    function whenItsStateIsTold(enabled: boolean, hasPassword: boolean): void {
        valid = usable({ enabled, hasPassword });
    }

    function thenTheNamesAre(...expected: string[]): void {
        expect(list).toEqual(expected);
    }

    function thenItIsValid(expected: boolean): void {
        expect(valid).toBe(expected);
    }
});
