// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from 'vitest';

describe('role', () => {
    let allowed: (path: string) => boolean = () => false;

    afterEach(() => {
        document.head.innerHTML = '';
        vi.resetModules();
    });

    it('should keep a guest to the transfers, searches, network and statistics', async () => {
        givenThePageOfA('guest');

        await whenTheRoleIsRead();

        thenThePlacesOpenAre(['/transfers', '/search', '/network/kad', '/statistics']);
        thenThePlacesClosedAre(['/settings', '/messages', '/shared', '/network/log']);
    });

    it('should open every place to the admin', async () => {
        givenThePageOfA('admin');

        await whenTheRoleIsRead();

        thenThePlacesOpenAre(['/transfers', '/settings', '/messages', '/network/log']);
    });

    function givenThePageOfA(role: string): void {
        const meta = document.createElement('meta');
        meta.name = 'njmule-role';
        meta.content = role;
        document.head.append(meta);
    }

    async function whenTheRoleIsRead(): Promise<void> {
        allowed = (await import('../role')).allowed;
    }

    function thenThePlacesOpenAre(paths: string[]): void {
        expect(paths.filter((p) => !allowed(p))).toEqual([]);
    }

    function thenThePlacesClosedAre(paths: string[]): void {
        expect(paths.filter((p) => allowed(p))).toEqual([]);
    }
});
