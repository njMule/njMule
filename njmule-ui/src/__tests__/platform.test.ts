// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { engineHere } from '../platform';

describe('where the engine runs', () => {
    let here = false;

    it('should take the engine the desktop shell started as on this computer', () => {
        whenTheEngineAnswersAt('http://127.0.0.1:52817/api/v1');
        thenItIsHere(true);
    });

    it('should take a remote engine as elsewhere', () => {
        whenTheEngineAnswersAt('https://nas.example.org:4711/api/v1');
        thenItIsHere(false);
    });

    function whenTheEngineAnswersAt(url: string): void {
        here = engineHere(url);
    }

    function thenItIsHere(expected: boolean): void {
        expect(here).toBe(expected);
    }
});
