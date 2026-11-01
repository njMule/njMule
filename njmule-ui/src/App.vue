<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { TooltipProvider } from 'reka-ui';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { answer, api, endpoint } from './api/client';
import { ask } from './ask';
import { duration, rate, size } from './format';
import { language, t } from './i18n';
import { fileLinks } from './links';
import { connection, folders, on, preferences, running, serverName, shared, status, uploads } from './model/engine';
import { networkWords } from './network';
import { current, PLACES, searchRequest, shortcut, withShortcutKey } from './places';
import { searches } from './search';
import { chats, friends } from './chats';
import type { Command } from './palette';
import { engineHere, type Platform } from './platform';
import SearchScreen from './screens/SearchScreen.vue';
import MessagesScreen from './screens/MessagesScreen.vue';
import NetworkScreen from './screens/NetworkScreen.vue';
import SettingsScreen from './screens/SettingsScreen.vue';
import SharedScreen from './screens/SharedScreen.vue';
import StatisticsScreen from './screens/StatisticsScreen.vue';
import TransfersScreen from './screens/TransfersScreen.vue';
import RemoteDialog from './dialogs/RemoteDialog.vue';
import AskDialog from './shell/AskDialog.vue';
import Banner from './shell/Banner.vue';
import CommandPalette from './shell/CommandPalette.vue';
import ShortcutsDialog from './shell/ShortcutsDialog.vue';
import Sidebar from './shell/Sidebar.vue';
import StatusStrip from './shell/StatusStrip.vue';
import Toasts from './shell/Toasts.vue';
import TopBar from './shell/TopBar.vue';
import ImportDialog from './dialogs/ImportDialog.vue';
import FirstRunWizard, { type WizardOutcome } from './dialogs/FirstRunWizard.vue';
import { switchLanguage } from './language';
import { guest } from './role';
import { logOut } from './logout';
import { setupPending } from './setup';
import { handOver } from './handover';
import { setPreset } from './limits';
import { toggleDark } from './theme';
import { message, problem, success, whileHidden } from './toasts';
import { addLinks } from './transfers-actions';
import { showAbout } from './about';
import AboutDialog from './dialogs/AboutDialog.vue';
import UpdateDialog from './dialogs/UpdateDialog.vue';
import { checkNow, followUpdates } from './updates';
import { watchClipboard } from './clipboard';
import { completedCount, rates } from './model/engine';
import { readDesktopSettings, setDesktopOption } from './desktop-options';
import { glanceOf, tooltipOf } from './tray';

// The window's content: the sidebar of places, the top bar with the screen's title, the field to search or paste links,
// the rates and the networks, the current screen, the status strip, and messages floating over it all.

const props = defineProps<{ platform: Platform }>();

// Below this width of the window the sidebar shows only its icons and the top bar keeps to its essentials; higher when
// the top bar needs more to show all it holds.
const FOLD_BELOW = 1000;
const SIDEBAR_KEY = 'njmule.sidebar.collapsed';

const width = ref(window.innerWidth);
const foldBelow = ref(FOLD_BELOW);
const narrow = computed(() => width.value < foldBelow.value);
// Folded while narrow, or as the user left it once wide; open for a while in a narrow window when asked.
const collapsed = ref(narrow.value || readCollapsed());
const paletteOpen = ref(false);
const paletteText = ref('');
const shortcutsOpen = ref(false);
const importOpen = ref(false);
const wizardOpen = ref(false);
const remoteOpen = ref(false);

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
    collapsed.value = !collapsed.value;
    if (!narrow.value) {
        try {
            localStorage.setItem(SIDEBAR_KEY, String(collapsed.value));
        } catch {
            // Folded until the window closes.
        }
    }
}

// The messages that came while the messages did not show, on their place until they show.
const unread = ref(0);
watch(current, (place) => {
    if (place === 'messages') {
        unread.value = 0;
    }
});

// The window turned narrow or wide: the sidebar folds, or opens again as the user left it.
watch(narrow, (now) => (collapsed.value = now || readCollapsed()));

// A top bar that cannot show all it holds turns the window narrow from this width up: it folds below the width its wide
// layout needs.
function crowded(): void {
    if (!narrow.value) {
        foldBelow.value = width.value + 1;
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
    // The digit keys themselves, whatever the layout writes on them (& on a French keyboard).
    const digit = Number(/^Digit(\d)$/.exec(e.code)?.[1] ?? 0);
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

// eD2K links dragged from a page or a text onto the window become downloads. What is dragged can be read only once
// dropped, so any text is let in; a field takes it as fields do.
function dragOver(e: DragEvent): void {
    if (e.dataTransfer?.types.includes('text/plain') || e.dataTransfer?.types.includes('text/uri-list')) {
        e.preventDefault();
    }
}

function drop(e: DragEvent): void {
    if (e.target instanceof Element && e.target.closest('input, textarea, [contenteditable="true"]')) {
        return;
    }
    // Never the page's own way with a link dropped, which would leave njMule for that address.
    e.preventDefault();
    const text = e.dataTransfer?.getData('text/plain') || e.dataTransfer?.getData('text/uri-list') || '';
    if (!guest && fileLinks(text).length > 0) {
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

// The folder of finished downloads in the system's file manager, the engine's default one included.
async function openIncoming(): Promise<void> {
    const folder = folders.value.incoming;
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

// What the palette offers: the places, then the commands of the bar and the menus; those this window cannot run, or a
// guest may not, show disabled. A browser's session can end.
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
        disabled: guest,
    },
    { title: t('IDS_OPENINC'), keys: '', run: () => void openIncoming(), disabled: !canOpenFolders.value },
    { title: t('nj.wizard.title'), keys: '', run: () => (wizardOpen.value = true), disabled: guest },
    { title: t('nj.import.title'), keys: '', run: () => (importOpen.value = true), disabled: guest },
    { title: t('nj.top.theme'), keys: '', run: toggleDark },
    {
        title: t(collapsed.value ? 'nj.sidebar.expand' : 'nj.sidebar.collapse'),
        keys: withShortcutKey('B'),
        run: toggleSidebar,
    },
    { title: t('nj.shortcuts.title'), keys: 'F1', run: () => (shortcutsOpen.value = true) },
    { title: t('IDS_CHECK4UPDATE'), keys: '', run: checkNow, disabled: guest },
    { title: t('nj.about.title'), keys: '', run: showAbout },
    ...(props.platform.desktop ? [] : [{ title: t('IDS_WEB_LOGOUT'), keys: '', run: logOut }]),
]);

// What the text typed is itself: eD2K links to download, or else words to search the network for; nothing for a guest,
// who may do neither.
function typed(text: string): Command[] {
    if (guest) {
        return [];
    }
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

// Only the lists and the fields have a menu of their own: in the desktop app's window a right click elsewhere opens
// none, rather than the webview's (Back, Reload, Print...).
function noWebviewMenu(e: MouseEvent): void {
    if (!(e.target instanceof Element && e.target.closest('input, textarea, [contenteditable="true"]'))) {
        e.preventDefault();
    }
}

// The sound of a notification of an event, when the options ask for one; not for the replies to the user. The file is
// on the engine's computer, so only a window on that computer plays it.
function chime(): void {
    const file = preferences.value['notify.soundFile'] ?? '';
    if (preferences.value['notify.sound'] === 'true' && file !== '' && canOpenFolders.value) {
        props.platform.playSound(file);
    }
}

// Closing the window ends njMule, asked first: the shell leaves it to the window only while the options ask for it.
async function quitIfWanted(): Promise<void> {
    const answer = await ask({
        title: t('nj.exit.title'),
        text: t('nj.exit.text'),
        check: { label: t('IDS_DONOTASKAGAIN'), value: false },
        yes: t('nj.exit.quit'),
        no: t('nj.exit.stay'),
    });
    if (answer === null) {
        return;
    }
    if (answer.check) {
        await setDesktopOption(props.platform, 'confirmExit', false).catch(() => {
            // Asked again next time.
        });
    }
    props.platform.quit();
}

// The tray's icon, its menu and MiniMule tell njMule's state and rates, the downloads completed and the free space.
watch(
    [connection, status, () => words.value.up, completedCount, rates],
    () => {
        const glance = glanceOf(connection.value, words.value.up, status.value, completedCount.value, rates.value);
        props.platform.trayState({ tooltip: tooltipOf(glance, t, rate), glance });
    },
    { immediate: true },
);

// After the questions of the first start: the language chosen, at once, then the import of the eMule found if chosen.
async function wizardDone(outcome: WizardOutcome): Promise<void> {
    if (outcome.language !== undefined) {
        await switchLanguage(outcome.language);
    }
    if (outcome.importEmule) {
        importOpen.value = true;
    }
}

onMounted(() => {
    window.addEventListener('keydown', key);
    window.addEventListener('resize', resize);
    if (props.platform.desktop) {
        document.addEventListener('contextmenu', noWebviewMenu);
    }
    on('download.completed', (d) => {
        success(t('nj.transfers.completed', d.download?.name ?? ''));
        chime();
    });
    on('message.received', (m) => {
        if (current.value !== 'messages') {
            unread.value++;
            message(t('nj.chat.from', m.nick || m.address || ''));
            chime();
        }
    });
    // Links clicked in a browser or opened by the system come to this window: files, servers and lists.
    props.platform.onLinks((links) => void handOver(links));
    // New versions told, and in the desktop app's window the end of njMule when one installs now.
    followUpdates(props.platform);
    // While the window hides in the notification area, its messages show as the system's notifications.
    whileHidden((text) => props.platform.notify(text));
    void readDesktopSettings(props.platform);
    watchClipboard(props.platform);
    props.platform.onCloseAsked(() => void quitIfWanted());
    // The application menu of macOS: About, Settings, and Quit asking first as closing the window does.
    props.platform.onAppMenu(
        { about: t('nj.about.title'), settings: `${t('IDS_EM_PREFS')}…`, quit: t('nj.exit.quit') },
        (chosen) => {
            if (chosen === 'about') {
                showAbout();
            } else if (chosen === 'settings') {
                current.value = 'settings';
            } else {
                void quitIfWanted();
            }
        },
    );
    props.platform.onTray((chosen) => {
        if (chosen === 'connect') {
            void ed2k();
        } else if (chosen === 'options') {
            current.value = 'settings';
        } else if (chosen === 'quit') {
            void quitIfWanted();
        } else if (chosen === 'incoming') {
            void openIncoming();
        } else {
            setPreset(chosen === 'full-speed').catch((e: Error) => problem(e.message));
        }
    });
    // At the first start a few questions set njMule up; never for a guest, who may change nothing.
    if (!guest) {
        void setupPending().then((pending) => (wizardOpen.value ||= pending));
    }
});

onUnmounted(() => {
    window.removeEventListener('keydown', key);
    window.removeEventListener('resize', resize);
    document.removeEventListener('contextmenu', noWebviewMenu);
});
</script>

<template>
    <TooltipProvider :delay-duration="700">
        <!-- Another language draws the window again: the words worked out once, such as the columns', too. -->
        <div :key="language" class="njmule" :class="{ narrow }" @dragover="dragOver" @drop="drop">
            <div class="brand-line" />
            <div class="frame">
                <Sidebar
                    :collapsed="collapsed"
                    :counts="{ transfers: running, messages: unread }"
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
                        :desktop="platform.desktop"
                        @palette="showPalette"
                        @ed2k="ed2k"
                        @kad="kad"
                        @both="both"
                        @shortcuts="shortcutsOpen = true"
                        @incoming="openIncoming"
                        @remote="remoteOpen = true"
                        @crowded="crowded"
                        @import="importOpen = true"
                        @wizard="wizardOpen = true"
                        @logout="logOut"
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
            <ImportDialog v-model:open="importOpen" :platform="platform" />
            <FirstRunWizard v-model:open="wizardOpen" :platform="platform" @done="wizardDone" />
            <AskDialog />
            <AboutDialog />
            <UpdateDialog :platform="platform" :can-open-folders="canOpenFolders" />
            <RemoteDialog v-model:open="remoteOpen" :platform="platform" />
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
