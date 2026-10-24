// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { shown, type Shown } from '../transfers';
import type { DownloadStatus } from '../types';

describe('transfers', () => {
    let download = { status: 'downloading' as DownloadStatus, transferring: 0, sources: 0 };
    let state: Shown | undefined;

    it('should show a running download with sources sending as downloading', () => {
        givenARunningDownload(3, 1);
        whenItsStateIsShown();
        thenItIs('downloading');
    });

    it('should show a running download whose sources wait as waiting', () => {
        givenARunningDownload(3, 0);
        whenItsStateIsShown();
        thenItIs('waiting');
    });

    it('should show a running download without sources as such', () => {
        givenARunningDownload(0, 0);
        whenItsStateIsShown();
        thenItIs('noSources');
    });

    function givenARunningDownload(sources: number, transferring: number): void {
        download = { status: 'downloading', sources, transferring };
    }

    function whenItsStateIsShown(): void {
        state = shown(download);
    }

    function thenItIs(expected: Shown): void {
        expect(state).toBe(expected);
    }
});
