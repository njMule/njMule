// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { versions } from '../about';
import type { Schemas } from '../api/client';

describe('the version details copied from About', () => {
    let info: Schemas['ApiInfo'];
    let copied: string;

    it('should name njMule, Java and the system the engine runs on', () => {
        givenTheEngine({
            name: 'njMule',
            version: '0.1.2',
            api: 1,
            java: '25.0.1',
            system: { name: 'Windows 11', version: '10.0', arch: 'amd64' },
        });
        whenTheDetailsAreCopied();
        thenTheyAre('njMule 0.1.2 · Java 25.0.1 · Windows 11 10.0 amd64');
    });

    it('should name only njMule to a guest, who is told nothing of the computer', () => {
        givenTheEngine({ name: 'njMule', version: '0.1.2', api: 1 });
        whenTheDetailsAreCopied();
        thenTheyAre('njMule 0.1.2');
    });

    function givenTheEngine(told: Schemas['ApiInfo']): void {
        info = told;
    }

    function whenTheDetailsAreCopied(): void {
        copied = versions(info);
    }

    function thenTheyAre(expected: string): void {
        expect(copied).toBe(expected);
    }
});
