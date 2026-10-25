<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInput, ElSegmented } from 'element-plus';
import { computed, nextTick, ref, watch } from 'vue';
import { api } from '../api';
import {
    chats,
    friendOf,
    initials,
    markRead,
    nameOf,
    readChats,
    unread,
    type Chat,
    type Friend,
    type Line,
} from '../chats';
import NjIcon from '../components/NjIcon.vue';
import { usePoll } from '../engine';
import { language, t } from '../i18n';
import { attempt, told } from '../notify';
import { smileys } from '../smileys';

const friends = usePoll(() => api.get<Friend[]>('/friends'), 10000, [] as Friend[]);
const slot = usePoll(() => api.get<{ userHash: string | null }>('/friends/slot'), 10000, { userHash: null });
const find = ref('');
const showing = ref<'all' | 'friends'>('all');
const starting = ref(false);
const newAddress = ref('');
const text = ref('');
const letters = ref('');
// A chat, or a friend without one, by address or user hash.
const chosen = ref('');
const lines = ref<HTMLElement | null>(null);

interface Row {
    key: string;
    name: string;
    chat?: Chat;
    friend?: Friend;
}

const rows = computed<Row[]>(() => {
    const all: Row[] = chats.value.map((c) => ({
        key: c.address,
        name: nameOf(c, friends.data.value),
        chat: c,
        friend: friendOf(c, friends.data.value),
    }));
    const talking = new Set(all.map((r) => r.friend?.userHash));
    for (const f of friends.data.value) {
        if (!talking.has(f.userHash)) {
            all.push({ key: f.userHash, name: f.name, friend: f });
        }
    }
    const words = find.value.toLowerCase();
    return all.filter(
        (r) =>
            (showing.value === 'all' || r.friend !== undefined) &&
            (words === '' || `${r.name} ${r.chat?.address ?? ''}`.toLowerCase().includes(words)),
    );
});

const row = computed(() => rows.value.find((r) => r.key === chosen.value) ?? rows.value[0]);
const address = computed(() => row.value?.chat?.address ?? row.value?.friend?.address ?? null);

const SHOWING = computed(() => [
    { value: 'all', label: t('nj.all') },
    { value: 'friends', label: t('IDS_FRIENDLIST') },
]);

watch(
    () => row.value?.chat?.lines.length,
    async () => {
        if (row.value?.chat) {
            markRead(row.value.chat);
        }
        await nextTick();
        lines.value?.scrollTo({ top: lines.value.scrollHeight });
    },
);

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

function when(time: string): string {
    return new Date(time).toLocaleTimeString(language.value, { hour: '2-digit', minute: '2-digit' });
}

function preview(r: Row): string {
    const last = r.chat?.lines.at(-1);
    if (last === undefined) {
        return '';
    }
    return last.author === 'us' ? t('nj.chat.you', smileys(last.text)) : lineText(last);
}

async function send(message: string): Promise<boolean> {
    const to = address.value;
    if (to === null || message.trim() === '') {
        return false;
    }
    const sent = await attempt(() => api.post('/messages', { to, text: message.trim() }));
    await readChats();
    if (row.value?.chat === undefined) {
        chosen.value = to;
    }
    return sent;
}

async function sendText(): Promise<void> {
    if (await send(text.value)) {
        text.value = '';
    }
}

async function sendLetters(): Promise<void> {
    if (await send(letters.value)) {
        letters.value = '';
    }
}

// Chats reach eD2K clients on IPv4 addresses: a new one is opened by sending to it.
function start(): void {
    const where = newAddress.value.trim();
    if (where.startsWith('[') || (where.match(/:/g) ?? []).length > 1) {
        told(t('nj.chat.ipv4Only', where));
        return;
    }
    if (!/^[^\s:]+:\d{1,5}$/.test(where)) {
        told(t('nj.servers.badAddress', where));
        return;
    }
    chats.value = [{ address: where, lines: [] }, ...chats.value.filter((c) => c.address !== where)];
    chosen.value = where;
    starting.value = false;
    newAddress.value = '';
}

async function addFriend(): Promise<void> {
    const chat = row.value?.chat;
    if (chat?.userHash === undefined) {
        told(t('nj.chat.unknownYet'));
        return;
    }
    await attempt(() =>
        api.post('/friends', { userHash: chat.userHash, name: chat.nick ?? chat.address, address: chat.address }),
    );
    await friends.refresh();
}

async function removeFriend(): Promise<void> {
    const friend = row.value?.friend;
    if (friend) {
        await attempt(() => api.delete(`/friends/${friend.userHash}`));
        await friends.refresh();
    }
}

async function toggleSlot(): Promise<void> {
    const friend = row.value?.friend;
    if (friend) {
        const holds = slot.data.value.userHash?.toUpperCase() === friend.userHash.toUpperCase();
        await attempt(() => api.put('/friends/slot', { userHash: holds ? null : friend.userHash }));
        await slot.refresh();
    }
}

async function close(): Promise<void> {
    const chat = row.value?.chat;
    if (chat) {
        await attempt(() => api.delete(`/conversations/${encodeURIComponent(chat.address)}`));
        await readChats();
    }
}
</script>

<template>
    <div class="layout">
        <aside class="list">
            <div class="find">
                <el-input v-model="find" clearable :placeholder="t('nj.chat.find')" />
                <el-button :title="t('nj.chat.start')" @click="starting = !starting"><NjIcon name="ADD" /></el-button>
            </div>
            <form v-if="starting" class="start" @submit.prevent="start">
                <el-input
                    v-model="newAddress"
                    :placeholder="t('nj.servers.addressPrompt')"
                    :aria-label="t('IDS_SV_ADDRESS')"
                />
                <el-button native-type="submit">{{ t('nj.chat.open') }}</el-button>
            </form>
            <el-segmented v-model="showing" :options="SHOWING" />
            <ul>
                <li v-for="r in rows" :key="r.key">
                    <button type="button" :class="{ current: row?.key === r.key }" @click="chosen = r.key">
                        <span class="avatar">{{ initials(r.name) }}</span>
                        <span class="who">
                            <b>{{ r.name }}<NjIcon v-if="r.friend" name="STAR" class="star" /></b>
                            <small>{{ preview(r) }}</small>
                        </span>
                        <span v-if="r.chat && unread(r.chat) > 0" class="badge">{{ unread(r.chat) }}</span>
                    </button>
                </li>
            </ul>
            <p v-if="rows.length === 0" class="hint">
                {{ showing === 'friends' ? t('nj.chat.noFriends') : t('nj.chat.none') }}
            </p>
        </aside>

        <section v-if="row" class="chat">
            <header>
                <span class="avatar">{{ initials(row.name) }}</span>
                <div class="who">
                    <b>{{ row.name }}</b>
                    <small v-if="address && address !== row.name">{{ address }}</small>
                </div>
                <span class="grow"></span>
                <el-button v-if="row.friend" @click="toggleSlot">
                    <NjIcon name="STAR" />{{ t('IDS_FRIENDSLOT') }}
                    <template v-if="slot.data.value.userHash?.toUpperCase() === row.friend.userHash.toUpperCase()">
                        ✓
                    </template>
                </el-button>
                <el-button v-if="row.chat && !row.friend" @click="addFriend">
                    <NjIcon name="PERSON_ADD" />{{ t('nj.chat.addFriend') }}
                </el-button>
                <el-button v-if="row.friend" @click="removeFriend">{{ t('IDS_REMOVEFRIEND') }}</el-button>
                <el-button v-if="row.chat" @click="close">{{ t('IDS_FD_CLOSE') }}</el-button>
            </header>
            <ol ref="lines" class="lines">
                <li v-for="l in row.chat?.lines ?? []" :key="l.sequence" :class="l.author">
                    <small
                        >{{ when(l.time) }}
                        {{ l.author === 'us' ? t('nj.chat.me') : l.author === 'them' ? row.name : '' }}</small
                    >
                    <p>{{ lineText(l) }}</p>
                </li>
            </ol>
            <p v-if="(row.chat?.lines.length ?? 0) === 0" class="hint">
                {{ address === null ? t('nj.chat.noAddress') : t('nj.chat.empty', row.name) }}
            </p>
            <form v-if="row.chat?.captcha" class="captcha" @submit.prevent="sendLetters">
                <p>{{ t('IDS_CAPTCHAREQUEST') }}</p>
                <img :src="`data:image/bmp;base64,${row.chat.captcha}`" alt="CAPTCHA" />
                <el-input v-model="letters" :placeholder="t('nj.chat.captchaLetters')" maxlength="16" />
                <el-button native-type="submit">{{ t('nj.chat.send') }}</el-button>
            </form>
            <form v-if="address !== null" class="composer" @submit.prevent="sendText">
                <el-input v-model="text" :placeholder="t('nj.chat.write', row.name)" maxlength="450" />
                <el-button type="primary" native-type="submit"><NjIcon name="SEND" />{{ t('nj.chat.send') }}</el-button>
            </form>
        </section>
        <p v-else class="hint">{{ t('nj.chat.none') }}</p>
    </div>
</template>

<style scoped>
.layout {
    display: grid;
    grid-template-columns: 300px minmax(0, 1fr);
    gap: 16px;
    min-height: 60vh;
}
.find,
.start,
.composer {
    display: flex;
    gap: 8px;
    margin-bottom: 10px;
}
.list ul {
    margin: 10px 0 0;
    padding: 0;
    list-style: none;
}
.list li button {
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 8px;
    border: 0;
    border-radius: 8px;
    background: none;
    color: var(--el-text-color-primary);
    font: inherit;
    text-align: start;
    cursor: pointer;
}
.list li button.current {
    background: var(--el-fill-color);
}
.avatar {
    display: inline-flex;
    flex: none;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    border-radius: 50%;
    background: var(--nj-busy-bg);
    color: var(--nj-busy-fg);
    font-weight: 600;
    font-size: 13px;
}
.who {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
}
.who b,
.who small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.who small {
    color: var(--el-text-color-secondary);
}
.star {
    width: 14px;
    height: 14px;
    margin-inline-start: 4px;
}
.badge {
    min-width: 22px;
    padding: 0 7px;
    border-radius: 10px;
    background: var(--nj-accent);
    color: var(--nj-on-accent);
    font-size: 12px;
    text-align: center;
}
.chat {
    display: flex;
    flex-direction: column;
    padding: 12px 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.chat header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    padding-bottom: 10px;
    border-bottom: 1px solid var(--el-border-color);
}
.chat header .el-button + .el-button {
    margin-left: 0;
}
.el-button .nj-icon {
    width: 16px;
    height: 16px;
    margin-inline-end: 6px;
}
.grow {
    flex: 1;
}
.lines {
    flex: 1;
    max-height: 55vh;
    overflow-y: auto;
    margin: 0;
    padding: 12px 0;
    list-style: none;
}
.lines li {
    max-width: 75%;
    margin: 6px 0;
}
.lines li p {
    margin: 2px 0 0;
    padding: 8px 12px;
    border-radius: 12px;
    background: var(--el-fill-color-light);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}
.lines li.us {
    margin-inline-start: auto;
    text-align: end;
}
.lines li.us p {
    background: var(--nj-busy-bg);
    color: var(--nj-busy-fg);
    text-align: start;
}
.lines li.notice {
    max-width: none;
    text-align: center;
}
.lines li.notice p {
    background: none;
    color: var(--el-text-color-secondary);
    font-style: italic;
}
.lines small {
    color: var(--el-text-color-secondary);
}
.captcha {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-bottom: 10px;
    padding: 10px;
    border-radius: 8px;
    background: var(--nj-warn-bg);
    color: var(--nj-warn-fg);
}
.captcha p {
    flex-basis: 100%;
    margin: 0;
}
.captcha .el-input {
    width: 200px;
}
.hint {
    color: var(--el-text-color-secondary);
}
@media (max-width: 900px) {
    .layout {
        grid-template-columns: 1fr;
    }
}
</style>
