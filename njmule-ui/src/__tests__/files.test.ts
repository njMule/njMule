// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { completedFile, playerSet } from '../files';
import { shared, type SharedFile } from '../model/engine';

describe('the player of the options', () => {
    let set = false;

    it('should take a path as a player', () => {
        whenThePlayerIs('C:\\Program Files\\VideoLAN\\VLC\\vlc.exe');
        thenAPlayerIsSet(true);
    });

    it('should take a path in quotes, as a file manager copies it, as a player', () => {
        whenThePlayerIs(' "/usr/bin/mpv" ');
        thenAPlayerIsSet(true);
    });

    it.each(['', '   ', '""', "' '"])('should take %j as no player', (player) => {
        whenThePlayerIs(player);
        thenAPlayerIsSet(false);
    });

    function whenThePlayerIs(player: string): void {
        set = playerSet(player);
    }

    function thenAPlayerIsSet(expected: boolean): void {
        expect(set).toBe(expected);
    }
});

describe('where a completed download is', () => {
    const HASH = '0123456789ABCDEF0123456789ABCDEF';
    let file: string | undefined;

    it('should find a completed download among the files shared', () => {
        givenTheSharedFile(HASH, '/home/ann/Downloads/njMule/film.mkv');
        whenLookedFor(HASH);
        thenItIs('/home/ann/Downloads/njMule/film.mkv');
    });

    it('should find no file of a download neither completed in this run nor shared', () => {
        givenTheSharedFile(HASH, '/home/ann/Downloads/njMule/film.mkv');
        whenLookedFor('FEDCBA9876543210FEDCBA9876543210');
        thenItIs(undefined);
    });

    function givenTheSharedFile(hash: string, path: string): void {
        shared.value = [{ hash, path, name: 'film.mkv' } as SharedFile];
    }

    function whenLookedFor(hash: string): void {
        file = completedFile(hash);
    }

    function thenItIs(expected: string | undefined): void {
        expect(file).toBe(expected);
    }
});
