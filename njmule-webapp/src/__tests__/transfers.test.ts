// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { bySending, shown, type Shown } from '../transfers';
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

describe('the sources of a download', () => {
    type Source = { name: string; state: string; queueRank: number };
    let order: string[] = [];

    it('should come sending first, then by their place in the queues, those without one last', () => {
        whenTheSourcesAreOrdered(
            { name: 'waiting', state: 'waiting', queueRank: 0 },
            { name: 'queued 9', state: 'queued', queueRank: 9 },
            { name: 'sending', state: 'downloading', queueRank: 0 },
            { name: 'queued 2', state: 'queued', queueRank: 2 },
        );

        thenTheOrderIs('sending', 'queued 2', 'queued 9', 'waiting');
    });

    function whenTheSourcesAreOrdered(...sources: Source[]): void {
        order = bySending(sources).map((s) => s.name);
    }

    function thenTheOrderIs(...names: string[]): void {
        expect(order).toEqual(names);
    }
});
