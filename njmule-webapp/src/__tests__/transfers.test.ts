// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    bySending,
    detailsActions,
    partsHave,
    shown,
    sourceDot,
    sourceNames,
    sourceShown,
    type DetailsAction,
    type Shown,
    type SourceDot,
} from '../transfers';
import type { DownloadStatus, Parts, SourceState } from '../types';

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

describe('source dots', () => {
    let source = { state: 'waiting' as SourceState, queueRank: 0 };
    let dot: SourceDot | undefined;

    it.each([
        ['downloading', 0, 'downloading'],
        ['queued', 3, 'queued'],
        ['waiting', 3, 'queued'],
        ['connecting', 0, 'reaching'],
        ['asking', 0, 'reaching'],
        ['queue_full', 0, 'useless'],
        ['no_needed_parts', 0, 'useless'],
        ['a4af', 0, 'useless'],
        ['waiting', 0, 'unknown'],
    ] as [SourceState, number, SourceDot][])(
        'should show a %s source queued at %i with the dot %s',
        (state, rank, expected) => {
            givenASource(state, rank);
            whenItsDotIsShown();
            thenTheDotIs(expected);
        },
    );

    function givenASource(state: SourceState, queueRank: number): void {
        source = { state, queueRank };
    }

    function whenItsDotIsShown(): void {
        dot = sourceDot(sourceShown(source));
    }

    function thenTheDotIs(expected: SourceDot): void {
        expect(dot).toBe(expected);
    }
});

describe('details', () => {
    const PART = 9_728_000;
    let actions: DetailsAction[] = [];
    let download = { size: 3 * PART, completed: 0, status: 'downloading' as DownloadStatus };
    let parts: Parts | null = null;
    let have: { have: number; total: number } | undefined;
    let names: { name: string; sources: number }[] = [];

    it.each([
        ['downloading', ['pause', 'stop']],
        ['waiting', ['pause', 'stop']],
        ['noSources', ['pause', 'addSource']],
        ['paused', ['resume', 'stop']],
        ['stopped', ['resume']],
        ['noSpace', ['pause', 'stop']],
        ['error', ['resume']],
        ['complete', []],
    ] as [Shown, DetailsAction[]][])('should offer for a %s download %j', (state, expected) => {
        whenTheActionsOfAStateAreAskedFor(state);
        thenTheActionsAre(expected);
    });

    it('should count as had only the parts with no byte missing', () => {
        givenADownloadOfThreePartsMissing([
            [10, 20],
            [2 * PART - 1, 2 * PART + 1],
        ]);
        whenItsPartsAreCounted();
        thenItHas(0, 3);
    });

    it('should count every part of a completed download as had', () => {
        givenACompletedDownloadOfThreeParts();
        whenItsPartsAreCounted();
        thenItHas(3, 3);
    });

    it('should list the names the sources give, the most given first', () => {
        whenTheNamesOfTheSourcesAreListed(['b.avi', 'a.mkv', undefined, 'a.mkv', 'c.avi']);
        thenTheNamesAre([
            { name: 'a.mkv', sources: 2 },
            { name: 'b.avi', sources: 1 },
            { name: 'c.avi', sources: 1 },
        ]);
    });

    function givenADownloadOfThreePartsMissing(missing: [number, number][]): void {
        download = { size: 3 * PART, completed: 0, status: 'downloading' };
        parts = { size: 3 * PART, missing: [...missing, [3 * PART - 5, 3 * PART]], requested: [], sources: [0, 0, 0] };
    }

    function givenACompletedDownloadOfThreeParts(): void {
        download = { size: 3 * PART, completed: 3 * PART, status: 'complete' };
        parts = null;
    }

    function whenTheActionsOfAStateAreAskedFor(state: Shown): void {
        actions = detailsActions(state);
    }

    function whenItsPartsAreCounted(): void {
        have = partsHave(download, parts);
    }

    function whenTheNamesOfTheSourcesAreListed(reported: (string | undefined)[]): void {
        names = sourceNames(reported.map((reportedName) => ({ reportedName })));
    }

    function thenTheActionsAre(expected: DetailsAction[]): void {
        expect(actions).toEqual(expected);
    }

    function thenItHas(parts: number, total: number): void {
        expect(have).toEqual({ have: parts, total });
    }

    function thenTheNamesAre(expected: { name: string; sources: number }[]): void {
        expect(names).toEqual(expected);
    }
});
