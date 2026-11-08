// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h } from 'vue';

type Startup = typeof import('../startup');
type I18n = typeof import('../i18n');

const ENGLISH: Record<string, string> = {
    'nj.startup.starting': 'Starting njMule…',
    'nj.startup.failed': 'njMule could not start.',
};

describe('the window before the interface shows', () => {
    let startup: Startup;
    let i18n: I18n;
    let place: HTMLElement;
    let stop: () => void = () => {};
    let caught: unknown = null;

    beforeEach(async () => {
        // A fresh module each time: the texts read stay for the module's life.
        vi.resetModules();
        i18n = await import('../i18n');
        startup = await import('../startup');
        place = document.createElement('div');
        document.body.append(place);
        caught = null;
        vi.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
        vi.restoreAllMocks();
        document.body.innerHTML = '';
    });

    it('should say that njMule is starting once the start lasts', async () => {
        await givenTheEnglishTexts();
        givenTheStartIsWaitedFor();

        whenTimePasses(startup.STARTING_DELAY_MS);

        thenTheWindowSays('Starting njMule…');
        thenItTellsAs('status');
    });

    it('should show nothing in between when the start is quick', async () => {
        await givenTheEnglishTexts();
        givenTheStartIsWaitedFor();
        givenTheStartEndsAfter(startup.STARTING_DELAY_MS - 100);

        whenTimePasses(startup.STARTING_DELAY_MS);

        thenTheWindowIsEmpty();
    });

    it('should stop saying that njMule is starting once the start ends', async () => {
        await givenTheEnglishTexts();
        givenTheStartIsWaitedFor();
        givenTimePassed(startup.STARTING_DELAY_MS);

        whenTheStartEnds();

        thenTheWindowIsEmpty();
    });

    it('should tell that njMule could not start, and why', async () => {
        await givenTheEnglishTexts();

        whenTheStartFailedWith(new Error('njMule did not start in time.'));

        thenTheWindowSays('njMule could not start.', 'njMule did not start in time.');
        thenItTellsAs('alert');
    });

    it('should tell the failure in English when no texts could be read', () => {
        whenTheStartFailedWith(new Error('Failed to fetch'));

        thenTheWindowSays(
            "njMule could not start. Try again, and if it happens again, report it with this message and njMule's log.",
            'Failed to fetch',
        );
    });

    it('should fail the mount when the root of the interface cannot set itself up', () => {
        whenMounted(rootFailing('the root broke'));

        thenTheMountFailedWith('the root broke');
    });

    it('should mount the interface when only a part of it cannot set itself up', () => {
        whenMounted(partFailing('a part broke'));

        thenTheMountSucceeded();
    });

    async function givenTheEnglishTexts(): Promise<void> {
        vi.stubGlobal(
            'fetch',
            vi.fn(async () => new Response(JSON.stringify(ENGLISH), { status: 200 })),
        );
        await i18n.loadLanguage('en');
    }

    function givenTheStartIsWaitedFor(): void {
        vi.useFakeTimers();
        stop = startup.sayStarting(place);
    }

    function givenTheStartEndsAfter(ms: number): void {
        vi.advanceTimersByTime(ms);
        stop();
    }

    function givenTimePassed(ms: number): void {
        vi.advanceTimersByTime(ms);
    }

    function rootFailing(message: string) {
        return defineComponent({
            setup() {
                throw new Error(message);
            },
        });
    }

    function partFailing(message: string) {
        const part = defineComponent({
            setup() {
                throw new Error(message);
            },
        });
        return defineComponent({ render: () => h('main', [h('p', 'the rest'), h(part)]) });
    }

    function whenTimePasses(ms: number): void {
        vi.advanceTimersByTime(ms);
    }

    function whenTheStartEnds(): void {
        stop();
    }

    function whenTheStartFailedWith(failure: unknown): void {
        startup.sayFailed(place, failure);
    }

    function whenMounted(root: ReturnType<typeof defineComponent>): void {
        try {
            startup.mountOrFail(createApp(root), place);
        } catch (e) {
            caught = e;
        }
    }

    function thenTheWindowSays(...lines: string[]): void {
        expect([...place.querySelectorAll('p')].map((p) => p.textContent)).toEqual(lines);
    }

    function thenItTellsAs(role: string): void {
        expect(place.firstElementChild?.getAttribute('role')).toBe(role);
    }

    function thenTheWindowIsEmpty(): void {
        expect(place.childElementCount).toBe(0);
    }

    function thenTheMountFailedWith(message: string): void {
        expect((caught as Error).message).toBe(message);
    }

    function thenTheMountSucceeded(): void {
        expect(caught).toBeNull();
        expect(place.textContent).toContain('the rest');
    }
});
