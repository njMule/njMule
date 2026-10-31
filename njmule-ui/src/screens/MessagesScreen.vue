<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuRoot,
    DropdownMenuTrigger,
} from 'reka-ui';
import { computed, nextTick, ref, watch } from 'vue';
import { answer, api } from '../api/client';
import {
    chats,
    friends,
    friendOf,
    initials,
    markRead,
    nameOf,
    readChats,
    readFriends,
    slot,
    unread,
    type Chat,
    type Friend,
    type Line,
} from '../chats';
import { language, t } from '../i18n';
import { smileys } from '../smileys';
import { message, problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import Segments from '../ui/Segments.vue';
import Tip from '../ui/Tip.vue';

// The messages: the conversations and the friends in one list beside the conversation chosen, its lines as bubbles,
// a CAPTCHA to answer when the other side asks for one, and the box to write in.

interface Entry {
    key: string;
    chat?: Chat;
    friend?: Friend;
}

type Show = 'all' | 'friends';

const find = ref('');
const show = ref<Show>('all');
const asking = ref(false);
const newAddress = ref('');
const chosenKey = ref('');
const text = ref('');
const letters = ref('');
const linesBox = ref<HTMLElement | null>(null);
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);

// The conversations of this run, the latest news first, then the friends with none.
const entries = computed<Entry[]>(() => {
    const latest = (c: Chat) => c.lines.at(-1)?.time ?? '';
    const list: Entry[] = [...chats.value]
        .sort((a, b) => (latest(b) > latest(a) ? 1 : latest(b) < latest(a) ? -1 : 0))
        .map((c) => ({ key: c.address, chat: c, friend: friendOf(c) }));
    for (const f of friends.value) {
        if (!list.some((e) => e.friend?.userHash === f.userHash)) {
            list.push({ key: `friend:${f.userHash}`, friend: f });
        }
    }
    return list;
});

const SHOWS = computed(() => [
    { value: 'all' as const, title: t('IDS_ALL'), count: entries.value.length },
    { value: 'friends' as const, title: t('IDS_FRIENDLIST'), count: friends.value.length },
]);

const shown = computed(() => {
    const words = find.value
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return entries.value.filter(
        (e) =>
            (show.value === 'all' || e.friend !== undefined) &&
            words.every((w) => `${name(e)} ${where(e)}`.toLowerCase().includes(w)),
    );
});

const chosen = computed(() => entries.value.find((e) => e.key === chosenKey.value));

function name(e: Entry): string {
    return e.chat ? nameOf(e.chat) : (e.friend?.name ?? '');
}

function where(e: Entry): string {
    return e.chat?.address ?? e.friend?.address ?? '';
}

function last(e: Entry): string {
    const line = e.chat?.lines.filter((l) => l.author !== 'notice').at(-1);
    if (line === undefined) {
        return where(e) !== name(e) ? where(e) : '';
    }
    return line.author === 'us' ? t('nj.chat.you', line.text) : line.text;
}

function when(time: string | undefined): string {
    return time === undefined
        ? ''
        : new Date(time).toLocaleTimeString(language.value, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

function lineText(l: Line): string {
    switch (l.notice) {
        case 'captcha_passed':
            return t('IDS_CAPTCHASOLVED');
        case 'captcha_wrong':
            return t('IDS_CAPTCHAFAILED');
        case 'not_delivered':
            return t('nj.chat.failed', l.text);
        default:
            return smileys(l.text);
    }
}

// The lines of the conversation shown are read, and it scrolls to the newest as they come.
watch(
    () => chosen.value?.chat?.lines.length,
    async () => {
        const chat = chosen.value?.chat;
        if (chat !== undefined) {
            markRead(chat);
        }
        await nextTick();
        linesBox.value?.scrollTo({ top: linesBox.value.scrollHeight });
    },
);

async function send(words: string, to: string): Promise<boolean> {
    if (words.trim() === '') {
        return false;
    }
    try {
        await answer(api().POST('/messages', { body: { to, text: words } }));
        await readChats();
        return true;
    } catch (e) {
        problem((e as Error).message);
        return false;
    }
}

async function sendText(): Promise<void> {
    const to = chosen.value ? where(chosen.value) : '';
    if (to === '') {
        message(t('nj.chat.noAddress'));
        return;
    }
    if (await send(text.value, to)) {
        text.value = '';
        chosenKey.value = to;
    }
}

async function sendLetters(): Promise<void> {
    const to = chosen.value?.chat?.address;
    if (to !== undefined && (await send(letters.value, to))) {
        letters.value = '';
    }
}

// Chats reach eD2K clients on IPv4 addresses: a new one is opened by choosing it, then writing.
function start(): void {
    const to = newAddress.value.trim();
    if (to.startsWith('[') || (to.match(/:/g) ?? []).length > 1) {
        message(t('nj.chat.ipv4Only', to));
        return;
    }
    if (!/^[^:\s]+:\d{1,5}$/.test(to)) {
        return;
    }
    if (!chats.value.some((c) => c.address === to)) {
        chats.value = [...chats.value, { address: to, lines: [] }];
    }
    chosenKey.value = to;
    asking.value = false;
    newAddress.value = '';
}

async function befriend(): Promise<void> {
    const chat = chosen.value?.chat;
    if (chat?.userHash === undefined) {
        message(t('nj.chat.unknownYet'));
        return;
    }
    try {
        await answer(
            api().POST('/friends', {
                body: { userHash: chat.userHash, name: chat.nick ?? chat.address, address: chat.address },
            }),
        );
        await readFriends();
    } catch (e) {
        problem((e as Error).message);
    }
}

async function removeFriend(): Promise<void> {
    const friend = chosen.value?.friend;
    if (friend) {
        try {
            await answer(api().DELETE('/friends/{userHash}', { params: { path: { userHash: friend.userHash } } }));
            await readFriends();
        } catch (e) {
            problem((e as Error).message);
        }
    }
}

async function toggleSlot(): Promise<void> {
    const friend = chosen.value?.friend;
    if (friend) {
        const holds = slot.value?.toUpperCase() === friend.userHash.toUpperCase();
        try {
            await answer(api().PUT('/friends/slot', { body: { userHash: holds ? null : friend.userHash } }));
            await readFriends();
        } catch (e) {
            problem((e as Error).message);
        }
    }
}

async function close(): Promise<void> {
    const chat = chosen.value?.chat;
    if (chat) {
        try {
            await answer(api().DELETE('/conversations/{address}', { params: { path: { address: chat.address } } }));
            await readChats();
        } catch (e) {
            problem((e as Error).message);
        }
    }
}
</script>

<template>
    <div class="screen">
        <aside class="conversations-side">
            <div class="side-top">
                <label class="field find">
                    <Icon name="SEARCH" />
                    <input v-model="find" :placeholder="t('nj.chat.find')" @keydown.esc="find = ''" />
                </label>
                <Tip :text="t('nj.chat.start')">
                    <button
                        id="new-conversation"
                        class="button icon-only toggle"
                        :aria-pressed="asking"
                        :aria-label="t('nj.chat.start')"
                        @click="asking = !asking"
                    >
                        <Icon name="ADD" />
                    </button>
                </Tip>
            </div>
            <form v-if="asking" class="new-conversation" @submit.prevent="start">
                <label for="chat-address">{{ t('IDS_SV_ADDRESS') }}</label>
                <div class="row">
                    <input
                        id="chat-address"
                        v-model="newAddress"
                        class="field"
                        dir="ltr"
                        :placeholder="t('nj.servers.addressPrompt')"
                    />
                    <button class="button" type="submit" :disabled="!/^[^:\s]+:\d{1,5}$/.test(newAddress.trim())">
                        <Icon name="MESSAGE" />{{ t('nj.chat.open') }}
                    </button>
                </div>
            </form>
            <div><Segments v-model="show" :items="SHOWS" :label="t('IDS_EM_MESSAGES')" /></div>
            <ul id="conversations" class="conversations">
                <li v-if="shown.length === 0" class="muted placeholder">
                    {{ t(show === 'friends' && find === '' ? 'nj.chat.noFriends' : 'nj.chat.none') }}
                </li>
                <li
                    v-for="e in shown"
                    :key="e.key"
                    class="entry"
                    :class="{ 'has-news': e.chat && unread(e.chat) > 0 }"
                    :aria-selected="e.key === chosenKey"
                    tabindex="0"
                    @click="chosenKey = e.key"
                    @keydown.enter="chosenKey = e.key"
                >
                    <span class="avatar">
                        <template v-if="initials(name(e))">{{ initials(name(e)) }}</template>
                        <Icon v-else name="MESSAGE" />
                    </span>
                    <span class="entry-text">
                        <span class="entry-top">
                            <span class="who ellipsis" dir="auto">{{ name(e) }}</span>
                            <Icon v-if="e.friend" class="friend-star" name="STAR" />
                            <span class="spacer" />
                            <span class="muted time">{{ when(e.chat?.lines.at(-1)?.time) }}</span>
                        </span>
                        <span class="entry-bottom">
                            <span class="muted ellipsis" dir="auto">{{ last(e) }}</span>
                            <span v-if="e.chat && unread(e.chat) > 0" class="news-badge">{{ unread(e.chat) }}</span>
                        </span>
                    </span>
                </li>
            </ul>
        </aside>

        <section class="conversation">
            <p v-if="chosen === undefined" class="muted none">{{ t('nj.chat.none') }}</p>
            <template v-else>
                <header class="person-head">
                    <span class="avatar">
                        <template v-if="initials(name(chosen))">{{ initials(name(chosen)) }}</template>
                        <Icon v-else name="MESSAGE" />
                    </span>
                    <div class="who-box">
                        <strong class="person-name ellipsis" dir="auto">{{ name(chosen) }}</strong>
                        <span v-if="where(chosen) !== name(chosen)" class="muted" dir="ltr">{{ where(chosen) }}</span>
                    </div>
                    <button
                        v-if="chosen.friend"
                        class="button toggle"
                        :aria-pressed="slot?.toUpperCase() === chosen.friend.userHash.toUpperCase()"
                        @click="toggleSlot"
                    >
                        <Icon name="STAR" />{{ t('IDS_FRIENDSLOT') }}
                    </button>
                    <button v-else class="button" @click="befriend">
                        <Icon name="PERSON_ADD" />{{ t('nj.chat.addFriend') }}
                    </button>
                    <DropdownMenuRoot>
                        <Tip :text="t('IDS_MORE')">
                            <DropdownMenuTrigger ref="moreTrigger" class="button icon-only" :aria-label="t('IDS_MORE')">
                                <Icon name="MORE" />
                            </DropdownMenuTrigger>
                        </Tip>
                        <DropdownMenuPortal>
                            <DropdownMenuContent class="menu" align="end" :reference="moreTrigger?.$el">
                                <DropdownMenuItem class="menu-item" :disabled="!chosen.friend" @select="removeFriend">
                                    <Icon name="DELETE" />{{ t('IDS_REMOVEFRIEND') }}
                                </DropdownMenuItem>
                                <DropdownMenuItem class="menu-item" :disabled="!chosen.chat" @select="close">
                                    <Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}
                                </DropdownMenuItem>
                            </DropdownMenuContent>
                        </DropdownMenuPortal>
                    </DropdownMenuRoot>
                </header>
                <div ref="linesBox" class="chat-lines">
                    <p v-if="!chosen.chat || chosen.chat.lines.length === 0" class="muted empty">
                        {{ t('nj.chat.empty', name(chosen)) }}
                    </p>
                    <div
                        v-for="l in chosen.chat?.lines ?? []"
                        :key="l.sequence"
                        class="chat-line"
                        :class="`chat-${l.author}`"
                    >
                        <div class="chat-bubble">
                            <span class="chat-text" dir="auto">{{ lineText(l) }}</span>
                            <span class="chat-meta">{{ when(l.time) }}</span>
                        </div>
                    </div>
                </div>
                <form v-if="chosen.chat?.captcha" class="captcha-card" @submit.prevent="sendLetters">
                    <p class="captcha-text"><Icon name="SHIELD" />{{ t('IDS_CAPTCHAREQUEST') }}</p>
                    <div class="row">
                        <span class="captcha-plate">
                            <img :src="`data:image/bmp;base64,${chosen.chat.captcha}`" alt="CAPTCHA" />
                        </span>
                        <input
                            v-model="letters"
                            class="field"
                            maxlength="16"
                            :placeholder="t('nj.chat.captchaLetters')"
                        />
                        <button class="button" type="submit"><Icon name="SEND" />{{ t('nj.chat.send') }}</button>
                    </div>
                </form>
                <form class="write" @submit.prevent="sendText">
                    <input
                        v-model="text"
                        class="field"
                        :placeholder="t('nj.chat.write', name(chosen))"
                        :aria-label="t('nj.chat.write', name(chosen))"
                    />
                    <button class="button accent" type="submit"><Icon name="SEND" />{{ t('nj.chat.send') }}</button>
                </form>
            </template>
        </section>
    </div>
</template>

<style scoped>
.screen {
    display: flex;
    gap: 16px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
}

.conversations-side {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 0 1 290px;
    min-width: 240px;
    max-width: 320px;
}

.side-top {
    display: flex;
    gap: 8px;
}

.find {
    flex: 1;
}

.new-conversation {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 10px;
    border-radius: 8px;
    background: var(--color-bg-subtle);
}

.row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.row .field {
    flex: 1;
    min-width: 0;
}

.conversations {
    flex: 1;
    min-height: 0;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
}

.placeholder {
    padding: 16px;
}

/* The conversations and the friends in one list: the person's letters on a pastel disc, the name bold while it has
   news, which a badge counts. */
.entry {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 10px;
    border-radius: 8px;
    cursor: default;
}

.entry[aria-selected='true'] {
    background: var(--color-accent-muted);
}

.avatar {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    flex: none;
    border-radius: 50%;
    background: var(--color-accent-subtle);
    font-weight: bold;
    font-size: 0.85em;
    --nj-icon-color: var(--color-fg-muted);
}

.entry-text {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
}

.entry-top,
.entry-bottom {
    display: flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
}

.has-news .who {
    font-weight: bold;
}

.friend-star {
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-warning-fg);
}

.spacer {
    flex: 1;
}

.time {
    font-size: 0.92em;
}

.news-badge {
    margin-inline-start: auto;
    padding: 0 6px;
    border-radius: 9px;
    background: var(--nj-brand-accent);
    color: var(--nj-on-accent);
    font-size: 0.8em;
    font-weight: bold;
}

.conversation {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-width: 0;
    min-height: 0;
}

.none {
    margin: auto;
}

.person-head {
    display: flex;
    align-items: center;
    gap: 12px;
    padding-bottom: 10px;
    border-bottom: 1px solid var(--color-border-muted);
}

.who-box {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
}

.person-name {
    font-size: 1.15em;
}

.chat-lines {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
    padding: 6px 0;
    overflow-y: auto;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
}

.empty {
    margin: auto;
}

/* Chat lines as bubbles on pastel tints: ours accent, theirs neutral. */
.chat-line {
    display: flex;
    padding: 3px 8px;
}

.chat-us {
    justify-content: flex-end;
}

.chat-notice {
    justify-content: center;
}

.chat-bubble {
    display: flex;
    flex-direction: column;
    gap: 2px;
    max-width: 70%;
    padding: 5px 10px 6px;
    border-radius: 10px;
    background: var(--color-bg-subtle);
}

.chat-us .chat-bubble {
    background: var(--color-accent-muted);
}

.chat-notice .chat-bubble {
    background: transparent;
    color: var(--color-fg-muted);
    font-style: italic;
}

.chat-text {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}

.chat-meta {
    color: var(--color-fg-muted);
    font-size: 0.85em;
}

/* A picture asked before a message goes through: on a light plate, read in every theme. */
.captcha-card {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 12px;
    border-radius: 8px;
    background: var(--color-warning-subtle);
}

.captcha-text {
    display: flex;
    gap: 8px;
    margin: 0;
    --nj-icon-color: var(--color-warning-fg);
}

.captcha-plate {
    padding: 6px 10px;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--nj-captcha-paper);
}

.captcha-plate img {
    display: block;
    height: 32px;
}

.write {
    display: flex;
    gap: 8px;
}

.write .field {
    flex: 1;
}
</style>
