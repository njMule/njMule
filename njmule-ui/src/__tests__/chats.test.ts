// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    chatAddress,
    chatWho,
    entriesOf,
    entryName,
    initials,
    listMove,
    preview,
    type Chat,
    type Entry,
    type Friend,
    type Line,
} from '../chats';

const ANN = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const BOB = 'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB';

function chat(address: string, fields: Partial<Chat> = {}, times: string[] = []): Chat {
    return {
        address,
        lines: times.map((time, i) => ({ sequence: i + 1, time, author: 'them', text: 'hi' })),
        ...fields,
    };
}

function friend(userHash: string, name: string, address: string | null = null): Friend {
    return { userHash, name, address };
}

describe('the list of the messages', () => {
    let chats: Chat[] = [];
    let friends: Friend[] = [];
    let entries: Entry[] = [];

    it('should put the conversation with the latest news first', () => {
        givenTheChats(
            chat('1.1.1.1:4662', {}, ['2026-10-31T10:00:00Z']),
            chat('2.2.2.2:4662', {}, ['2026-10-31T11:00:00Z']),
        );
        whenListed();
        thenTheKeysAre(['2.2.2.2:4662', '1.1.1.1:4662']);
    });

    it('should put the conversation opened last first among those without news', () => {
        givenTheChats(chat('1.1.1.1:4662'), chat('2.2.2.2:4662'));
        whenListed();
        thenTheKeysAre(['2.2.2.2:4662', '1.1.1.1:4662']);
    });

    it('should list the friends without a conversation after them, by name', () => {
        givenTheChats(chat('1.1.1.1:4662'));
        givenTheFriends(friend(BOB, 'zoe'), friend(ANN, 'Al'));
        whenListed();
        thenTheKeysAre(['1.1.1.1:4662', `friend:${ANN}`, `friend:${BOB}`]);
    });

    it('should find the friend of a conversation by the user hash once it is known', () => {
        givenTheChats(chat('1.1.1.1:4662', { userHash: BOB }));
        givenTheFriends(friend(ANN, 'Ann', '1.1.1.1:4662'), friend(BOB, 'Bob'));
        whenListed();
        thenTheFriendOfTheFirstIs(BOB);
    });

    it('should find the friend of a conversation by the address while the hash is not known', () => {
        givenTheChats(chat('1.1.1.1:4662'));
        givenTheFriends(friend(ANN, 'Ann', '1.1.1.1:4662'));
        whenListed();
        thenTheFriendOfTheFirstIs(ANN);
    });

    function givenTheChats(...list: Chat[]): void {
        chats = list;
        friends = [];
    }

    function givenTheFriends(...list: Friend[]): void {
        friends = list;
    }

    function whenListed(): void {
        entries = entriesOf(chats, friends);
    }

    function thenTheKeysAre(keys: string[]): void {
        expect(entries.map((e) => e.key)).toEqual(keys);
    }

    function thenTheFriendOfTheFirstIs(hash: string): void {
        expect(entries[0]?.friend?.userHash).toBe(hash);
    }
});

describe('the name of a row', () => {
    let named = '';

    it('should be the nick of the conversation', () => {
        whenNamed({ key: 'a', chat: chat('1.1.1.1:4662', { nick: 'ann' }), friend: friend(ANN, 'Annie') });
        thenItIs('ann');
    });

    it('should be the user hash of a friend without a name', () => {
        whenNamed({ key: 'a', friend: friend(ANN, '') });
        thenItIs(ANN);
    });

    it('should be the address of a stranger without a nick', () => {
        whenNamed({ key: 'a', chat: chat('1.1.1.1:4662', { nick: '' }) });
        thenItIs('1.1.1.1:4662');
    });

    function whenNamed(e: Entry): void {
        named = entryName(e);
    }

    function thenItIs(expected: string): void {
        expect(named).toBe(expected);
    }
});

describe('the letters of a person', () => {
    let letters = '';

    it.each([
        ['ann lee smith', 'AL'],
        ['john_doe', 'J'],
        ['abc 1x', 'A1'],
        ['2pac x', ''],
        ['[::1]:4662', ''],
        ['', ''],
    ])('should be those of the first two words of %s', (name, expected) => {
        whenTakenFrom(name);
        thenTheyAre(expected);
    });

    function whenTakenFrom(name: string): void {
        letters = initials(name);
    }

    function thenTheyAre(expected: string): void {
        expect(letters).toBe(expected);
    }
});

describe('the last line of a row', () => {
    let shown = '';

    it('should show the smileys of their words as emoji', () => {
        whenPreviewed({ sequence: 1, time: '', author: 'them', text: 'see you :)' });
        thenItReads('see you 🙂');
    });

    it('should show a notice in the language of the window', () => {
        whenPreviewed({ sequence: 1, time: '', author: 'notice', text: '', notice: 'captcha_passed' });
        thenItReads('[IDS_CAPTCHASOLVED]');
    });

    it('should tell our own words', () => {
        whenPreviewed({ sequence: 1, time: '', author: 'us', text: 'hello' });
        thenItReads('[nj.chat.you]');
    });

    function whenPreviewed(line: Line): void {
        shown = preview(line);
    }

    function thenItReads(expected: string): void {
        expect(shown).toBe(expected);
    }
});

describe('an address to chat with', () => {
    let read: ReturnType<typeof chatAddress> = null;

    it.each(['192.168.1.10:4662', 'peer.example.org:4662', 'localhost:1'])('should take %s', (typed) => {
        whenRead(typed);
        thenItIsAnAddress(false);
    });

    it('should take an IPv6 address in brackets, to tell that chats reach IPv4 ones only', () => {
        whenRead('[::1]:4662');
        thenItIsAnAddress(true);
    });

    it.each(['192.168.1.10', '192.168.1.10:0', 'host:70000', '::1:4662', '1.2.3.400:4662', '-peer.org:4662', ':4662'])(
        'should refuse %s',
        (typed) => {
            whenRead(typed);
            thenItIsNone();
        },
    );

    function whenRead(typed: string): void {
        read = chatAddress(typed);
    }

    function thenItIsAnAddress(ipv6: boolean): void {
        expect(read?.ipv6).toBe(ipv6);
    }

    function thenItIsNone(): void {
        expect(read).toBeNull();
    }
});

describe('the keys of a list', () => {
    let at: number | null = null;

    it.each([
        ['ArrowDown', 2, 3],
        ['ArrowUp', 2, 1],
        ['Home', 2, 0],
        ['End', 2, 9],
        ['PageDown', 2, 6],
        ['PageUp', 2, 0],
        ['ArrowDown', -1, 0],
        ['ArrowDown', 9, 9],
    ])('should move the choice with %s from row %i to row %i', (key, from, to) => {
        whenPressed(key as string, from as number);
        thenTheChoiceIs(to as number);
    });

    it('should leave the other keys to the page', () => {
        whenPressed('Enter', 2);
        thenTheChoiceIs(null);
    });

    function whenPressed(key: string, from: number): void {
        at = listMove(key, 10, from, 4);
    }

    function thenTheChoiceIs(expected: number | null): void {
        expect(at).toBe(expected);
    }
});

describe('the person of a conversation', () => {
    let who = '';

    it('should go by the nick in the conversation', () => {
        whenNamedIn(chat('1.1.1.1:4662', { nick: 'ann' }));
        thenTheyGoBy('ann');
    });

    it('should go by the address while the nick is not known', () => {
        whenNamedIn(chat('1.1.1.1:4662', { nick: '' }));
        thenTheyGoBy('1.1.1.1:4662');
    });

    function whenNamedIn(c: Chat): void {
        who = chatWho(c);
    }

    function thenTheyGoBy(expected: string): void {
        expect(who).toBe(expected);
    }
});
