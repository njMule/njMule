// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it } from 'vitest';
import { holds, mayHoldPersonalFiles, placeKey, places, treeMove, type Place, type TreeMove } from '../shared';

describe('the places of the shared files', () => {
    const files = ['D:\\Shared\\Books\\a.epub', 'D:\\Shared\\Films\\b.mkv', 'D:\\Shared\\c.iso', 'E:\\Music\\d.mp3'];
    let tree: Place[] = [];

    it('should list all files, the incoming folder, then each shared folder', () => {
        whenTheTreeIsMade([{ path: 'D:\\Shared', subdirectories: true }], 'C:\\Incoming');
        thenTheTopPlacesAre(['all', 'incoming', 'folder']);
    });

    it('should show the subfolders that hold files under a folder shared with them', () => {
        whenTheTreeIsMade([{ path: 'D:\\Shared', subdirectories: true }], 'C:\\Incoming');
        thenTheSubfoldersAre(['D:\\Shared\\Books', 'D:\\Shared\\Films']);
    });

    it('should show no subfolder under a folder shared alone', () => {
        whenTheTreeIsMade([{ path: 'D:\\Shared', subdirectories: false }], 'C:\\Incoming');
        thenTheSubfoldersAre([]);
    });

    it('should not list the incoming folder twice when it is shared too', () => {
        whenTheTreeIsMade([{ path: 'C:\\Incoming', subdirectories: false }], 'C:\\Incoming');
        thenTheTopPlacesAre(['all', 'incoming']);
    });

    function whenTheTreeIsMade(directories: { path: string; subdirectories: boolean }[], incoming: string): void {
        tree = places(directories, incoming, files);
    }

    function thenTheTopPlacesAre(kinds: string[]): void {
        expect(tree.map((p) => p.kind)).toEqual(kinds);
    }

    function thenTheSubfoldersAre(paths: string[]): void {
        expect(tree[2]?.children.map((c) => c.path) ?? []).toEqual(paths);
    }
});

describe('a place', () => {
    let held: boolean | undefined;

    it('should hold the files below a folder shared with its subfolders', () => {
        whenAsked(
            { kind: 'folder', path: 'D:\\Shared', subdirectories: true, children: [] },
            'D:\\Shared\\Books\\a.epub',
        );
        thenItHoldsIt(true);
    });

    it('should hold only its own files when shared alone', () => {
        whenAsked(
            { kind: 'folder', path: 'D:\\Shared', subdirectories: false, children: [] },
            'D:\\Shared\\Books\\a.epub',
        );
        thenItHoldsIt(false);
    });

    it('should not take a folder whose name only starts the same', () => {
        whenAsked({ kind: 'subfolder', path: '/srv/share', subdirectories: true, children: [] }, '/srv/shared/x.iso');
        thenItHoldsIt(false);
    });

    function whenAsked(place: Place, file: string): void {
        held = holds(place, file);
    }

    function thenItHoldsIt(expected: boolean): void {
        expect(held).toBe(expected);
    }
});

describe('the keys of the tree', () => {
    const tree = places(
        [
            { path: 'D:\\Shared', subdirectories: true },
            { path: 'E:\\Music', subdirectories: false },
        ],
        'C:\\Incoming',
        ['D:\\Shared\\Books\\a.epub', 'D:\\Shared\\Films\\b.mkv'],
    );
    const shared = tree[2]!;
    const books = shared.children[0]!;
    let closed: string[] = [];
    let move: TreeMove | null = null;

    beforeEach(() => {
        closed = [];
        move = null;
    });

    it('should move the choice down to the next place in sight', () => {
        whenPressed('ArrowDown', tree[1]!);
        thenItChooses(shared);
    });

    it('should go over the subfolders of a closed folder', () => {
        givenClosed(shared);
        whenPressed('ArrowDown', shared);
        thenItChooses(tree[3]!);
    });

    it('should open a closed folder towards the end', () => {
        givenClosed(shared);
        whenPressed('ArrowRight', shared);
        thenItToggles(shared);
    });

    it('should go into an open folder towards the end', () => {
        whenPressed('ArrowRight', shared);
        thenItChooses(books);
    });

    it('should close an open folder towards the start', () => {
        whenPressed('ArrowLeft', shared);
        thenItToggles(shared);
    });

    it('should go from a subfolder to its folder towards the start', () => {
        whenPressed('ArrowLeft', books);
        thenItChooses(shared);
    });

    it('should take the end as the left in a right-to-left layout', () => {
        whenPressed('ArrowLeft', shared, true);
        thenItChooses(books);
    });

    it('should leave the other keys to the page', () => {
        whenPressed('Enter', shared);
        thenNothingMoves();
    });

    function givenClosed(place: Place): void {
        closed = [placeKey(place)];
    }

    function whenPressed(key: string, at: Place, rtl = false): void {
        move = treeMove(key, tree, closed, at, rtl);
    }

    function thenItChooses(place: Place): void {
        expect(move?.choose?.path).toBe(place.path);
    }

    function thenItToggles(place: Place): void {
        expect(move?.toggle?.path).toBe(place.path);
        expect(move?.choose).toBeUndefined();
    }

    function thenNothingMoves(): void {
        expect(move).toBeNull();
    }
});

describe('a folder to share', () => {
    let warned: boolean | undefined;

    it.each([
        'C:\\',
        'D:',
        '\\\\nas\\films',
        'C:\\Windows\\Fonts',
        'C:\\Program Files (x86)\\Game',
        'C:\\Users',
        'C:\\Users\\ann',
        'C:\\Users\\ann\\Documents\\Books',
        'C:\\Users\\ann\\AppData\\Roaming',
        'C:\\Users\\ann\\OneDrive\\Pictures',
        '/',
        '/etc',
        '/usr/share/doc',
        '/home',
        '/home/ann',
        '/home/ann/.ssh',
        '/Users/ann/Desktop',
        '/root',
    ])('should be asked about first when it is %s', (path) => {
        whenChecked(path);
        thenItIsWarnedAbout(true);
    });

    it.each([
        'D:\\Shared',
        'C:\\Users\\ann\\Videos',
        'C:\\Users\\Public\\Music',
        '\\\\nas\\films\\new',
        '/srv/share',
        '/home/ann/Downloads',
        '/Users/Shared/Films',
    ])('should be shared at once when it is %s', (path) => {
        whenChecked(path);
        thenItIsWarnedAbout(false);
    });

    function whenChecked(path: string): void {
        warned = mayHoldPersonalFiles(path);
    }

    function thenItIsWarnedAbout(expected: boolean): void {
        expect(warned).toBe(expected);
    }
});
