// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import DownloadDetails from '../screens/transfers/DownloadDetails.vue';
import type { Download, Source } from '../types';

vi.mock('../api', () => ({ api: { get: vi.fn(async () => []), post: vi.fn(), put: vi.fn() } }));

const FILM: Download = {
    hash: '0123456789ABCDEF0123456789ABCDEF',
    name: 'film.mkv',
    size: 4 * 9_728_000,
    completed: 2 * 9_728_000,
    status: 'paused',
    priority: 'high',
    category: 0,
    sources: 2,
    transferring: 0,
    bytesPerSecond: 0,
    previewFirst: false,
    autoSwap: false,
    link: 'ed2k://|file|film.mkv|38912000|0123456789ABCDEF0123456789ABCDEF|/',
    kind: 'video',
};

function source(address: string, state: Source['state'], queueRank: number): Source {
    return {
        file: FILM.hash,
        fileName: FILM.name,
        address,
        software: 'eMule v0.70',
        state,
        queueRank,
        received: 2048,
        parts: 2,
        partCount: 4,
    };
}

describe('download details', () => {
    let chosen: Download[] = [];
    let sources: Source[] = [];
    let details: VueWrapper | undefined;

    afterEach(() => details?.unmount());

    it('should say how to see the details when no download is chosen', async () => {
        givenTheDownloadsChosen();
        await whenTheDetailsShow();
        thenTheyRead('[nj.details.none]');
    });

    it('should tell how many downloads are chosen when there are several', async () => {
        givenTheDownloadsChosen(FILM, { ...FILM, hash: 'FF'.repeat(16) });
        await whenTheDetailsShow();
        thenTheyRead('[nj.details.chosen]');
    });

    it('should offer to resume or stop a paused download', async () => {
        givenTheDownloadsChosen(FILM);
        await whenTheDetailsShow();
        thenTheButtonsOfTheStateAre('[IDS_DL_RESUME]', '[IDS_DL_STOP]');
    });

    it('should show each source with the dot of what it does', async () => {
        givenTheDownloadsChosen(FILM);
        givenTheSources(source('10.0.0.1:4662', 'downloading', 0), source('10.0.0.2:4662', 'waiting', 3));
        await whenTheDetailsShow();
        thenTheDotsAre('dot-downloading', 'dot-queued');
    });

    it('should ask to be closed from their cross', async () => {
        givenTheDownloadsChosen(FILM);
        await whenTheDetailsShow();
        await whenTheCrossIsPressed();
        thenTheyAskedToBeClosed();
    });

    function givenTheDownloadsChosen(...downloads: Download[]): void {
        chosen = downloads;
    }

    function givenTheSources(...shown: Source[]): void {
        sources = shown;
    }

    async function whenTheDetailsShow(): Promise<void> {
        details = mount(DownloadDetails, { props: { chosen, sources, parts: null, categories: [] } });
        await flushPromises();
    }

    async function whenTheCrossIsPressed(): Promise<void> {
        await details!.find('.tabs .close').trigger('click');
    }

    function thenTheyRead(text: string): void {
        expect(details!.text()).toContain(text);
    }

    function thenTheButtonsOfTheStateAre(...texts: string[]): void {
        expect(details!.findAll('.to-do button').map((b) => b.text())).toEqual(texts);
    }

    function thenTheDotsAre(...tones: string[]): void {
        expect(
            details!.findAll('.sources .source-dot').map((d) => d.classes().find((c) => c.startsWith('dot-'))),
        ).toEqual(tones);
    }

    function thenTheyAskedToBeClosed(): void {
        expect(details!.emitted('close')).toHaveLength(1);
    }
});
