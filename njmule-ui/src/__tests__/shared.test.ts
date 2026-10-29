// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { holds, places, type Place } from '../shared';

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
