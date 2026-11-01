// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from 'vitest';
import { connect } from '../api/client';
import { logOut } from '../logout';

describe('the logout', () => {
    let sent: HTMLFormElement[] = [];

    afterEach(() => {
        vi.restoreAllMocks();
        document.body.innerHTML = '';
        sent = [];
    });

    it("should send the web interface's logout form with the session's token", () => {
        givenTheSessionsToken('t0ken');
        givenFormsAreSentHere();

        whenTheUserLogsOut();

        thenOneFormWasSent();
        thenTheFormIsSentTo('post', 'logout');
        thenTheFormCarries({ csrf: 't0ken' });
    });

    function givenTheSessionsToken(csrf: string): void {
        connect({ url: 'api/v1', csrf }, () => {});
    }

    function givenFormsAreSentHere(): void {
        vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (this: HTMLFormElement) {
            sent.push(this);
        });
    }

    function whenTheUserLogsOut(): void {
        logOut();
    }

    function thenOneFormWasSent(): void {
        expect(sent).toHaveLength(1);
    }

    function thenTheFormIsSentTo(method: string, action: string): void {
        expect(sent[0]!.method).toBe(method);
        expect(sent[0]!.getAttribute('action')).toBe(action);
    }

    function thenTheFormCarries(fields: Record<string, string>): void {
        expect(Object.fromEntries(new FormData(sent[0]!) as unknown as Iterable<[string, string]>)).toEqual(fields);
    }
});
