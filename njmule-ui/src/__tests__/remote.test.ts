// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { connectable, remoteApi, unreachable } from '../remote';

describe('the address of a remote njMule', () => {
    let api: string | null = null;

    it('should add the scheme and the path of the API to a host and port', () => {
        whenTyped('  nas.local:4711 ');
        thenTheApiIs('http://nas.local:4711/api/v1');
    });

    it('should add the path of the API to the address of a web interface', () => {
        whenTyped('https://nas.example.org/');
        thenTheApiIs('https://nas.example.org/api/v1');
    });

    it('should keep the path of an API behind a reverse proxy', () => {
        whenTyped('https://example.org/njmule/api/v1');
        thenTheApiIs('https://example.org/njmule/api/v1');
    });

    it.each(['', 'ftp://nas.local/api/v1', 'http://'])('should take %j as no address', (typed) => {
        whenTyped(typed);
        thenTheApiIs(null);
    });

    function whenTyped(typed: string): void {
        api = remoteApi(typed);
    }

    function thenTheApiIs(expected: string | null): void {
        expect(api).toBe(expected);
    }
});

describe('what a connection needs', () => {
    const remembered = ['http://nas.local:4711/api/v1'];
    let enough = false;

    it('should need a token for an address not remembered', () => {
        whenAsked('http://other:4711/api/v1', ' ');
        thenItIsEnough(false);
    });

    it('should take the token remembered for an address', () => {
        whenAsked('http://nas.local:4711/api/v1', '');
        thenItIsEnough(true);
    });

    it('should need an address', () => {
        whenAsked(null, 'token');
        thenItIsEnough(false);
    });

    function whenAsked(api: string | null, token: string): void {
        enough = connectable(api, token, remembered);
    }

    function thenItIsEnough(expected: boolean): void {
        expect(enough).toBe(expected);
    }
});

describe('reaching a remote njMule', () => {
    let answer: () => Promise<Response> = async () => new Response('{}');
    let asked: { url: string; authorization: string } | null = null;
    let problem: string | null = 'not asked';

    it('should reach an API that takes the token', async () => {
        givenTheApiAnswers(200, '{"running":true}');
        await whenReached();
        thenTheProblemIs(null);
        thenTheStatusWasAskedWithTheToken();
    });

    it("should tell a refusal in the API's words", async () => {
        givenTheApiAnswers(401, '{"error":"Wrong token."}');
        await whenReached();
        thenTheProblemIs('Wrong token.');
    });

    it('should tell the status of an answer without words', async () => {
        givenTheApiAnswers(502, 'Bad gateway');
        await whenReached();
        thenTheProblemIs('502');
    });

    it('should tell why nothing answered', async () => {
        givenNothingAnswers('Failed to fetch');
        await whenReached();
        thenTheProblemIs('Failed to fetch');
    });

    function givenTheApiAnswers(status: number, body: string): void {
        answer = async () => new Response(body, { status });
    }

    function givenNothingAnswers(reason: string): void {
        answer = async () => {
            throw new TypeError(reason);
        };
    }

    async function whenReached(): Promise<void> {
        problem = await unreachable({ url: 'http://nas.local:4711/api/v1', token: 'secret' }, (async (
            url: string,
            init: RequestInit,
        ) => {
            asked = { url, authorization: new Headers(init.headers).get('Authorization') ?? '' };
            return answer();
        }) as typeof fetch);
    }

    function thenTheProblemIs(expected: string | null): void {
        expect(problem).toBe(expected);
    }

    function thenTheStatusWasAskedWithTheToken(): void {
        expect(asked).toEqual({ url: 'http://nas.local:4711/api/v1/status', authorization: 'Bearer secret' });
    }
});
