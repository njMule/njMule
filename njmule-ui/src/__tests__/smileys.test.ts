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

    // Each smiley the pattern finds: a colon with or without a nose before each mouth, and the others.
    const FOUND = [':', ':-']
        .flatMap((eyes) => [...')(DPpOo|*'].map((mouth) => eyes + mouth))
        .concat([';)', ';-)', ":'(", '8)', '<3']);

    it.each(FOUND)('should show the smiley %s as an emoji', (smiley) => {
        whenShown(smiley);

        thenItIsNoLonger(smiley);
    });

    function whenShown(text: string): void {
        shown = smileys(text);
    }

    function thenItIsNoLonger(smiley: string): void {
        expect(shown).not.toBe(smiley);
    }

    function thenItIs(expected: string): void {
        expect(shown).toBe(expected);
    }
});
