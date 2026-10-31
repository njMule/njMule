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
    addressTyped,
    besideName,
    chatWho,
    chatAddress,
    chats,
    entriesOf,
    entryName,
    entryWhere,
    forgetChat,
    friends,
    initials,
    isListed,
    lineText,
    listMove,
    markRead,
    openChat,
    preview,
    readChats,
    readFriends,
    shortTime,
    slot,
    unread,
    type Chat,
    type Entry,
    type Line,
} from '../chats';
import { t } from '../i18n';
import { message, problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import Segments from '../ui/Segments.vue';
import Tip from '../ui/Tip.vue';

// The messages: the conversations and the friends in one list beside the conversation chosen, its lines as bubbles,
// a CAPTCHA to answer when the other side asks for one, and the box to write in.

type Show = 'all' | 'friends';

const find = ref('');
const show = ref<Show>('all');
const asking = ref(false);
const newAddress = ref('');
const chosenKey = ref('');
const text = ref('');
const letters = ref('');
const linesBox = ref<HTMLElement | null>(null);
const list = ref<HTMLElement | null>(null);
const addressBox = ref<HTMLInputElement | null>(null);
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);

const entries = computed(() => entriesOf(chats.value, friends.value));

const SHOWS = computed(() => [
    { value: 'all' as const, title: t('nj.all'), count: entries.value.length },
    {
        value: 'friends' as const,
        title: t('IDS_FRIENDLIST'),
        count: entries.value.filter((e) => e.friend !== undefined).length,
    },
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
const chosenIndex = computed(() => shown.value.findIndex((e) => e.key === chosenKey.value));

const name = entryName;
const where = entryWhere;

function last(e: Entry): string {
    const line = e.chat?.lines.at(-1);
    return line === undefined ? besideName(e) : preview(line);
}

function when(time: string | undefined): string {
    return time === undefined ? '' : shortTime(time);
}

// Above a line's words: its time and who wrote it, "Me" or the nick ("?" while none is known); a notice's time only.
// The nick keeps its own direction.
function meta(l: Line, chat: Chat): string {
    if (l.author === 'notice') {
        return when(l.time);
    }
    return `${when(l.time)}  ${l.author === 'us' ? t('nj.chat.me') : `\u2068${chat.nick || '?'}\u2069`}`;
}

// A row out of sight, hidden by the words to find or by the friends only, shows again when it is chosen.
function choose(key: string): void {
    if (!shown.value.some((e) => e.key === key)) {
        find.value = '';
        show.value = 'all';
    }
    chosenKey.value = key;
}

// With nothing chosen, the newest conversation shows: when the list first comes, and when a conversation opens.
let known: string[] | null = null;
watch(
    () => chats.value.map((c) => c.address),
    (now) => {
        const opened = known === null || now.some((a) => !known!.includes(a));
        known = now;
        const newest = shown.value.find((e) => e.chat !== undefined);
        if (opened && chosen.value === undefined && newest !== undefined) {
            choose(newest.key);
        }
    },
    { immediate: true },
);

// The list is one stop of the Tab key, whose arrows, Home, End and page keys move the choice.
async function listKey(e: KeyboardEvent): Promise<void> {
    const box = list.value;
    const row = box?.querySelector<HTMLElement>('.entry');
    const page = box && row ? Math.max(1, Math.floor(box.clientHeight / row.offsetHeight)) : 1;
    const next = listMove(e.key, shown.value.length, chosenIndex.value, page);
    if (next === null) {
        return;
    }
    e.preventDefault();
    chosenKey.value = shown.value[next]!.key;
    await nextTick();
    document.getElementById(`conversation-${next}`)?.scrollIntoView({ block: 'nearest' });
}

// The address typed keeps only what an address holds; a wrong one is marked, unless the box is empty.
watch(newAddress, (now) => {
    const kept = addressTyped(now);
    if (kept !== now) {
        newAddress.value = kept;
    }
});
const address = computed(() => chatAddress(newAddress.value));
const addressWrong = computed(() => newAddress.value.trim() !== '' && address.value === null);

watch(asking, async (now) => {
    if (now) {
        await nextTick();
        addressBox.value?.focus();
    }
});

// Each conversation keeps where it was scrolled to, unless lines came since: then, as while it shows, it goes to the
// newest. The lines of the conversation shown are read.
const scrolls = new Map<string, { top: number; last: number }>();
const lastLine = (key: string) => entries.value.find((e) => e.key === key)?.chat?.lines.at(-1)?.sequence ?? 0;

watch(
    chosenKey,
    (_now, was) => {
        if (linesBox.value) {
            scrolls.set(was, { top: linesBox.value.scrollTop, last: lastLine(was) });
        }
    },
    { flush: 'pre' },
);

watch(
    () => [chosenKey.value, chosen.value?.chat?.lines.at(-1)?.sequence ?? 0] as const,
    async ([key, last], [wasKey]) => {
        const chat = chosen.value?.chat;
        if (chat !== undefined) {
            markRead(chat);
        }
        await nextTick();
        const box = linesBox.value;
        const kept = scrolls.get(key);
        if (box) {
            box.scrollTop = key !== wasKey && kept?.last === last ? kept.top : box.scrollHeight;
        }
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

// Writing to a friend without a conversation opens one on the friend's address.
async function sendText(): Promise<void> {
    const to = chosen.value ? where(chosen.value) : '';
    if (to === '' || text.value.trim() === '') {
        return;
    }
    if (chosen.value?.chat === undefined) {
        openChat(to);
        choose(to);
    }
    if (await send(text.value, to)) {
        text.value = '';
    }
}

async function sendLetters(): Promise<void> {
    const to = chosen.value?.chat?.address;
    if (to !== undefined && (await send(letters.value, to))) {
        letters.value = '';
    }
}

// Chats reach eD2K clients on IPv4 addresses: a new one is opened and chosen, then written in.
function start(): void {
    const typed = newAddress.value.trim();
    const to = address.value;
    if (to === null) {
        message(t('nj.servers.badAddress', typed));
        return;
    }
    if (to.ipv6) {
        message(t('nj.chat.ipv4Only', typed));
        return;
    }
    openChat(`${to.host}:${to.port}`);
    choose(`${to.host}:${to.port}`);
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
                body: { userHash: chat.userHash, name: chat.nick ?? '', address: chat.address },
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
            // One opened here and never written in is only in this window.
            if (isListed(chat.address)) {
                await answer(api().DELETE('/conversations/{address}', { params: { path: { address: chat.address } } }));
            }
            forgetChat(chat.address);
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
                    <Tip :text="t('nj.input.address')">
                        <input
                            id="chat-address"
                            ref="addressBox"
                            v-model="newAddress"
                            class="field"
                            :class="{ wrong: addressWrong }"
                            dir="ltr"
                            :aria-invalid="addressWrong"
                            :placeholder="t('nj.servers.addressPrompt')"
                        />
                    </Tip>
                    <button class="button" type="submit" :disabled="address === null">
                        <Icon name="MESSAGE" />{{ t('nj.chat.open') }}
                    </button>
                </div>
            </form>
            <div><Segments v-model="show" :items="SHOWS" :label="t('IDS_EM_MESSAGES')" /></div>
            <ul
                id="conversations"
                ref="list"
                class="conversations focusable"
                :class="{ empty: shown.length === 0 }"
                role="listbox"
                tabindex="0"
                :aria-activedescendant="chosenIndex < 0 ? undefined : `conversation-${chosenIndex}`"
                @keydown="listKey"
            >
                <li v-if="shown.length === 0" class="muted placeholder">
                    {{ t(show === 'friends' && find.trim() === '' ? 'nj.chat.noFriends' : 'nj.chat.none') }}
                </li>
                <li
                    v-for="(e, i) in shown"
                    :id="`conversation-${i}`"
                    :key="e.key"
                    class="entry"
                    :class="{ 'has-news': e.chat && unread(e.chat) > 0 }"
                    role="option"
                    :aria-selected="e.key === chosenKey"
                    @click="chosenKey = e.key"
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
                        <span v-if="besideName(chosen)" class="muted" dir="ltr">{{ besideName(chosen) }}</span>
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
                <template v-if="chosen.chat">
                    <div ref="linesBox" class="chat-lines">
                        <p v-if="chosen.chat.lines.length === 0" class="muted empty">
                            {{ t('nj.chat.empty', chatWho(chosen.chat)) }}
                        </p>
                        <div
                            v-for="l in chosen.chat.lines"
                            :key="l.sequence"
                            class="chat-line"
                            :class="`chat-${l.author}`"
                        >
                            <div class="chat-bubble">
                                <span class="chat-meta">{{ meta(l, chosen.chat) }}</span>
                                <span class="chat-text" dir="auto">{{ lineText(l) }}</span>
                            </div>
                        </div>
                    </div>
                    <form v-if="chosen.chat.captcha" class="captcha-card" @submit.prevent="sendLetters">
                        <p class="captcha-text"><Icon name="SHIELD" />{{ t('IDS_CAPTCHAREQUEST') }}</p>
                        <div class="row">
                            <span class="captcha-plate">
                                <img
                                    :src="`data:image/bmp;base64,${chosen.chat.captcha}`"
                                    :alt="t('nj.chat.captchaLetters')"
                                />
                            </span>
                            <input
                                v-model="letters"
                                class="field"
                                dir="ltr"
                                :placeholder="t('nj.chat.captchaLetters')"
                                :aria-label="t('nj.chat.captchaLetters')"
                            />
                            <button class="button" type="submit"><Icon name="SEND" />{{ t('nj.chat.send') }}</button>
                        </div>
                    </form>
                    <form class="write" @submit.prevent="sendText">
                        <input
                            v-model="text"
                            class="field"
                            :placeholder="t('nj.chat.write', chatWho(chosen.chat))"
                            :aria-label="t('nj.chat.write', chatWho(chosen.chat))"
                        />
                        <button class="button accent" type="submit"><Icon name="SEND" />{{ t('nj.chat.send') }}</button>
                    </form>
                </template>
                <!-- A friend without a conversation: a word, and a box that starts one when the friend can be reached. -->
                <template v-else>
                    <p class="muted friend-note">
                        {{ chosen.friend?.address ? t('nj.chat.empty', name(chosen)) : t('nj.chat.noAddress') }}
                    </p>
                    <form class="write" @submit.prevent="sendText">
                        <input
                            v-model="text"
                            class="field"
                            :disabled="!chosen.friend?.address"
                            :placeholder="chosen.friend?.address ? t('nj.chat.write', name(chosen)) : ''"
                            :aria-label="t('nj.chat.write', name(chosen))"
                        />
                        <button class="button accent" type="submit"><Icon name="SEND" />{{ t('nj.chat.send') }}</button>
                    </form>
                </template>
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

/* An address that is none, as the desktop app marks a wrong value. */
.field.wrong {
    border-color: var(--color-danger-emphasis);
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

/* The words of an empty list, wrapped and centred in it. */
.conversations.empty {
    display: flex;
}

.placeholder {
    margin: auto;
    padding: 16px;
    text-align: center;
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

/* What a friend without a conversation shows above the box to write, centred in the room left. */
.friend-note {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    margin: 0;
    text-align: center;
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
    max-width: 75%;
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

/* The time and the author, two spaces apart, above the words. */
.chat-meta {
    color: var(--color-fg-muted);
    font-size: 0.85em;
    white-space: pre-wrap;
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

/* The picture at its own size, as the client drew it. */
.captcha-plate img {
    display: block;
}

.write {
    display: flex;
    gap: 8px;
}

.write .field {
    flex: 1;
}
</style>
