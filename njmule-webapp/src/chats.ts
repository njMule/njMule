// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { computed, ref } from 'vue';
import { api } from './api';

export interface Line {
    sequence: number;
    time: string;
    author: 'us' | 'them' | 'notice';
    text: string;
    notice?: 'captcha_passed' | 'captcha_wrong' | 'not_delivered';
}

export interface Chat {
    address: string;
    userHash?: string;
    nick?: string;
    captcha?: string;
    lines: Line[];
}

export interface Friend {
    userHash: string;
    name: string;
    address: string | null;
}

/** The engine's chats of this run, read every few seconds wherever the page is. */
export const chats = ref<Chat[]>([]);

const SEEN = 'njmule.chat.seen';

// The last line of theirs read in this browser, by chat.
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
            // Kept until the page reloads.
        }
    }
}

export async function readChats(): Promise<void> {
    try {
        chats.value = await api.get<Chat[]>('/conversations');
    } catch {
        // The top bar tells when the engine does not answer.
    }
}

export function followChats(): void {
    void readChats();
    window.setInterval(() => void readChats(), 3000);
}

/** The name a chat shows: the nick, a friend's name, the user hash, or the address. */
export function nameOf(chat: Chat, friends: Friend[]): string {
    const friend = friendOf(chat, friends);
    return chat.nick ?? friend?.name ?? chat.userHash ?? chat.address;
}

/** The friend of a chat, by user hash, or by address while the hash is not known. */
export function friendOf(chat: Chat, friends: Friend[]): Friend | undefined {
    return friends.find((f) =>
        chat.userHash !== undefined
            ? f.userHash.toUpperCase() === chat.userHash.toUpperCase()
            : f.address === chat.address,
    );
}

/** The initials of a name, for its avatar. */
export function initials(name: string): string {
    const words = name.split(/[\s._-]+/).filter((w) => w !== '');
    return ((words[0]?.[0] ?? '?') + (words[1]?.[0] ?? '')).toUpperCase();
}
