// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it } from 'vitest';
import type { Source, Upload } from '../model/engine';
import {
    clientsBySoftware,
    scaleTop,
    type StatsEntry,
    type TreeMove,
    type TreeRow,
    treeKey,
    treeRows,
    upDownRatio,
} from '../stats';

describe('the top of the rate graph', () => {
    let top = 0;

    it('should round a rate in KB/s up to 1, 2 or 5 times a power of ten', () => {
        whenTheScaleFits(300 * 1024);
        thenTheTopIs(500 * 1024);
    });

    it('should read a thousand KB/s and more in MB/s', () => {
        whenTheScaleFits(1500 * 1024);
        thenTheTopIs(2 * 1024 * 1024);
    });

    it('should keep one KB/s while nothing moves', () => {
        whenTheScaleFits(0);
        thenTheTopIs(1024);
    });

    function whenTheScaleFits(highest: number): void {
        top = scaleTop(highest);
    }

    function thenTheTopIs(expected: number): void {
        expect(top).toBe(expected);
    }
});

describe('the upload to download ratio', () => {
    let text = '';

    it('should tell nothing before anything moved', () => {
        whenTheRatioIsWritten(0, 0);
        thenItReads('-');
    });

    it('should tell no upload against a download', () => {
        whenTheRatioIsWritten(0, 10);
        thenItReads('0 : 1');
    });

    it('should tell the download for each byte uploaded', () => {
        whenTheRatioIsWritten(4, 10);
        thenItReads('1 : 2.50');
    });

    function whenTheRatioIsWritten(up: number, down: number): void {
        text = upDownRatio(up, down);
    }

    function thenItReads(expected: string): void {
        expect(text).toBe(expected);
    }
});

describe('the clients by software', () => {
    let sources: Source[] = [];
    let uploads: Upload[] = [];
    let counted: ReturnType<typeof clientsBySoftware> | null = null;

    beforeEach(() => {
        sources = [];
        uploads = [];
    });

    it('should count a client both a source and an upload once', () => {
        givenSources(['192.0.2.1:4662', 'eMule'], ['192.0.2.2:4662', 'aMule']);
        givenUploads(['192.0.2.1:4662', 'eMule']);
        whenCounted();
        thenTheTotalIs(2);
    });

    it('should put the most used software first', () => {
        givenSources(['192.0.2.1:4662', 'aMule'], ['192.0.2.2:4662', 'eMule'], ['192.0.2.3:4662', 'eMule']);
        whenCounted();
        thenTheSoftwareIs([
            ['eMule', 2],
            ['aMule', 1],
        ]);
    });

    function givenSources(...clients: [string, string][]): void {
        sources = clients.map(([address, software]) => ({ address, software }) as Source);
    }

    function givenUploads(...clients: [string, string][]): void {
        uploads = clients.map(([address, software]) => ({ address, software }) as Upload);
    }

    function whenCounted(): void {
        counted = clientsBySoftware(sources, uploads);
    }

    function thenTheTotalIs(expected: number): void {
        expect(counted?.total).toBe(expected);
    }

    function thenTheSoftwareIs(expected: [string, number][]): void {
        expect(counted?.bySoftware).toEqual(expected);
    }
});

describe('the statistics tree', () => {
    const ENTRIES: StatsEntry[] = [
        {
            text: 'Uploads',
            children: [{ text: 'Session', children: [{ text: 'Uploaded: 1 MB', children: [] }] }],
        },
        { text: 'Time', children: [{ text: 'Session: 5 mins', children: [] }] },
    ];
    let open = new Set<string>();
    let rows: TreeRow[] = [];
    let move: TreeMove | null = null;

    it('should show the lines of the branches open only', () => {
        givenOpen('0');
        whenTheLinesAreLaidOut();
        thenTheLinesAre(['0', '0.0', '1']);
    });

    it('should go down to the next line', () => {
        givenOpen('0');
        whenTheKeyIsPressed('0.0', 'ArrowDown');
        thenTheMoveIs({ chosen: '1' });
    });

    it('should open a closed branch with the right arrow', () => {
        givenOpen('0');
        whenTheKeyIsPressed('0.0', 'ArrowRight');
        thenTheMoveIs({ chosen: '0.0', toggle: '0.0' });
    });

    it('should go into an open branch with the right arrow', () => {
        givenOpen('0');
        whenTheKeyIsPressed('0', 'ArrowRight');
        thenTheMoveIs({ chosen: '0.0' });
    });

    it('should close an open branch with the left arrow', () => {
        givenOpen('0');
        whenTheKeyIsPressed('0', 'ArrowLeft');
        thenTheMoveIs({ chosen: '0', toggle: '0' });
    });

    it('should go up to the parent with the left arrow', () => {
        givenOpen('0', '0.0');
        whenTheKeyIsPressed('0.0.0', 'ArrowLeft');
        thenTheMoveIs({ chosen: '0.0' });
    });

    it('should choose the first line on the first key', () => {
        givenOpen();
        whenTheKeyIsPressed('', 'ArrowDown');
        thenTheMoveIs({ chosen: '0' });
    });

    function givenOpen(...keys: string[]): void {
        open = new Set(keys);
    }

    function whenTheLinesAreLaidOut(): void {
        rows = treeRows(ENTRIES, open);
    }

    function whenTheKeyIsPressed(chosen: string, key: string): void {
        move = treeKey(treeRows(ENTRIES, open), chosen, key);
    }

    function thenTheLinesAre(expected: string[]): void {
        expect(rows.map((r) => r.key)).toEqual(expected);
    }

    function thenTheMoveIs(expected: TreeMove): void {
        expect(move).toEqual(expected);
    }
});
