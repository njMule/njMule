// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { connect } from '../api/client';
import type { Platform } from '../platform';
import { browsesHere, folderTakes, type PathKind } from '../screens/settings/folders';
import { checkRules } from '../screens/settings/inputs';
import type { Kind } from '../settings';

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

describe('the Browse button of a path', () => {
    const HERE = 'http://127.0.0.1:4711/api/v1';
    const ELSEWHERE = 'http://nas.local:4711/api/v1';
    let platform: Platform;
    let browses = false;

    it.each<Kind>(['folder', 'command', 'sound', 'program'])(
        'should choose a %s in the desktop window on the engine computer',
        (kind) => {
            givenAWindow(true, HERE);
            whenAskedWhetherItBrowses(kind);
            thenItBrowses(true);
        },
    );

    it.each<Kind>(['folder', 'command'])('should leave a %s of another computer typed', (kind) => {
        givenAWindow(true, ELSEWHERE);
        whenAskedWhetherItBrowses(kind);
        thenItBrowses(false);
    });

    it.each<Kind>(['sound', 'program'])('should choose a %s of this computer in a window on another', (kind) => {
        givenAWindow(true, ELSEWHERE);
        whenAskedWhetherItBrowses(kind);
        thenItBrowses(true);
    });

    it.each<Kind>(['folder', 'sound'])('should leave a %s typed in a browser', (kind) => {
        givenAWindow(false, HERE);
        whenAskedWhetherItBrowses(kind);
        thenItBrowses(false);
    });

    function givenAWindow(desktop: boolean, url: string): void {
        platform = { desktop } as Platform;
        connect({ url, token: 'secret' }, () => {});
    }

    function whenAskedWhetherItBrowses(kind: Kind): void {
        browses = browsesHere(kind, platform);
    }

    function thenItBrowses(expected: boolean): void {
        expect(browses).toBe(expected);
    }
});
