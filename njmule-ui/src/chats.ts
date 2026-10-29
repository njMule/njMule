// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { computed, ref, shallowRef } from 'vue';
import { answer, api, type Schemas } from './api/client';

// The conversations of this run and the friends, read every few seconds wherever the window is, with what is not read
// yet in this window.

export type Chat = Schemas['Conversation'];
export type Line = Chat['lines'][number];
export type Friend = Schemas['Friend'];

export const chats = shallowRef<Chat[]>([]);
export const friends = shallowRef<Friend[]>([]);
/** The friend who has a slot of ours kept, if any. */
export const slot = ref<string | null>(null);

const SEEN = 'njmule.chat.seen';

// The last line of theirs read in this window, by chat.
const seen = ref<Record<string, number>>(load());

function load(): Record<string, number> {
    try {
        return JSON.parse(localStorage.getItem(SEEN) ?? '{}') as Record<string, number>;
    } catch {
        return {};
    }
}

/** Lines of theirs not read yet, by chat. */
export function unread(chat: Chat): number {
    const last = seen.value[chat.address] ?? 0;
    return chat.lines.filter((l) => l.author === 'them' && l.sequence > last).length;
}

export const unreadTotal = computed(() => chats.value.reduce((sum, c) => sum + unread(c), 0));

export function markRead(chat: Chat): void {
    const last = chat.lines.at(-1)?.sequence ?? 0;
    if ((seen.value[chat.address] ?? 0) < last) {
        seen.value = { ...seen.value, [chat.address]: last };
        try {
            localStorage.setItem(SEEN, JSON.stringify(seen.value));
        } catch {
            // Kept until the window closes.
        }
    }
}

export async function readChats(): Promise<void> {
    try {
        chats.value = await answer(api().GET('/conversations'));
    } catch {
        // The status strip tells when the engine does not answer.
    }
}

export async function readFriends(): Promise<void> {
    try {
        const [list, held] = await Promise.all([answer(api().GET('/friends')), answer(api().GET('/friends/slot'))]);
        friends.value = list;
        slot.value = held.userHash;
    } catch {
        // Kept as they were.
    }
}

export function followChats(): void {
    void readChats();
    void readFriends();
    window.setInterval(() => void readChats(), 3000);
    window.setInterval(() => void readFriends(), 10000);
}

/** The friend a chat is with, by user hash or address. */
export function friendOf(chat: Chat): Friend | undefined {
    return friends.value.find(
        (f) =>
            (chat.userHash !== undefined && f.userHash.toUpperCase() === chat.userHash.toUpperCase()) ||
            f.address === chat.address,
    );
}

/** The name a chat shows: the nick, a friend's name, or the address. */
export function nameOf(chat: Chat): string {
    return chat.nick ?? friendOf(chat)?.name ?? chat.address;
}

/** Two letters for a person's disc: the first of each word of the name, or the first two. */
export function initials(name: string): string {
    const words = name.split(/[\s._-]+/).filter((w) => w !== '' && /\p{L}/u.test(w[0]!));
    if (words.length === 0) {
        return '';
    }
    return (words.length > 1 ? words[0]![0]! + words[1]![0]! : words[0]![0]!).toUpperCase();
}
