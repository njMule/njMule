// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { smileys } from '../smileys';

describe('smileys', () => {
    let shown = '';

    it('should show the smileys of a message as emoji', () => {
        whenShown('hi :) see you ;-) <3');

        thenItIs('hi 🙂 see you 😉 ❤');
    });

    it('should leave smileys inside words, addresses and code alone', () => {
        whenShown('http://example.org/:) [::1]:4662 f(x:D)');

        thenItIs('http://example.org/:) [::1]:4662 f(x:D)');
    });

    function whenShown(text: string): void {
        shown = smileys(text);
    }

    function thenItIs(expected: string): void {
        expect(shown).toBe(expected);
    }
});
