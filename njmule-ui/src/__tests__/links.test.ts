// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { fileLink, fileLinks, htmlLinks, type FileLink } from '../links';

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

describe('the links written for files', () => {
    const HASH = '0123456789ABCDEF0123456789ABCDEF';
    let name = '';
    let written = '';

    it('should encode spaces, percent signs and bars of a name', () => {
        givenTheName('a b%c|d');
        whenTheLinkIsWritten();
        thenTheLinkIs(`ed2k://|file|a%20b%25c%7Cd|10|${HASH}|/`);
    });

    it('should keep the printable marks of a name as they are', () => {
        givenTheName("[x] (y), z&w+v='u'.avi");
        whenTheLinkIsWritten();
        thenTheLinkIs(`ed2k://|file|[x]%20(y),%20z&w+v='u'.avi|10|${HASH}|/`);
    });

    it('should encode other letters in UTF-8', () => {
        givenTheName('città 日');
        whenTheLinkIsWritten();
        thenTheLinkIs(`ed2k://|file|citt%C3%A0%20%E6%97%A5|10|${HASH}|/`);
    });

    it('should write the links as HTML with named entities', () => {
        whenTheLinksAreWrittenAsHtml([{ link: 'ed2k://|file|a&b|/', name: `<"it's">` }]);
        thenTheLinkIs('<a href="ed2k://|file|a&amp;b|/">&lt;&quot;it&#39;s&quot;&gt;</a>');
    });

    function givenTheName(value: string): void {
        name = value;
    }

    function whenTheLinkIsWritten(): void {
        written = fileLink(name, 10, HASH);
    }

    function whenTheLinksAreWrittenAsHtml(links: { link: string; name: string }[]): void {
        written = htmlLinks(links);
    }

    function thenTheLinkIs(expected: string): void {
        expect(written).toBe(expected);
    }
});
