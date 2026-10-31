// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import type { Connection, RateSample, Status } from '../model/engine';
import { glanceOf, stateKey, stateLine, tooltipOf, type Glance } from '../tray';

const words = (key: string) => `<${key}>`;
const rate = (bytesPerSecond: number) => `${bytesPerSecond} B/s`;

function status(down: number, up: number, free?: number): Status {
    return {
        downloadBytesPerSecond: down,
        uploadBytesPerSecond: up,
        ...(free === undefined ? {} : { freeSpace: { incoming: free, temp: free } }),
    } as unknown as Status;
}

describe('njMule at a glance for the notification area', () => {
    let glance: Glance;
    let text = '';

    it('should keep the last half minute of the rates', () => {
        whenTheGlanceIsTaken(status(10, 5), samples(45));
        thenItDraws(30, 15);
    });

    it('should not know the free space the engine has not told', () => {
        whenTheGlanceIsTaken(status(10, 5), []);
        thenTheFreeSpaceIs(null);
    });

    it('should take the free space where the downloads go', () => {
        whenTheGlanceIsTaken(status(10, 5, 1_000_000), []);
        thenTheFreeSpaceIs(1_000_000);
    });

    it('should tell connected while only Kad is up', () => {
        givenAGlance('disconnected', true);
        whenTheMenuTellsTheState();
        thenTheWordsAre('<IDS_CONNECTED> · ↓ 10 B/s · ↑ 5 B/s');
    });

    it('should tell the menu that a server is being tried', () => {
        givenAGlance('connecting', false);
        whenTheMenuTellsTheState();
        thenTheWordsAre('<IDS_CONNECTING> · ↓ 10 B/s · ↑ 5 B/s');
    });

    it('should tell MiniMule not connected while a server is being tried', () => {
        givenAGlance('connecting', false);
        whenMiniMuleTellsTheState();
        thenTheWordsAre('IDS_NOTCONNECTED');
    });

    it('should give the icon the rates as its tooltip', () => {
        givenAGlance('connected', true);
        whenTheIconTellsTheRates();
        thenTheWordsAre('njMule - <IDS_PW_CON_DOWNLBL>: 10 B/s | <IDS_PW_CON_UPLBL>: 5 B/s');
    });

    function samples(count: number): RateSample[] {
        return Array.from({ length: count }, (_, i) => ({ down: i, up: i }));
    }

    function givenAGlance(connection: Connection, online: boolean): void {
        glance = glanceOf(connection, online, status(10, 5), 0, []);
    }

    function whenTheGlanceIsTaken(s: Status, rates: RateSample[]): void {
        glance = glanceOf('connected', true, s, 3, rates);
    }

    function whenTheMenuTellsTheState(): void {
        text = stateLine(glance, words, rate);
    }

    function whenMiniMuleTellsTheState(): void {
        text = stateKey(glance, false);
    }

    function whenTheIconTellsTheRates(): void {
        text = tooltipOf(glance, words, rate);
    }

    function thenItDraws(count: number, oldest: number): void {
        expect(glance.rates).toHaveLength(count);
        expect(glance.rates[0]?.down).toBe(oldest);
    }

    function thenTheFreeSpaceIs(expected: number | null): void {
        expect(glance.free).toBe(expected);
    }

    function thenTheWordsAre(expected: string): void {
        expect(text).toBe(expected);
    }
});
