// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { base64, choosesOnEngine, fileName } from '../list-files';
import type { Platform } from '../platform';

describe('list files', () => {
    let bytes = new Uint8Array();
    let path = '';
    let platform: Platform | undefined;
    let text = '';
    let onEngine = false;

    it('should send the content of a file in Base64', () => {
        givenTheBytes(0xe0, 0x01, 0xff, 0x00);
        whenTheyAreWrittenInBase64();
        thenTheTextIs('4AH/AA==');
    });

    it('should send a file larger than one call takes in parts', () => {
        givenBytesOfTheLength(100_000);
        whenTheyAreWrittenInBase64();
        thenTheTextHoldsTheBytes();
    });

    it('should name a file of a Windows engine by the end of its path', () => {
        givenThePath('C:\\Users\\ann\\Downloads\\server.met');
        whenItsNameIsTaken();
        thenTheTextIs('server.met');
    });

    it('should name a file of a Linux engine by the end of its path', () => {
        givenThePath('/home/ann/nodes.dat');
        whenItsNameIsTaken();
        thenTheTextIs('nodes.dat');
    });

    it('should choose the file on the engine in the desktop window beside it', () => {
        givenThePlatform(true);
        whenTheWayIsChosenFor('http://127.0.0.1:52817/api/v1');
        thenItIsChosenOnTheEngine(true);
    });

    it('should read the file here in the desktop window of a remote engine', () => {
        givenThePlatform(true);
        whenTheWayIsChosenFor('https://nas.example.org:4711/api/v1');
        thenItIsChosenOnTheEngine(false);
    });

    it('should read the file here in a browser', () => {
        givenThePlatform(false);
        whenTheWayIsChosenFor('http://127.0.0.1:4711/api/v1');
        thenItIsChosenOnTheEngine(false);
    });

    function givenTheBytes(...values: number[]): void {
        bytes = Uint8Array.from(values);
    }

    function givenBytesOfTheLength(length: number): void {
        bytes = Uint8Array.from({ length }, (_, i) => (i * 7) % 256);
    }

    function givenThePath(value: string): void {
        path = value;
    }

    function givenThePlatform(desktop: boolean): void {
        platform = { desktop } as Platform;
    }

    function whenTheyAreWrittenInBase64(): void {
        text = base64(bytes);
    }

    function whenItsNameIsTaken(): void {
        text = fileName(path);
    }

    function whenTheWayIsChosenFor(url: string): void {
        onEngine = choosesOnEngine(platform, url);
    }

    function thenTheTextIs(expected: string): void {
        expect(text).toBe(expected);
    }

    function thenTheTextHoldsTheBytes(): void {
        expect(Uint8Array.from(atob(text), (c) => c.charCodeAt(0))).toEqual(bytes);
    }

    function thenItIsChosenOnTheEngine(expected: boolean): void {
        expect(onEngine).toBe(expected);
    }
});
