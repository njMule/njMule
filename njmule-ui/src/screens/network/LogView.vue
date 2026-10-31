<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import { count } from '../../format';
import { language, t } from '../../i18n';
import { addressNumber, buddyState, udpState } from '../../kad';
import { connection, type Connection, serverNotes, status } from '../../model/engine';
import { inDesktopShell } from '../../platform';
import FilterChips from '../../ui/FilterChips.vue';
import Icon from '../../ui/Icon.vue';

// The log of this run narrowed by level, what the servers said, and what we are on the networks; the lines can be
// selected and copied.

type Entry = Schemas['LogEntry'];
type Level = 'all' | 'error' | 'warning' | 'info';

const lines = shallowRef<Entry[]>([]);
const level = ref<Level>('all');
const tab = ref<'log' | 'server' | 'mine'>('log');
// Lines before this one are not shown since Reset.
const from = ref(0);
let after = 0;

const shown = computed(() => lines.value.filter((l) => l.sequence > from.value));

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

function when(time: string): string {
    return new Date(time).toLocaleString(language.value, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
    });
}

// What we are on the networks, in sections side by side; the web interface's settings are not in the API, so its
// section shows only in a browser, which it serves.
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
    if (!inDesktopShell()) {
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

// The log is read while it shows: from its part's opening or the network's coming back, until either goes.
let timer = 0;
function follow(): void {
    window.clearInterval(timer);
    void read();
    timer = window.setInterval(() => void read(), 2000);
}
onMounted(follow);
onActivated(follow);
onDeactivated(() => window.clearInterval(timer));
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <div class="log">
        <div class="tool-row" :class="{ unused: tab !== 'log' }">
            <FilterChips v-model="level" :items="chips" :label="t('IDS_SV_LOG')" />
            <span class="spacer" />
            <button class="button flat" @click="from = lines.at(-1)?.sequence ?? 0">
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
        <div v-if="tab === 'log'" class="lines" tabindex="0">
            <div v-for="l in shown.filter((e) => keeps(e, level))" :key="l.sequence" class="line" :class="l.level">
                <span class="line-icon"><Icon v-if="ICONS[l.level]" :name="ICONS[l.level]!" /></span>
                <span dir="ltr">{{ when(l.time) }}: </span><span dir="auto">{{ l.message }}</span>
            </div>
        </div>
        <div v-else-if="tab === 'server'" class="lines" tabindex="0">
            <div v-for="(n, i) in serverNotes" :key="i" class="line">
                <span class="line-icon" />
                <span dir="ltr">{{ when(n.time) }}: </span><span dir="auto">{{ n.server }}: {{ n.text }}</span>
            </div>
        </div>
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

/* Registers can be selected and copied. */
.lines {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    user-select: text;
}

.line {
    display: flex;
    gap: 4px;
    padding: 1px 0;
}

.line-icon {
    width: 22px;
    flex: none;
    display: inline-flex;
    align-items: center;
    --nj-icon-size: 14px;
}

.line.debug {
    color: var(--color-fg-muted);
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
    user-select: text;
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

/* The levels and Reset keep their room under the other tabs, which they do not act on. */
.tool-row.unused {
    visibility: hidden;
}
</style>
