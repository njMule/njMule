// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { folderTakes, type PathKind } from '../screens/settings/folders';
import { checkRules } from '../screens/settings/inputs';

const t = (key: string) => key;

describe('a folder option', () => {
    let path = '';
    let kind: PathKind | undefined;
    let takes = false;

    it('should take an empty path for the default folder', () => {
        givenThePath('', undefined);
        whenChecked();
        thenItTakesIt(true);
    });

    it('should take a folder that is not there yet', () => {
        givenThePath('D:\\Downloads\\new', 'missing');
        whenChecked();
        thenItTakesIt(true);
    });

    it('should refuse a file', () => {
        givenThePath('/home/ann/notes.txt', 'file');
        whenChecked();
        thenItTakesIt(false);
    });

    it('should refuse what is no path on the engine computer', () => {
        givenThePath('C:\\a<b', 'invalid');
        whenChecked();
        thenItTakesIt(false);
    });

    it('should take a path the engine did not tell about yet', () => {
        givenThePath('/srv/incoming', undefined);
        whenChecked();
        thenItTakesIt(true);
    });

    it('should follow what the engine tells in its rules', () => {
        givenThePath('/home/ann/notes.txt', 'file');
        whenCheckedByItsRules();
        thenItTakesIt(false);
    });

    function givenThePath(text: string, named: PathKind | undefined): void {
        path = text;
        kind = named;
    }

    function whenChecked(): void {
        takes = folderTakes(path, kind);
    }

    function whenCheckedByItsRules(): void {
        const rules = checkRules('folder', t, { folder: (text) => folderTakes(text, kind) });
        takes = rules.valid(path);
    }

    function thenItTakesIt(expected: boolean): void {
        expect(takes).toBe(expected);
    }
});
