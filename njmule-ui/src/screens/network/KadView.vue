<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { computed, h, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue';
import { answer, api, endpoint, type Schemas } from '../../api/client';
import { count } from '../../format';
import { t } from '../../i18n';
import { buddyState, distanceBars, udpState } from '../../kad';
import { status } from '../../model/engine';
import { hostPort, rememberUrl, SUGGESTED_URLS, urlHistory, webAddress } from '../../network';
import { chooseListFile, choosesOnEngine } from '../../list-files';
import { currentPlatform, type Platform } from '../../platform';
import { message, problem } from '../../toasts';
import DataTable from '../../ui/DataTable.vue';
import Icon from '../../ui/Icon.vue';
import Tip from '../../ui/Tip.vue';

// Kad: its state in plain words with what to do, the contacts by distance and the facts, the ways to join it, and
// the contacts and the lookups running, read every few seconds while shown.

type Contact = Schemas['KadContact'];
type Lookup = Schemas['KadSearch'];

const contacts = shallowRef<Contact[]>([]);
const lookups = shallowRef<Lookup[]>([]);
const tab = ref<'contacts' | 'searches'>('contacts');
const joinOpen = ref(false);
// The lists that worked are offered again; the well-known one is filled in while none did.
const nodesHistory = ref(urlHistory('nodesDat'));
const nodesUrl = ref<string>(nodesHistory.value.length === 0 ? SUGGESTED_URLS.nodesDat : '');
const node = ref('');
const nodesValid = computed(() => webAddress(nodesUrl.value) !== null);
const nodeValid = computed(() => hostPort(node.value) !== null);
const selectedContacts = ref<string[]>([]);
const selectedLookups = ref<string[]>([]);

const kad = computed(() => status.value?.kad ?? null);
const bars = computed(() => distanceBars(contacts.value));
const tallest = computed(() => Math.max(1, ...bars.value));
const placed = computed(() => bars.value.reduce((sum, n) => sum + n, 0));
// The bars stand on the base line, as tall as their share of the fullest; an empty distance shows a hairline.
const CHART_HEIGHT = 72;
function barHeight(n: number): string {
    return n === 0 ? '1px' : `${Math.max(3, (CHART_HEIGHT * n) / tallest.value)}px`;
}

// Open while Kad is not connected, closed once it is; in between, as the user leaves it.
watch(
    () => kad.value?.connected ?? false,
    (connected) => (joinOpen.value = !connected),
    { immediate: true },
);

const say = computed(() => {
    const k = kad.value;
    if (k === null) {
        return { icon: 'CLOUD_OFF' as const, tone: '', text: t('nj.kad.sayOff') };
    }
    if (!k.connected) {
        return { icon: 'HOURGLASS' as const, tone: '', text: t('nj.kad.sayConnecting') };
    }
    return k.firewalled
        ? { icon: 'WARNING' as const, tone: 'warning', text: t('nj.kad.sayFirewalled', status.value?.tcpPort ?? 0) }
        : { icon: 'CHECK_CIRCLE' as const, tone: 'success', text: t('nj.kad.sayOpen') };
});

const facts = computed(() => {
    const k = kad.value;
    return [
        [
            t('nj.kad.state'),
            k === null
                ? t('nj.kad.off')
                : !k.connected
                  ? t('IDS_NOTCONNECTED')
                  : t(k.firewalled ? 'IDS_FIREWALLED' : 'IDS_KADOPEN'),
        ],
        [t('nj.kad.udpState'), udpState(k)],
        [t('IDS_BUDDY'), buddyState(k)],
        [t('IDS_KADCONTACTLAB'), k ? count(k.contacts) : '-'],
        [t('nj.kad.verified'), k ? count(k.verifiedContacts) : '-'],
        [t('IDS_KADID'), k?.id ?? '-'],
    ];
});

// The kinds of lookups in their order, which their column sorts by whatever the words of the language.
const KINDS: Record<Lookup['kind'], string> = {
    node: 'IDS_KAD_NODE',
    keyword: 'IDS_KAD_SEARCHKW',
    sources: 'IDS_KAD_SEARCHSRC',
    notes: 'IDS_NOTES',
    store_keyword: 'IDS_KAD_STOREKW',
    store_source: 'IDS_KAD_STOREFILE',
    store_notes: 'IDS_STORENOTES',
};

const ORDER = Object.keys(KINDS);

// Ids, addresses and figures read left to right in every language.
function leftToRight({ getValue }: any) {
    return h('span', { dir: 'ltr' }, String(getValue()));
}

const contactColumns: ColumnDef<any, Contact, any>[] = [
    { id: 'id', header: t('IDS_ID'), accessorFn: (c: Contact) => c.id, size: 260, cell: leftToRight },
    { id: 'type', header: t('IDS_TYPE'), accessorFn: (c: Contact) => c.type, size: 60, cell: leftToRight },
    {
        id: 'distance',
        header: t('IDS_KADDISTANCE'),
        accessorFn: (c: Contact) => c.distance,
        size: 260,
        cell: leftToRight,
    },
    { id: 'address', header: t('IDS_IP'), accessorFn: (c: Contact) => c.address, size: 160, cell: leftToRight },
    { id: 'version', header: 'Kad', accessorFn: (c: Contact) => c.version, size: 50, cell: leftToRight },
];

const lookupColumns: ColumnDef<any, Lookup, any>[] = [
    { id: 'target', header: t('IDS_KADID'), accessorFn: (l: Lookup) => l.target, size: 260, cell: leftToRight },
    {
        id: 'kind',
        header: t('IDS_TYPE'),
        accessorFn: (l: Lookup) => t(KINDS[l.kind]),
        size: 140,
        sortFn: (a: any, b: any) =>
            ORDER.indexOf((a.original as Lookup).kind) - ORDER.indexOf((b.original as Lookup).kind),
    },
    {
        id: 'answers',
        header: t('nj.kad.answers'),
        accessorFn: (l: Lookup) => l.answers,
        size: 80,
        cell: leftToRight,
    },
];

async function read(): Promise<void> {
    try {
        [contacts.value, lookups.value] = await Promise.all([
            answer(api().GET('/kad/contacts')),
            answer(api().GET('/kad/searches')),
        ]);
    } catch {
        // Kept as they were.
    }
}

async function download(): Promise<void> {
    const url = nodesUrl.value.trim();
    const parsed = webAddress(url);
    if (parsed === null) {
        return;
    }
    message(t('IDS_DOWNLOADING_NODESDAT_FROM', url));
    try {
        const { added } = await answer(api().POST('/kad/nodes', { body: { url } }));
        rememberUrl('nodesDat', url);
        nodesHistory.value = urlHistory('nodesDat');
        message(t('nj.kad.fromUrl', added, parsed.hostname));
    } catch (e) {
        problem((e as Error).message);
    }
}

const platform = shallowRef<Platform | undefined>();
onMounted(() => void currentPlatform().then((p) => (platform.value = p)));

// A nodes.dat of the engine's computer by its path in the desktop window beside it, else one of this computer sent.
async function addNodesDat(): Promise<void> {
    const onEngine = choosesOnEngine(platform.value, endpoint().url);
    const chosen = await chooseListFile(onEngine, platform.value, t('nj.kad.addNodesDat'), 'nodes.dat', 'dat');
    if (chosen === undefined) {
        return;
    }
    try {
        const { added } = await answer(api().POST('/kad/nodes', { body: chosen.body }));
        message(t('nj.kad.fromFile', added, chosen.name));
    } catch (e) {
        problem((e as Error).message);
    }
}

async function bootstrap(): Promise<void> {
    const where = hostPort(node.value);
    if (where === null) {
        message(t('nj.servers.badAddress', node.value.trim()));
        return;
    }
    try {
        await answer(api().POST('/kad/bootstrap', { body: { address: where } }));
        node.value = '';
    } catch (e) {
        problem((e as Error).message);
    }
}

// Kad is read while it shows: from its part's opening or the network's coming back, until either goes.
let timer = 0;
function follow(): void {
    window.clearInterval(timer);
    void read();
    timer = window.setInterval(() => void read(), 3000);
}
onMounted(follow);
onActivated(follow);
onDeactivated(() => window.clearInterval(timer));
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <div class="kad">
        <p class="say" :class="say.tone"><Icon :name="say.icon" />{{ say.text }}</p>
        <div class="cards">
            <section class="card chart">
                <div class="chart-head">
                    <strong>{{ t('nj.kad.byDistance') }}</strong>
                    <span class="muted">{{ t('IDS_KADCONTACTLAB') }}: {{ count(placed) }}</span>
                </div>
                <div
                    class="bars"
                    role="img"
                    :aria-label="`${t('nj.kad.byDistance')}, ${t('IDS_KADCONTACTLAB')}: ${count(placed)}`"
                >
                    <Tip v-for="(n, i) in bars" :key="i" :text="`${t('IDS_KADCONTACTLAB')}: ${count(n)}`">
                        <span class="distance-bar" :class="{ empty: n === 0 }" :style="{ height: barHeight(n) }" />
                    </Tip>
                </div>
                <div class="chart-axis muted">
                    <span>{{ t('nj.kad.close') }}</span
                    ><span>{{ t('nj.kad.far') }}</span>
                </div>
            </section>
            <dl class="card facts">
                <template v-for="([name, value], i) in facts" :key="name">
                    <dt class="muted">{{ name }}</dt>
                    <!-- The id reads left to right in every language. -->
                    <dd :dir="i === facts.length - 1 ? 'ltr' : undefined">{{ value }}</dd>
                </template>
            </dl>
        </div>
        <details class="join" :open="joinOpen" @toggle="joinOpen = ($event.target as HTMLDetailsElement).open">
            <summary><Icon name="CHEVRON_RIGHT" class="chevron" />{{ t('nj.kad.join') }}</summary>
            <p>{{ t('nj.kad.joinHint') }}</p>
            <div class="ways">
                <label for="nodes-url">{{ t('IDS_BOOTSRAPNODESDAT') }}</label>
                <Tip :text="t('nj.input.url')">
                    <input
                        id="nodes-url"
                        v-model="nodesUrl"
                        class="field"
                        dir="ltr"
                        list="nodes-url-history"
                        placeholder="https://…/nodes.dat"
                        :aria-invalid="nodesUrl.trim() !== '' && !nodesValid"
                        @keydown.enter="download"
                    />
                </Tip>
                <button class="button" :disabled="!nodesValid" @click="download">
                    <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                </button>
                <span />
                <button class="button from-file" @click="addNodesDat">
                    <Icon name="FOLDER_OPEN" />{{ t('nj.kad.addNodesDat') }}
                </button>
                <span />
                <label for="kad-node">{{ t('nj.kad.fromClient') }}</label>
                <Tip :text="t('nj.input.address')">
                    <input
                        id="kad-node"
                        v-model="node"
                        class="field"
                        dir="ltr"
                        :placeholder="t('nj.kad.nodePrompt')"
                        :aria-invalid="node.trim() !== '' && !nodeValid"
                        @keydown.enter="bootstrap"
                    />
                </Tip>
                <button class="button" :disabled="!nodeValid" @click="bootstrap">
                    <Icon name="IMPORT" />{{ t('IDS_BOOTSTRAP') }}
                </button>
            </div>
            <datalist id="nodes-url-history">
                <option v-for="u in nodesHistory" :key="u" :value="u" />
            </datalist>
        </details>
        <div class="tabs" role="tablist">
            <button class="tab" role="tab" :aria-selected="tab === 'contacts'" @click="tab = 'contacts'">
                {{ t('IDS_KADCONTACTLAB') }}
            </button>
            <button class="tab" role="tab" :aria-selected="tab === 'searches'" @click="tab = 'searches'">
                {{ t('IDS_KADSEARCHLAB') }}
            </button>
        </div>
        <DataTable
            v-if="tab === 'contacts'"
            v-model:selected="selectedContacts"
            class="kad-table"
            name="kadContacts"
            :rows="contacts"
            :columns="contactColumns"
            :row-id="(c: Contact) => c.id + c.address"
            :label="t('IDS_KADCONTACTLAB')"
        >
            <template #empty>
                <span class="muted">{{ t('nj.kad.off') }}</span>
            </template>
        </DataTable>
        <DataTable
            v-else
            v-model:selected="selectedLookups"
            class="kad-table"
            name="kadSearches"
            :rows="lookups"
            :columns="lookupColumns"
            :row-id="(l: Lookup) => l.target + l.kind"
            :label="t('IDS_KADSEARCHLAB')"
        >
            <template #empty>
                <span class="muted">{{ t('nj.kad.noSearches') }}</span>
            </template>
        </DataTable>
    </div>
</template>

<style scoped>
.kad {
    display: flex;
    flex-direction: column;
    gap: 18px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    overflow-y: auto;
}

.say {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0;
    padding: 8px 12px;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    --nj-icon-size: 18px;
    --nj-icon-color: var(--color-fg-muted);
}

/* On a pastel tint of its tone, which its icon takes; the text keeps the theme's colour. */
.say.success {
    background: var(--color-success-subtle);
    --nj-icon-color: var(--color-success-fg);
}

.say.warning {
    background: var(--color-warning-subtle);
    --nj-icon-color: var(--color-warning-fg);
}

.cards {
    display: flex;
    gap: 12px;
}

.card {
    padding: 12px 14px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
}

.chart {
    display: flex;
    flex-direction: column;
    gap: 8px;
    flex: 1;
    min-width: 0;
}

.chart-head {
    display: flex;
    justify-content: space-between;
}

.bars {
    display: flex;
    align-items: flex-end;
    gap: 3px;
    height: 72px;
    direction: ltr;
}

/* The Kad contacts by distance: bars in the accent of the progress bars, an empty distance as a hairline. */
.distance-bar {
    flex: 1;
    min-width: 2px;
    border-radius: 2px 2px 0 0;
    background: var(--nj-bar-sources-max);
}

.distance-bar.empty {
    background: var(--nj-bar-bg);
}

.chart-axis {
    display: flex;
    justify-content: space-between;
    font-size: 0.85em;
    direction: ltr;
}

.facts {
    display: grid;
    grid-template-columns: auto auto;
    align-content: start;
    gap: 6px 24px;
    margin: 0;
}

.facts dd {
    margin: 0;
    font-weight: bold;
}

.join {
    border: 1px solid var(--color-border-default);
}

.join[open] {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding-bottom: 12px;
}

.join summary {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 10px;
    list-style: none;
    cursor: pointer;
    font-size: 1.05em;
}

.join summary::-webkit-details-marker {
    display: none;
}

.join[open] summary .chevron {
    transform: rotate(90deg);
}

.join:not([open]) summary .chevron:dir(rtl) {
    transform: scaleX(-1);
}

.join > :not(summary) {
    margin: 0 14px;
}

/* The ways in side by side as in a form: the names, the boxes that take the rest, their buttons. */
.ways {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 8px;
}

/* Under the field it goes with, as wide as its words. */
.from-file {
    justify-self: start;
}

.join p {
    margin-block: 0;
}

.field[aria-invalid='true'] {
    border-color: var(--color-danger-emphasis);
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

.kad-table {
    min-height: 220px;
}
</style>
