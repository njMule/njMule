// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { initials, nameOf, type Chat, type Friend } from '../chats';

describe('chats', () => {
    let chat: Chat = { address: '203.0.113.5:4662', lines: [] };
    let friends: Friend[] = [];
    let shown = '';

    it('should name a chat after the friend of its user hash before it has a nick', () => {
        givenAChatWithTheUserHash('A1'.repeat(16));
        givenTheFriend({ userHash: 'a1'.repeat(16), name: 'Alice', address: null });
        whenTheChatIsNamed();
        thenItReads('Alice');
    });

    it('should name an unknown chat after its address', () => {
        givenAChatWithTheUserHash(undefined);
        whenTheChatIsNamed();
        thenItReads('203.0.113.5:4662');
    });

    it('should take the initials of the first two words', () => {
        whenTheInitialsAreTaken('night owl_42');
        thenItReads('NO');
    });

    function givenAChatWithTheUserHash(userHash: string | undefined): void {
        chat = { address: '203.0.113.5:4662', lines: [], ...(userHash === undefined ? {} : { userHash }) };
    }

    function givenTheFriend(friend: Friend): void {
        friends = [friend];
    }

    function whenTheChatIsNamed(): void {
        shown = nameOf(chat, friends);
    }

    function whenTheInitialsAreTaken(name: string): void {
        shown = initials(name);
    }

    function thenItReads(text: string): void {
        expect(shown).toBe(text);
    }
});
