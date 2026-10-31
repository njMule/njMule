// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { answerOrder } from '../ask';

describe('the answers of a question', () => {
    let order: string[] = [];

    it('should put the answer that acts first on Windows', () => {
        whenOrderedFor(true);
        thenTheOrderIs(['yes', 'no']);
    });

    it('should put the answer that acts last on the other systems', () => {
        whenOrderedFor(false);
        thenTheOrderIs(['no', 'yes']);
    });

    function whenOrderedFor(windows: boolean): void {
        order = answerOrder(windows);
    }

    function thenTheOrderIs(expected: string[]): void {
        expect(order).toEqual(expected);
    }
});
