// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { fileLinks, type FileLink } from '../links';

describe('links', () => {
    const LINK = 'ed2k://|file|My%20File.iso|1048576|0123456789ABCDEF0123456789ABCDEF|/';
    let text = '';
    let found: FileLink[] = [];

    it('should find a link inside other text', () => {
        givenTheText(`look at ${LINK} please`);
        whenTheLinksAreFound();
        thenTheFilesAre('My File.iso');
    });

    it('should keep one link for each file', () => {
        givenTheText(`${LINK}\n${LINK.replace('My%20File', 'Other')}`);
        whenTheLinksAreFound();
        thenTheFilesAre('My File.iso');
    });

    it('should read the size of the file', () => {
        givenTheText(LINK);
        whenTheLinksAreFound();
        thenTheFirstSizeIs(1048576);
    });

    function givenTheText(value: string): void {
        text = value;
    }

    function whenTheLinksAreFound(): void {
        found = fileLinks(text);
    }

    function thenTheFilesAre(...names: string[]): void {
        expect(found.map((f) => f.name)).toEqual(names);
    }

    function thenTheFirstSizeIs(bytes: number): void {
        expect(found[0]?.size).toBe(bytes);
    }
});
