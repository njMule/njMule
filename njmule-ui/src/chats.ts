// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { computed, ref, shallowRef } from 'vue';
import { answer, api, type Schemas } from './api/client';
import { language, t } from './i18n';
import { smileys } from './smileys';

// The conversations of this run and the friends, read every few seconds wherever the window is, with what is not read
// yet in this window.

export type Chat = Schemas['Conversation'];
export type Line = Chat['lines'][number];
export type Friend = Schemas['Friend'];

/** The conversations: the engine's, and those opened here that nobody wrote in yet. */
export const chats = shallowRef<Chat[]>([]);
export const friends = shallowRef<Friend[]>([]);
/** The friend who has a slot of ours kept, if any. */
export const slot = ref<string | null>(null);

// The engine lists a conversation once a line was written in it: one opened here waits in this window until closed.
let listed: Chat[] = [];
let opened: string[] = [];

function merge(): void {
    chats.value = [
        ...listed,
        ...opened.filter((a) => !listed.some((c) => c.address === a)).map((address) => ({ address, lines: [] })),
    ];
}

/** Opens the conversation with {@code address}, kept in this window until closed. */
export function openChat(address: string): void {
    if (!opened.includes(address)) {
        opened = [...opened, address];
        merge();
    }
}

/** Whether the engine knows the conversation with {@code address}, which may only be opened here. */
export function isListed(address: string): boolean {
    return listed.some((c) => c.address === address);
}

/** Forgets the conversation with {@code address}, until the engine lists it again. */
export function forgetChat(address: string): void {
    opened = opened.filter((a) => a !== address);
    listed = listed.filter((c) => c.address !== address);
    merge();
}

// The last line read, by chat; in memory, as what came before the window opened was not read in it.
const seen = ref<Record<string, number>>({});

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
    }
}

export async function readChats(): Promise<void> {
    try {
        listed = await answer(api().GET('/conversations'));
        merge();
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

/** A row of the list: a conversation, a friend, or a conversation with a friend. */
export interface Entry {
    key: string;
    chat?: Chat;
    friend?: Friend;
}

/** The friend of {@code chat}: by user hash, or by address while the hash is not known. */
export function friendOf(chat: Chat, all: Friend[]): Friend | undefined {
    const hash = chat.userHash?.toUpperCase();
    return all.find((f) => (hash === undefined ? f.address === chat.address : f.userHash.toUpperCase() === hash));
}

/** The conversations, each with its friend if it has one, the latest news first; then the other friends by name. */
export function entriesOf(chatList: Chat[], all: Friend[]): Entry[] {
    const latest = (c: Chat) => c.lines.at(-1)?.time ?? '';
    // Two conversations without news in common: the one opened last comes first.
    const newest = chatList
        .map((chat, i) => ({ chat, i }))
        .sort((a, b) => (latest(a.chat) === latest(b.chat) ? b.i - a.i : latest(a.chat) < latest(b.chat) ? 1 : -1));
    const entries: Entry[] = newest.map(({ chat }) => ({ key: chat.address, chat, friend: friendOf(chat, all) }));
    const alone = all
        .filter((f) => !entries.some((e) => e.friend === f))
        .sort((a, b) =>
            a.name.toLowerCase() < b.name.toLowerCase() ? -1 : a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0,
        );
    return [...entries, ...alone.map((friend) => ({ key: `friend:${friend.userHash}`, friend }))];
}

/** The name a row shows: the nick, the friend's name or else user hash, or the address. */
export function entryName(e: Entry): string {
    if (e.chat?.nick) {
        return e.chat.nick;
    }
    if (e.friend) {
        return e.friend.name === '' ? e.friend.userHash : e.friend.name;
    }
    return e.chat?.address ?? '';
}

/** Where the person of a row is reached. */
export function entryWhere(e: Entry): string {
    return e.chat?.address ?? e.friend?.address ?? '';
}

/** Where the person is, unless the name says it already. */
export function besideName(e: Entry): string {
    const where = entryWhere(e);
    return where === entryName(e) ? '' : where;
}

/** The first letters of up to two words of the name, or none for an address. */
export function initials(name: string): string {
    if (name === '' || /^[\p{Nd}\[]/u.test(name)) {
        return '';
    }
    return name
        .split(/\s+/)
        .filter((w) => w !== '')
        .slice(0, 2)
        .map((w) => String.fromCodePoint(w.codePointAt(0)!))
        .join('')
        .toUpperCase();
}

/** What a line says: a notice in the language of the window, the words of people with their smileys as emoji. */
export function lineText(line: Line): string {
    switch (line.notice) {
        case 'captcha_passed':
            return t('IDS_CAPTCHASOLVED');
        case 'captcha_wrong':
            return t('IDS_CAPTCHAFAILED');
        case 'not_delivered':
            return t('nj.chat.failed', line.text);
        default:
            return smileys(line.text);
    }
}

/** The last line in a few words: ours after "You", a notice as it reads. */
export function preview(line: Line): string {
    return line.author === 'us' ? t('nj.chat.you', lineText(line)) : lineText(line);
}

/** A time of the day in the language's short form, as 15:04 or 3:04 PM. */
export function shortTime(time: string): string {
    return new Date(time).toLocaleTimeString(language.value, { timeStyle: 'short' });
}

/** Where the arrow keys, Home, End and the page keys take the choice in a list of {@code count} rows; null for others. */
export function listMove(key: string, count: number, at: number, page: number): number | null {
    const last = count - 1;
    if (count === 0) {
        return null;
    }
    switch (key) {
        case 'ArrowDown':
            return Math.min(last, at + 1);
        case 'ArrowUp':
            return Math.max(0, at - 1);
        case 'Home':
            return 0;
        case 'End':
            return last;
        case 'PageDown':
            return Math.min(last, Math.max(0, at) + page);
        case 'PageUp':
            return Math.max(0, at - page);
        default:
            return null;
    }
}

const MAX_ADDRESS = 253 + 8;
const IPV4 = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const LABEL = /^[A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?$/;

/** {@code text} with only what an address holds: letters and digits, dots, dashes, colons and brackets. */
export function addressTyped(text: string): string {
    return text.replace(/[^A-Za-z0-9.:[\]-]/g, '').substring(0, MAX_ADDRESS);
}

// A host name as the DNS takes it: labels of letters, digits, dashes and underscores, the last not starting with a digit.
function isHostName(host: string): boolean {
    const name = host.endsWith('.') ? host.slice(0, -1) : host;
    const labels = name.split('.');
    return name.length > 0 && name.length <= 253 && labels.every((l) => LABEL.test(l)) && !/^\d/.test(labels.at(-1)!);
}

/** The {@code host:port} typed, with an IPv6 address in brackets; null when it is none. */
export function chatAddress(text: string): { host: string; port: number; ipv6: boolean } | null {
    const typed = text.trim();
    const m = /^\[([0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*)\]:(\d{1,5})$/.exec(typed) ?? /^([^:[\]]+):(\d{1,5})$/.exec(typed);
    const port = Number(m?.[2]);
    if (!m || port < 1 || port > 65535) {
        return null;
    }
    const ipv6 = typed.startsWith('[');
    return ipv6 || IPV4.test(m[1]!) || isHostName(m[1]!) ? { host: m[1]!, port, ipv6 } : null;
}
