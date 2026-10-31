// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import type { Download } from '../model/engine';
import {
    actsFirst,
    appliesTo,
    byCategory,
    byName,
    byPriority,
    byState,
    countryName,
    keeps,
    rating,
    named,
    priorityKey,
    remaining,
    shown,
    sourceProgram,
    withoutColon,
    type Shown,
    type StateFilter,
} from '../transfers';

function download(fields: Partial<Download>): Download {
    return {
        hash: '0123456789ABCDEF0123456789ABCDEF',
        name: 'file.iso',
        size: 1000,
        completed: 0,
        status: 'downloading',
        priority: 'normal',
        category: 0,
        sources: 0,
        transferring: 0,
        bytesPerSecond: 0,
        previewFirst: false,
        link: '',
        ...fields,
    };
}

describe('the state a download shows', () => {
    let given = download({});
    let state: Shown | undefined;

    it('should be downloading while a source sends', () => {
        givenARunningDownload(3, 1);
        whenItsStateIsShown();
        thenItIs('downloading');
    });

    it('should be waiting while its sources only queue', () => {
        givenARunningDownload(3, 0);
        whenItsStateIsShown();
        thenItIs('waiting');
    });

    it('should tell a running download without sources', () => {
        givenARunningDownload(0, 0);
        whenItsStateIsShown();
        thenItIs('noSources');
    });

    function givenARunningDownload(sources: number, transferring: number): void {
        given = download({ status: 'downloading', sources, transferring });
    }

    function whenItsStateIsShown(): void {
        state = shown(given);
    }

    function thenItIs(expected: Shown): void {
        expect(state).toBe(expected);
    }
});

describe('the state chips', () => {
    let given = download({});
    let kept: boolean | undefined;

    it('should keep a stopped download under paused', () => {
        givenADownload('stopped');
        whenTheChipNarrows('paused');
        thenItIsKept(true);
    });

    it('should keep a download short of disk space under errors', () => {
        givenADownload('no_space');
        whenTheChipNarrows('errors');
        thenItIsKept(true);
    });

    it('should not keep a complete download under downloading', () => {
        givenADownload('complete');
        whenTheChipNarrows('downloading');
        thenItIsKept(false);
    });

    function givenADownload(status: Download['status']): void {
        given = download({ status });
    }

    function whenTheChipNarrows(filter: StateFilter): void {
        kept = keeps(filter, given);
    }

    function thenItIsKept(expected: boolean): void {
        expect(kept).toBe(expected);
    }
});

describe('the filter by name', () => {
    let kept: boolean | undefined;

    it('should keep a name that holds every word in any case', () => {
        whenTheFilterIs('LUMENOS dvd', 'lumenos-4.2-amd64-dvd.iso');
        thenTheNameIsKept(true);
    });

    it('should not keep a name missing one of the words', () => {
        whenTheFilterIs('lumenos beta', 'lumenos-4.2-amd64-dvd.iso');
        thenTheNameIsKept(false);
    });

    function whenTheFilterIs(text: string, name: string): void {
        kept = named(text)({ name });
    }

    function thenTheNameIsKept(expected: boolean): void {
        expect(kept).toBe(expected);
    }
});

describe('the time left', () => {
    let seconds: number | undefined;

    it('should be the bytes missing at the current rate', () => {
        whenTheTimeLeftIsTold(1000, 400, 100);
        thenItIs(6);
    });

    it('should be unknown while nothing arrives', () => {
        whenTheTimeLeftIsTold(1000, 400, 0);
        thenItIs(-1);
    });

    function whenTheTimeLeftIsTold(size: number, completed: number, bytesPerSecond: number): void {
        seconds = remaining({ size, completed, bytesPerSecond });
    }

    function thenItIs(expected: number): void {
        expect(seconds).toBe(expected);
    }
});

describe('an automatic priority', () => {
    let key: string | undefined;

    it('should be high for a rare file', () => {
        whenThePriorityIsNamed(20);
        thenItIs('IDS_PRIOAUTOHIGH');
    });

    it('should be low for a file with many sources', () => {
        whenThePriorityIsNamed(101);
        thenItIs('IDS_PRIOAUTOLOW');
    });

    function whenThePriorityIsNamed(sources: number): void {
        key = priorityKey({ priority: 'auto', sources });
    }

    function thenItIs(expected: string): void {
        expect(key).toBe(expected);
    }
});

describe('a label as a column title', () => {
    let title: string | undefined;

    it('should leave out the colon a label ends with', () => {
        whenTheLabelBecomesATitle('Uploaded total: ');
        thenTheTitleIs('Uploaded total');
    });

    it('should keep a title without a colon as it is', () => {
        whenTheLabelBecomesATitle('Last Seen');
        thenTheTitleIs('Last Seen');
    });

    function whenTheLabelBecomesATitle(label: string): void {
        title = withoutColon(label);
    }

    function thenTheTitleIs(expected: string): void {
        expect(title).toBe(expected);
    }
});

describe('the name of a country', () => {
    let name: string | undefined;

    it('should be the name in the language shown', () => {
        whenTheCountryIsNamed('it', 'en');
        thenTheNameIs('Italy');
    });

    it('should be the code when no country has it', () => {
        whenTheCountryIsNamed('xy', 'en');
        thenTheNameIs('XY');
    });

    function whenTheCountryIsNamed(code: string, language: string): void {
        name = countryName(code, language);
    }

    function thenTheNameIs(expected: string): void {
        expect(name).toBe(expected);
    }
});

describe('the order of names', () => {
    let order: string[] = [];

    it('should put capitals before small letters, as the desktop app does', () => {
        whenTheNamesAreSorted(['beta.iso', 'Alpha.iso', 'alpha.iso']);
        thenTheOrderIs(['Alpha.iso', 'alpha.iso', 'beta.iso']);
    });

    it('should compare figures as characters, not as numbers', () => {
        whenTheNamesAreSorted(['part2', 'part10']);
        thenTheOrderIs(['part10', 'part2']);
    });

    function whenTheNamesAreSorted(names: string[]): void {
        order = [...names].sort(byName);
    }

    function thenTheOrderIs(expected: string[]): void {
        expect(order).toEqual(expected);
    }
});

describe('the order of the buttons of a dialog', () => {
    let first: boolean | undefined;

    it('should put the button that acts first on Windows', () => {
        whenTheButtonsAreOrderedOn('Win32');
        thenTheOneThatActsIsFirst(true);
    });

    it('should put the button that acts last elsewhere', () => {
        whenTheButtonsAreOrderedOn('MacIntel');
        thenTheOneThatActsIsFirst(false);
    });

    function whenTheButtonsAreOrderedOn(platform: string): void {
        first = actsFirst(platform);
    }

    function thenTheOneThatActsIsFirst(expected: boolean): void {
        expect(first).toBe(expected);
    }
});

describe('the downloads a command applies to', () => {
    let given: Download[] = [];
    let changed: Download['status'][] = [];

    it('should pause only the running ones, leaving a stopped one stopped', () => {
        givenDownloads('downloading', 'stopped', 'complete');
        whenTheCommandIsGiven('pause');
        thenItChanges(['downloading']);
    });

    it('should stop all but the stopped and the complete ones', () => {
        givenDownloads('downloading', 'paused', 'stopped', 'complete');
        whenTheCommandIsGiven('stop');
        thenItChanges(['downloading', 'paused']);
    });

    function givenDownloads(...statuses: Download['status'][]): void {
        given = statuses.map((status) => download({ status }));
    }

    function whenTheCommandIsGiven(command: 'pause' | 'resume' | 'stop'): void {
        changed = appliesTo(command, given).map((d) => d.status);
    }

    function thenItChanges(expected: Download['status'][]): void {
        expect(changed).toEqual(expected);
    }
});

describe('the order of the downloads', () => {
    let given: Download[] = [];
    let order: string[] = [];

    it('should sort the states from what runs to what is done', () => {
        givenDownloads(
            download({ name: 'done', status: 'complete' }),
            download({ name: 'paused', status: 'paused' }),
            download({ name: 'running', status: 'downloading', sources: 2, transferring: 1 }),
        );
        whenTheyAreSortedBy(byState);
        thenTheOrderIs(['running', 'paused', 'done']);
    });

    it('should sort the priorities by level, an automatic one after a set one', () => {
        givenDownloads(
            download({ name: 'auto high', priority: 'auto', sources: 3 }),
            download({ name: 'high', priority: 'high' }),
            download({ name: 'low', priority: 'low' }),
        );
        whenTheyAreSortedBy(byPriority);
        thenTheOrderIs(['low', 'high', 'auto high']);
    });

    it('should sort the categories by their number, not their name', () => {
        givenDownloads(download({ name: 'second', category: 2 }), download({ name: 'first', category: 1 }));
        whenTheyAreSortedBy(byCategory);
        thenTheOrderIs(['first', 'second']);
    });

    function givenDownloads(...list: Download[]): void {
        given = list;
    }

    function whenTheyAreSortedBy(compare: (a: Download, b: Download) => number): void {
        order = [...given].sort(compare).map((d) => d.name);
    }

    function thenTheOrderIs(expected: string[]): void {
        expect(order).toEqual(expected);
    }
});

describe('a rating', () => {
    let key: string | undefined;

    it('should be named as clients number it', () => {
        whenTheRatingIsNamed(4);
        thenItIs('IDS_CMT_GOOD');
    });

    it('should be the best there is when a client gives more', () => {
        whenTheRatingIsNamed(9);
        thenItIs('IDS_CMT_EXCELLENT');
    });

    function whenTheRatingIsNamed(value: number): void {
        key = rating(value).key;
    }

    function thenItIs(expected: string): void {
        expect(key).toBe(expected);
    }
});

describe('a source under its download', () => {
    let shownProgram: string | undefined;

    it('should tell its place in the queue after its program', () => {
        whenTheSourceIsShown('eMule v0.70b', 12);
        thenItShows('eMule v0.70b QR: 12');
    });

    it('should tell only its program outside a queue', () => {
        whenTheSourceIsShown('aMule 2.3.3', 0);
        thenItShows('aMule 2.3.3');
    });

    function whenTheSourceIsShown(software: string, queueRank: number): void {
        shownProgram = sourceProgram({ software, queueRank });
    }

    function thenItShows(expected: string): void {
        expect(shownProgram).toBe(expected);
    }
});
