// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { close, message, offer, problem, toasts } from '../toasts';

describe('the messages over the window', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        toasts.value.forEach((t) => close(t.id));
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it('should count the same message up on the one shown', () => {
        givenAMessage('Download finished: a.iso');
        whenAMessage('Download finished: a.iso');
        thenTheMessagesAre([['Download finished: a.iso', 2]]);
    });

    it('should show a problem beside a message of the same words', () => {
        givenAMessage('Cannot write');
        whenAProblem('Cannot write');
        thenTheMessagesAre([
            ['Cannot write', 1],
            ['Cannot write', 1],
        ]);
    });

    it('should never count an offer up', () => {
        givenAnOffer('Removed');
        whenAnOffer('Removed');
        thenTheMessagesAre([
            ['Removed', 1],
            ['Removed', 1],
        ]);
    });

    it('should push the oldest out past two', () => {
        givenAMessage('one');
        givenAMessage('two');
        whenAMessage('three');
        thenTheMessagesAre([
            ['two', 1],
            ['three', 1],
        ]);
    });

    it('should give the same message its whole time again', () => {
        givenAMessage('again');
        givenTimePasses(5000);
        whenAMessage('again');
        thenTheMessagesAreAfter(5000, [['again', 2]]);
    });

    it('should let a message go after a few seconds', () => {
        givenAMessage('short');
        whenTimePasses(6000);
        thenTheMessagesAre([]);
    });

    it('should keep a problem until it is closed', () => {
        givenAProblem('stays');
        whenTimePasses(60_000);
        thenTheMessagesAre([['stays', 1]]);
    });

    function givenAMessage(text: string): void {
        message(text);
    }

    function givenAProblem(text: string): void {
        problem(text);
    }

    function givenAnOffer(text: string): void {
        offer(text, 'Undo', () => {});
    }

    function givenTimePasses(ms: number): void {
        vi.advanceTimersByTime(ms);
    }

    function whenAMessage(text: string): void {
        message(text);
    }

    function whenAProblem(text: string): void {
        problem(text);
    }

    function whenAnOffer(text: string): void {
        givenAnOffer(text);
    }

    function whenTimePasses(ms: number): void {
        vi.advanceTimersByTime(ms);
    }

    function thenTheMessagesAre(expected: [string, number][]): void {
        expect(toasts.value.map((t) => [t.text, t.count])).toEqual(expected);
    }

    function thenTheMessagesAreAfter(ms: number, expected: [string, number][]): void {
        vi.advanceTimersByTime(ms);
        thenTheMessagesAre(expected);
    }
});
