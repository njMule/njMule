<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { TooltipProvider } from 'reka-ui';
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { answer, api, endpoint } from './api/client';
import { duration, rate, size } from './format';
import { t } from './i18n';
import { fileLinks } from './links';
import { connection, on, preferences, running, serverName, shared, status, uploads } from './model/engine';
import { networkWords } from './network';
import { current, PLACES, searchRequest, shortcut, withShortcutKey } from './places';
import { searches } from './search';
import { chats, friends, unreadTotal } from './chats';
import type { Command } from './palette';
import { engineHere, type Platform } from './platform';
import SearchScreen from './screens/SearchScreen.vue';
import MessagesScreen from './screens/MessagesScreen.vue';
import NetworkScreen from './screens/NetworkScreen.vue';
import SettingsScreen from './screens/SettingsScreen.vue';
import SharedScreen from './screens/SharedScreen.vue';
import StatisticsScreen from './screens/StatisticsScreen.vue';
import TransfersScreen from './screens/TransfersScreen.vue';
import AskDialog from './shell/AskDialog.vue';
import Banner from './shell/Banner.vue';
import CommandPalette from './shell/CommandPalette.vue';
import ShortcutsDialog from './shell/ShortcutsDialog.vue';
import Sidebar from './shell/Sidebar.vue';
import StatusStrip from './shell/StatusStrip.vue';
import Toasts from './shell/Toasts.vue';
import TopBar from './shell/TopBar.vue';
import { toggleDark } from './theme';
import { message, problem, success } from './toasts';
import { addLinks } from './transfers-actions';

// The window's content: the sidebar of places, the top bar with the screen's title, the field to search or paste links,
// the rates and the networks, the current screen, the status strip, and messages floating over it all.

const props = defineProps<{ platform: Platform }>();

// Below this width of the window the sidebar shows only its icons and the top bar keeps to its essentials.
const FOLD_BELOW = 1000;
const SIDEBAR_KEY = 'njmule.sidebar.collapsed';

const width = ref(window.innerWidth);
const narrow = computed(() => width.value < FOLD_BELOW);
const userCollapsed = ref(readCollapsed());
const collapsed = computed(() => narrow.value || userCollapsed.value);
const paletteOpen = ref(false);
const paletteText = ref('');
const shortcutsOpen = ref(false);

const words = computed(() => networkWords(connection.value, status.value, serverName.value));
const title = computed(() => t(PLACES.find((p) => p.id === current.value)?.title ?? ''));
const summary = computed(() => {
    if (current.value === 'transfers') {
        return running.value === 0 && (status.value?.downloads ?? 0) === 0
            ? ''
            : t('nj.summary.transfers', running.value, rate(status.value?.downloadBytesPerSecond ?? 0));
    }
    if (current.value === 'search') {
        return searches.value.length === 0
            ? ''
            : t('nj.summary.search', searches.value.length, searches.value.filter((s) => s.running).length);
    }
    if (current.value === 'messages') {
        return chats.value.length === 0 && friends.value.length === 0
            ? ''
            : t('nj.summary.messages', chats.value.length, friends.value.length);
    }
    if (current.value === 'shared') {
        return shared.value.length === 0 ? '' : t('nj.summary.shared', shared.value.length, uploads.value.length);
    }
    if (current.value === 'settings') {
        return t('nj.settings.subtitle');
    }
    if (current.value === 'statistics') {
        const s = status.value;
        return s === null
            ? ''
            : t('nj.summary.statistics', duration(s.sessionSeconds), size(s.downloaded), size(s.uploaded));
    }
    return current.value === 'network' ? `${words.value.ed2k} · ${words.value.kad}` : '';
});

function readCollapsed(): boolean {
    try {
        return localStorage.getItem(SIDEBAR_KEY) === 'true';
    } catch {
        return false;
    }
}

/** Folds or opens the sidebar, remembered while the window is wide. */
function toggleSidebar(): void {
    if (narrow.value) {
        return;
    }
    userCollapsed.value = !userCollapsed.value;
    try {
        localStorage.setItem(SIDEBAR_KEY, String(userCollapsed.value));
    } catch {
        // Folded until the window closes.
    }
}

function showPalette(typed: string): void {
    paletteText.value = typed;
    paletteOpen.value = true;
}

// Ctrl+1 ... Ctrl+7 (Cmd on macOS) open the places in sidebar order, Ctrl+B folds the sidebar, Ctrl+K opens the
// palette; F1 lists the shortcuts.
function key(e: KeyboardEvent): void {
    const command = e.ctrlKey || e.metaKey;
    const digit = Number(e.key);
    if (command && digit >= 1 && digit <= PLACES.length) {
        current.value = PLACES[digit - 1]!.id;
    } else if (e.key === 'F1') {
        shortcutsOpen.value = true;
    } else if (command && e.key.toLowerCase() === 'k') {
        showPalette('');
    } else if (command && e.key.toLowerCase() === 'b') {
        toggleSidebar();
    } else {
        return;
    }
    e.preventDefault();
}

function resize(): void {
    width.value = window.innerWidth;
}

// eD2K links dragged from a page or a text onto the window become downloads.
function dragOver(e: DragEvent): void {
    if (e.dataTransfer?.types.includes('text/plain') || e.dataTransfer?.types.includes('text/uri-list')) {
        e.preventDefault();
    }
}

function drop(e: DragEvent): void {
    const text = e.dataTransfer?.getData('text/plain') || e.dataTransfer?.getData('text/uri-list') || '';
    if (fileLinks(text).length > 0) {
        e.preventDefault();
        void addLinks(text);
    }
}

// The banner's way out of being offline: what Connect does then.
function connectIfOff(): void {
    if (connection.value === 'disconnected') {
        void both();
    }
}

// Connect and Disconnect act on both networks, as eD2K's state says which; Cancel only stops trying servers.
async function both(): Promise<void> {
    try {
        if (connection.value === 'disconnected') {
            await answer(api().POST('/servers/connect', { body: {} }));
            if (!words.value.kadRuns && preferences.value['net.kad.enabled'] !== 'false') {
                await answer(api().POST('/kad/connect'));
            }
        } else if (connection.value === 'connecting') {
            await answer(api().POST('/servers/disconnect'));
        } else {
            await answer(api().POST('/servers/disconnect'));
            if (words.value.kadRuns) {
                await answer(api().POST('/kad/disconnect'));
            }
        }
    } catch (e) {
        problem(String((e as Error).message));
    }
}

async function ed2k(): Promise<void> {
    try {
        await answer(
            connection.value === 'disconnected'
                ? api().POST('/servers/connect', { body: {} })
                : api().POST('/servers/disconnect'),
        );
    } catch (e) {
        problem(String((e as Error).message));
    }
}

async function kad(): Promise<void> {
    try {
        await answer(words.value.kadRuns ? api().POST('/kad/disconnect') : api().POST('/kad/connect'));
    } catch (e) {
        problem(String((e as Error).message));
    }
}

// The folder of finished downloads in the system's file manager; the engine's default one is not told.
async function openIncoming(): Promise<void> {
    const folder = preferences.value['dirs.incoming'];
    if (folder) {
        try {
            await props.platform.showFolder(folder);
        } catch (e) {
            problem((e as Error).message);
        }
    }
}

// The folders open where they are: in the desktop app's window on the engine's own computer.
const canOpenFolders = computed(() => props.platform.desktop && engineHere(endpoint().url));

// What the palette offers: the places, then the commands of the bar and the menus; those whose dialog this window has
// not yet show disabled.
const commands = computed<Command[]>(() => [
    ...PLACES.map((p, i) => ({ title: t(p.title), keys: shortcut(i), run: () => (current.value = p.id) })),
    // What eD2K's state calls for, as on the networks' button for both.
    {
        title: t(
            connection.value === 'connected'
                ? 'IDS_MAIN_BTN_DISCONNECT'
                : connection.value === 'connecting'
                  ? 'IDS_MAIN_BTN_CANCEL'
                  : 'IDS_MAIN_BTN_CONNECT',
        ),
        keys: '',
        run: () => void both(),
    },
    { title: t('IDS_OPENINC'), keys: '', run: () => void openIncoming(), disabled: !canOpenFolders.value },
    { title: t('nj.wizard.title'), keys: '', run: () => {}, disabled: true },
    { title: t('nj.import.title'), keys: '', run: () => {}, disabled: true },
    { title: t('nj.top.theme'), keys: '', run: toggleDark },
    {
        title: t(collapsed.value ? 'nj.sidebar.expand' : 'nj.sidebar.collapse'),
        keys: withShortcutKey('B'),
        run: toggleSidebar,
    },
    { title: t('nj.shortcuts.title'), keys: 'F1', run: () => (shortcutsOpen.value = true) },
    { title: t('IDS_CHECK4UPDATE'), keys: '', run: () => {}, disabled: true },
    { title: t('nj.about.title'), keys: '', run: () => {}, disabled: true },
]);

// What the text typed is itself: eD2K links to download, or else words to search the network for.
function typed(text: string): Command[] {
    const links = fileLinks(text);
    if (links.length > 0) {
        return [
            {
                title: `${t('IDS_DOWNLOAD')}: ${links.map((l) => l.name).join(', ')}`,
                keys: '',
                run: () => void addLinks(text),
            },
        ];
    }
    if (text.toLowerCase().startsWith('ed2k://')) {
        return [];
    }
    return [
        {
            title: t('nj.palette.search', text),
            keys: '',
            run: () => {
                searchRequest.value = text;
                current.value = 'search';
            },
        },
    ];
}

onMounted(() => {
    window.addEventListener('keydown', key);
    window.addEventListener('resize', resize);
    on('download.completed', (d) => success(t('nj.transfers.completed', d.download?.name ?? '')));
    on('message.received', (m) => {
        if (current.value !== 'messages') {
            message(t('nj.chat.from', m.nick || m.address || ''));
        }
    });
    // Links clicked in a browser or opened by the system come to this window.
    props.platform.onLinks((links) => void addLinks(links.join('\n')));
});

onUnmounted(() => {
    window.removeEventListener('keydown', key);
    window.removeEventListener('resize', resize);
});
</script>

<template>
    <TooltipProvider :delay-duration="700">
        <div class="njmule" :class="{ narrow }" @dragover="dragOver" @drop="drop">
            <div class="brand-line" />
            <div class="frame">
                <Sidebar
                    :collapsed="collapsed"
                    :counts="{ transfers: running, messages: unreadTotal }"
                    :network="{ up: words.up, trying: words.trying, state: `${words.ed2k}\n${words.kad}` }"
                    @toggle="toggleSidebar"
                />
                <div class="center">
                    <TopBar
                        :title="title"
                        :summary="summary"
                        :narrow="narrow"
                        :words="words"
                        :can-open-folders="canOpenFolders"
                        @palette="showPalette"
                        @ed2k="ed2k"
                        @kad="kad"
                        @both="both"
                        @shortcuts="shortcutsOpen = true"
                        @incoming="openIncoming"
                    />
                    <Banner @connect="connectIfOff" />
                    <!-- A place keeps what it shows while another is open: its filters, choice, scroll and pending options. -->
                    <main class="content">
                        <KeepAlive>
                            <TransfersScreen v-if="current === 'transfers'" :platform="platform" />
                            <SearchScreen v-else-if="current === 'search'" />
                            <SharedScreen v-else-if="current === 'shared'" :platform="platform" />
                            <MessagesScreen v-else-if="current === 'messages'" />
                            <NetworkScreen v-else-if="current === 'network'" @ed2k="ed2k" @kad="kad" />
                            <StatisticsScreen v-else-if="current === 'statistics'" />
                            <SettingsScreen v-else />
                        </KeepAlive>
                    </main>
                </div>
            </div>
            <StatusStrip />
            <Toasts />
            <CommandPalette v-model:open="paletteOpen" v-model:text="paletteText" :commands="commands" :typed="typed" />
            <ShortcutsDialog v-model:open="shortcutsOpen" />
            <AskDialog />
        </div>
    </TooltipProvider>
</template>

<style scoped>
.njmule {
    display: flex;
    flex-direction: column;
    height: 100%;
}

/* The brand's orange along the top of the window. */
.brand-line {
    height: 2px;
    flex: none;
    background: var(--nj-brand-accent);
}

.frame {
    display: flex;
    flex: 1;
    min-height: 0;
}

.center {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
}

.content {
    flex: 1;
    min-height: 0;
    min-width: 0;
    display: flex;
    flex-direction: column;
}
</style>
