<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ContextMenuContent, ContextMenuItem, ContextMenuPortal, ContextMenuRoot, ContextMenuTrigger } from 'reka-ui';
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import { count } from '../../format';
import { t } from '../../i18n';
import { addressNumber, buddyState, udpState } from '../../kad';
import { connection, type Connection, type ServerNote, serverNotes, status } from '../../model/engine';
import { logFrom, logTime, notesFrom } from '../../network';
import { logTabRequest } from '../../places';
import { inDesktopShell } from '../../platform';
import { usable, type WebSettings } from '../settings/webui';
import { problem } from '../../toasts';
import FilterChips from '../../ui/FilterChips.vue';
import Icon from '../../ui/Icon.vue';

// The log of this run narrowed by level, what the servers said, and what we are on the networks. Both logs read left to
// right in every language, follow new lines unless some text is chosen, and are chosen with the mouse and copied.

type Entry = Schemas['LogEntry'];
type Level = 'all' | 'error' | 'warning' | 'info';

const lines = shallowRef<Entry[]>([]);
const level = ref<Level>('all');
const tab = ref<'log' | 'server' | 'mine'>('log');
// The status strip's last line opens the log at its own tab, whichever was shown.
watch(logTabRequest, () => (tab.value = 'log'));
// njMule and its version, at the top of what the servers said until that is reset.
const title = ref('');
let after = 0;

const shown = computed(() => lines.value.filter((l) => l.sequence > logFrom.value));
const kept = computed(() => shown.value.filter((e) => keeps(e, level.value)));
const notes = computed(() => serverNotes.value.filter((n) => n.sequence > notesFrom.value));

// Successes and the details go with the news.
function keeps(l: Entry, which: Level): boolean {
    switch (which) {
        case 'all':
            return true;
        case 'info':
            return l.level === 'info' || l.level === 'success' || l.level === 'debug';
        default:
            return l.level === which;
    }
}

const LEVELS: { value: Level; key: string }[] = [
    { value: 'all', key: 'nj.all' },
    { value: 'error', key: 'IDS_ERROR' },
    { value: 'warning', key: 'IDS_WARNING' },
    { value: 'info', key: 'IDS_INFO' },
];

const chips = computed(() =>
    LEVELS.map((l) => ({
        value: l.value,
        title: t(l.key),
        count: shown.value.filter((e) => keeps(e, l.value)).length,
    })),
);

const ICONS: Partial<Record<Entry['level'], 'ERROR' | 'WARNING' | 'CHECK_CIRCLE'>> = {
    error: 'ERROR',
    warning: 'WARNING',
    success: 'CHECK_CIRCLE',
};

// A session's start on a server, after its date and time.
function sessionLine(n: ServerNote): string {
    return `${logTime(n.time)}: ${
        n.kind === 'obfuscated' ? t('IDS_CONNECTEDTOOBFUSCATED', n.text) : t('IDS_CONNECTEDTO', n.text)
    }`;
}

// Reset empties the log, or what the servers said; My Info has nothing to reset.
function reset(): void {
    if (tab.value === 'server') {
        notesFrom.value = serverNotes.value.at(-1)?.sequence ?? notesFrom.value;
        title.value = '';
    } else {
        logFrom.value = lines.value.at(-1)?.sequence ?? logFrom.value;
    }
}

// The log or the server information shown, whichever tab is chosen.
const area = ref<HTMLElement | null>(null);
// The text chosen as the menu opened, which Copy takes.
const chosenText = ref('');

function selectionIn(element: HTMLElement): Selection | null {
    const selection = window.getSelection();
    return selection !== null &&
        !selection.isCollapsed &&
        selection.rangeCount > 0 &&
        element.contains(selection.getRangeAt(0).commonAncestorContainer)
        ? selection
        : null;
}

// New lines show at the bottom, unless some text is chosen.
async function follow(): Promise<void> {
    await nextTick();
    const element = area.value;
    if (element !== null && selectionIn(element) === null) {
        element.scrollTop = element.scrollHeight;
    }
}

watch([kept, notes, tab], () => void follow());

function menuOpening(): void {
    const selection = area.value === null ? null : selectionIn(area.value);
    chosenText.value = selection === null ? '' : selection.toString();
}

async function copy(): Promise<void> {
    try {
        await navigator.clipboard.writeText(chosenText.value);
    } catch (e) {
        problem((e as Error).message);
    }
}

function selectAll(): void {
    const element = area.value;
    const selection = window.getSelection();
    if (element === null || selection === null) {
        return;
    }
    const range = document.createRange();
    range.selectNodeContents(element);
    selection.removeAllRanges();
    selection.addRange(range);
}

// Ctrl+A chooses the lines of the log, not the whole window.
function key(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
        selectAll();
        e.preventDefault();
    }
}

// What we are on the networks, in sections side by side, and the web interface as its settings say; a guest, who may
// not read them, sees the one serving it.
interface Section {
    title: string;
    rows: [string, string][];
}

const mine = computed<Section[]>(() => {
    const s = status.value;
    const state = `${t('IDS_STATUS')}:`;
    const ipPort = `${t('IDS_IP')}:${t('IDS_PORT')}:`;
    const ed2k: Section = {
        title: `eD2K ${t('IDS_NETWORK')}`,
        rows: [[state, t(CONNECTION[connection.value])]],
    };
    const sections = [ed2k];
    if (connection.value === 'connected' && s?.server) {
        ed2k.rows.push(
            [ipPort, s.publicIp ? `${s.publicIp}:${s.tcpPort}` : t('IDS_UNKNOWN')],
            [`${t('IDS_ID')}:`, String(s.clientId)],
            ['', t(s.lowId ? 'IDS_IDLOW' : 'IDS_IDHIGH')],
        );
        sections.push({
            title: `eD2K ${t('IDS_SERVER')}`,
            rows: [
                [`${t('IDS_SW_NAME')}:`, s.server.name],
                [`${t('IDS_DESCRIPTION')}:`, s.server.description],
                [ipPort, s.server.address],
                [`${t('IDS_VERSION')}:`, s.server.version],
                [`${t('IDS_UUSERS')}:`, count(s.server.users)],
                [`${t('IDS_PW_FILES')}:`, count(s.server.files)],
                [`${t('IDS_CONNECTIONS')}:`, t(s.obfuscated ? 'IDS_OBFUSCATED' : 'IDS_PRIONORMAL')],
            ],
        });
    }
    const k = s?.kad;
    const kad: Section = { title: `${t('IDS_KADEMLIA')} ${t('IDS_NETWORK')}`, rows: [] };
    if (k?.connected) {
        kad.rows.push(
            [state, t(k.firewalled ? 'IDS_FIREWALLED' : 'IDS_KADOPEN')],
            [`UDP ${state}`, udpState(k)],
            [`${t('IDS_BUDDY')}:`, buddyState(k)],
            [ipPort, k.publicIp ? `${k.publicIp}:${k.udpPort}` : t('IDS_UNKNOWN')],
            [`${t('IDS_ID')}:`, k.publicIp ? addressNumber(k.publicIp) : ''],
        );
    } else {
        kad.rows.push([state, t(k ? 'IDS_CONNECTING' : 'IDS_DISCONNECTED')]);
    }
    sections.push(kad);
    const w = web.value;
    if (w !== undefined) {
        const rows: [string, string][] = [[state, t(usable(w) ? 'IDS_ENABLED' : 'IDS_DISABLED')]];
        if (w.url) {
            rows.push(['URL:', w.url]);
        }
        sections.push({ title: t('IDS_WEBSRV'), rows });
    } else if (!inDesktopShell()) {
        sections.push({
            title: t('IDS_WEBSRV'),
            rows: [
                [state, t('IDS_ENABLED')],
                ['URL:', `${window.location.origin}/`],
            ],
        });
    }
    return sections;
});

const CONNECTION: Record<Connection, string> = {
    connected: 'IDS_CONNECTED',
    connecting: 'IDS_CONNECTING',
    disconnected: 'IDS_DISCONNECTED',
};

async function read(): Promise<void> {
    try {
        const more = await answer(api().GET('/log', { params: { query: { after } } }));
        if (more.length > 0) {
            lines.value = [...lines.value, ...more].slice(-2000);
            after = more.at(-1)!.sequence;
        }
    } catch {
        // Kept as they were.
    }
}

// The web interface's settings, which change in the Options: read again as the log shows.
const web = shallowRef<WebSettings | undefined>();

async function readWeb(): Promise<void> {
    try {
        web.value = await answer(api().GET('/webui'));
    } catch {
        web.value = undefined;
    }
}

async function readTitle(): Promise<void> {
    try {
        const info = await answer(api().GET('/'));
        title.value = notesFrom.value === 0 ? `${info.name} ${info.version}` : '';
    } catch {
        // No title.
    }
}

// The log is read while it shows: from its part's opening or the network's coming back, until either goes.
let timer = 0;
function start(): void {
    void readWeb();
    window.clearInterval(timer);
    void read();
    timer = window.setInterval(() => void read(), 2000);
    void follow();
}
onMounted(() => {
    void readTitle();
    start();
});
onActivated(start);
onDeactivated(() => window.clearInterval(timer));
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <div class="log">
        <div class="tool-row">
            <FilterChips v-model="level" :class="{ unused: tab !== 'log' }" :items="chips" :label="t('IDS_SV_LOG')" />
            <span class="spacer" />
            <button class="button flat" :class="{ unused: tab === 'mine' }" @click="reset">
                <Icon name="CLEAR_ALL" />{{ t('IDS_PW_RESET') }}
            </button>
        </div>
        <div class="tabs" role="tablist">
            <button class="tab" role="tab" :aria-selected="tab === 'log'" @click="tab = 'log'">
                {{ t('IDS_SV_LOG') }}
            </button>
            <button class="tab" role="tab" :aria-selected="tab === 'server'" @click="tab = 'server'">
                {{ t('IDS_SV_SERVERINFO') }}
            </button>
            <button class="tab" role="tab" :aria-selected="tab === 'mine'" @click="tab = 'mine'">
                {{ t('IDS_MYINFO') }}
            </button>
        </div>
        <ContextMenuRoot v-if="tab !== 'mine'">
            <ContextMenuTrigger as-child>
                <div
                    v-if="tab === 'log'"
                    ref="area"
                    class="lines focusable"
                    dir="ltr"
                    tabindex="0"
                    @keydown="key"
                    @contextmenu="menuOpening"
                >
                    <div v-for="l in kept" :key="l.sequence" class="line" :class="l.level">
                        <span class="line-icon"><Icon v-if="ICONS[l.level]" :name="ICONS[l.level]!" /></span>
                        <span class="text"
                            >{{ `${logTime(l.time)}: ` }}<bdi>{{ l.message }}</bdi></span
                        >
                    </div>
                </div>
                <div
                    v-else
                    ref="area"
                    class="lines focusable"
                    dir="ltr"
                    tabindex="0"
                    @keydown="key"
                    @contextmenu="menuOpening"
                >
                    <div v-if="title" class="line">
                        <span class="text">{{ title }}</span>
                    </div>
                    <template v-for="n in notes" :key="n.sequence">
                        <div v-if="n.kind === 'message'" class="line">
                            <bdi class="text">{{ n.text }}</bdi>
                        </div>
                        <template v-else>
                            <!-- An empty line before each session. -->
                            <div class="line"><span class="text">&nbsp;</span></div>
                            <div class="line success">
                                <span class="text">{{ sessionLine(n) }}</span>
                            </div>
                        </template>
                    </template>
                </div>
            </ContextMenuTrigger>
            <ContextMenuPortal>
                <ContextMenuContent class="menu">
                    <ContextMenuItem class="menu-item" :disabled="chosenText === ''" @select="copy">
                        <Icon name="COPY" />{{ t('IDS_COPY') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" @select="selectAll">
                        <Icon name="SELECT_ALL" />{{ t('IDS_SELECTALL') }}
                    </ContextMenuItem>
                </ContextMenuContent>
            </ContextMenuPortal>
        </ContextMenuRoot>
        <div v-else class="mine">
            <section v-for="section in mine" :key="section.title" class="mine-section">
                <strong>{{ section.title }}</strong>
                <dl>
                    <template v-for="([name, value], i) in section.rows" :key="i">
                        <dt>{{ name }}</dt>
                        <dd dir="auto">{{ value }}</dd>
                    </template>
                </dl>
            </section>
        </div>
    </div>
</template>

<style scoped>
.log {
    display: flex;
    flex-direction: column;
    gap: 12px;
    flex: 1;
    min-height: 0;
    min-width: 0;
}

.tool-row {
    display: flex;
    align-items: flex-start;
    gap: 8px;
}

.spacer {
    flex: 1;
}

/* The levels are the log's, Reset is not My Info's: they keep their room under the other tabs. */
.unused {
    visibility: hidden;
}

.tabs {
    display: flex;
    gap: 4px;
    border-bottom: 1px solid var(--color-border-default);
}

.tab {
    padding: 9px 14px;
    border: 1px solid var(--color-border-default);
    border-bottom: 0;
    border-radius: 6px 6px 0 0;
    background: var(--color-bg-subtle);
    cursor: pointer;
}

.tab[aria-selected='true'] {
    background: var(--color-bg-default);
    box-shadow: inset 0 2px 0 var(--nj-accent-strong);
}

/* A line a paragraph, tight; the text is chosen with the mouse, also across lines. */
.lines {
    flex: 1;
    min-height: 0;
    padding: 2px 6px;
    overflow-y: auto;
    background: var(--color-bg-default);
    font-size: 0.92em;
    user-select: text;
    overflow-wrap: anywhere;
}

.line {
    display: flex;
    padding: 1px 0;
}

/* The text of a line as it came, spaces and all. */
.text {
    white-space: pre-wrap;
}

/* Lines without an icon keep the indent of those with one. */
.line-icon {
    width: 19px;
    flex: none;
    display: inline-flex;
    align-items: center;
    --nj-icon-size: 13px;
}

.line.debug {
    color: var(--color-fg-subtle);
}

.line.success {
    color: var(--color-success-fg);
}

.line.warning {
    color: var(--color-warning-fg);
}

.line.error {
    color: var(--color-danger-fg);
}

.mine {
    display: flex;
    flex-wrap: wrap;
    align-content: start;
    gap: 12px 32px;
    padding: 8px 12px;
    overflow-y: auto;
    user-select: none;
}

.mine-section {
    display: flex;
    flex-direction: column;
    gap: 4px;
    width: 280px;
}

.mine dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 2px 10px;
    margin: 0;
}

.mine dd {
    margin: 0;
    overflow-wrap: anywhere;
}
</style>
