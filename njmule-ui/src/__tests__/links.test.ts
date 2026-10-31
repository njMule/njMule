// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it } from 'vitest';
import { fileLink, fileLinks, htmlLinks, type FileLink } from '../links';

describe('links', () => {
    const HASH = '0123456789ABCDEF0123456789ABCDEF';
    const OTHER_HASH = 'FEDCBA9876543210FEDCBA9876543210';
    const LINK = `ed2k://|file|My%20File.iso|1048576|${HASH}|/`;
    const MAGNET = `magnet:?xt=urn:ed2k:${HASH.toLowerCase()}&xl=1048576&dn=My%20File.iso`;
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

    it('should take a link a browser handed over with its separators escaped', () => {
        givenTheText(LINK.replaceAll('|', '%7C'));
        whenTheLinksAreFound();
        thenTheFilesAre('My File.iso');
        thenTheLinksAre(LINK);
    });

    it('should take the magnet link of an eD2K file as it is', () => {
        givenTheText(`get ${MAGNET} now`);
        whenTheLinksAreFound();
        thenTheFilesAre('My File.iso');
        thenTheFirstSizeIs(1048576);
        thenTheLinksAre(MAGNET);
    });

    it('should take the eD2K hash of a magnet link that names it the long way', () => {
        givenTheText(MAGNET.replace('urn:ed2k:', 'urn:ed2khash:'));
        whenTheLinksAreFound();
        thenTheFilesAre('My File.iso');
    });

    it('should leave a magnet link of another network', () => {
        givenTheText('magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=Other&xl=10');
        whenTheLinksAreFound();
        thenTheFilesAre();
    });

    it('should keep the order of eD2K and magnet links', () => {
        givenTheText(`${MAGNET.replace(HASH.toLowerCase(), OTHER_HASH).replace('My%20File', 'First')}\n${LINK}`);
        whenTheLinksAreFound();
        thenTheFilesAre('First.iso', 'My File.iso');
    });

    it('should keep one link for a file named by an eD2K and a magnet link', () => {
        givenTheText(`${LINK}\n${MAGNET}`);
        whenTheLinksAreFound();
        thenTheLinksAre(LINK);
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

    function thenTheLinksAre(...links: string[]): void {
        expect(found.map((f) => f.link)).toEqual(links);
    }

    function thenTheFirstSizeIs(bytes: number): void {
        expect(found[0]?.size).toBe(bytes);
    }
});

describe('the links written for files', () => {
    const HASH = '0123456789ABCDEF0123456789ABCDEF';
    const AICH = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    let name = '';
    let aich: string | undefined;
    let written = '';

    beforeEach(() => {
        aich = undefined;
    });

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

    it('should carry the AICH hash of a file when it is known', () => {
        givenTheName('a.iso');
        givenTheAichHash(AICH);
        whenTheLinkIsWritten();
        thenTheLinkIs(`ed2k://|file|a.iso|10|${HASH}|h=${AICH}|/`);
    });

    it('should write the links as HTML with named entities', () => {
        whenTheLinksAreWrittenAsHtml([{ link: 'ed2k://|file|a&b|/', name: `<"it's">` }]);
        thenTheLinkIs('<a href="ed2k://|file|a&amp;b|/">&lt;&quot;it&#39;s&quot;&gt;</a>');
    });

    function givenTheName(value: string): void {
        name = value;
    }

    function givenTheAichHash(value: string): void {
        aich = value;
    }

    function whenTheLinkIsWritten(): void {
        written = fileLink(name, 10, HASH, aich);
    }

    function whenTheLinksAreWrittenAsHtml(links: { link: string; name: string }[]): void {
        written = htmlLinks(links);
    }

    function thenTheLinkIs(expected: string): void {
        expect(written).toBe(expected);
    }
});
