<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import { count } from '../../format';
import { t } from '../../i18n';
import { buddyState, distanceBars, udpState } from '../../kad';
import { status } from '../../model/engine';
import { message, problem, success } from '../../toasts';
import DataTable from '../../ui/DataTable.vue';
import Icon from '../../ui/Icon.vue';

// Kad: its state in plain words with what to do, the contacts by distance and the facts, the ways to join it, and
// the contacts and the lookups running, read every few seconds while shown.

type Contact = Schemas['KadContact'];
type Lookup = Schemas['KadSearch'];

const contacts = shallowRef<Contact[]>([]);
const lookups = shallowRef<Lookup[]>([]);
const tab = ref<'contacts' | 'searches'>('contacts');
const joinOpen = ref(false);
const nodesUrl = ref('https://upd.emule-security.org/nodes.dat');
const node = ref('');
const selectedContacts = ref<string[]>([]);
const selectedLookups = ref<string[]>([]);

const kad = computed(() => status.value?.kad ?? null);
const bars = computed(() => distanceBars(contacts.value));
const tallest = computed(() => Math.max(1, ...bars.value));

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

const KINDS: Record<Lookup['kind'], string> = {
    node: 'IDS_KAD_NODE',
    keyword: 'IDS_KAD_SEARCHKW',
    sources: 'IDS_KAD_SEARCHSRC',
    notes: 'IDS_NOTES',
    store_keyword: 'IDS_KAD_STOREKW',
    store_source: 'IDS_KAD_STOREFILE',
    store_notes: 'IDS_STORENOTES',
};

const contactColumns: ColumnDef<any, Contact, any>[] = [
    { id: 'id', header: t('IDS_ID'), accessorFn: (c: Contact) => c.id, size: 260 },
    { id: 'type', header: t('IDS_TYPE'), accessorFn: (c: Contact) => c.type, size: 60 },
    { id: 'distance', header: t('IDS_KADDISTANCE'), accessorFn: (c: Contact) => c.distance, size: 260 },
    { id: 'address', header: t('IDS_IP'), accessorFn: (c: Contact) => c.address, size: 160 },
    { id: 'version', header: 'Kad', accessorFn: (c: Contact) => c.version, size: 50 },
];

const lookupColumns: ColumnDef<any, Lookup, any>[] = [
    { id: 'target', header: t('IDS_KADID'), accessorFn: (l: Lookup) => l.target, size: 260 },
    { id: 'kind', header: t('IDS_TYPE'), accessorFn: (l: Lookup) => t(KINDS[l.kind]), size: 140 },
    { id: 'answers', header: t('nj.kad.answers'), accessorFn: (l: Lookup) => l.answers, size: 80 },
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
    message(t('IDS_DOWNLOADING_NODESDAT_FROM', url));
    try {
        const { added } = await answer(api().POST('/kad/nodes', { body: { url } }));
        success(t('nj.kad.fromUrl', added, url));
    } catch (e) {
        problem((e as Error).message);
    }
}

async function bootstrap(): Promise<void> {
    const where = node.value.trim();
    if (!/^[^\s:]+:\d{1,5}$/.test(where)) {
        message(t('nj.servers.badAddress', where));
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
                    <span class="muted">{{ t('IDS_KADCONTACTLAB') }}: {{ count(contacts.length) }}</span>
                </div>
                <div class="bars" role="img" :aria-label="t('nj.kad.byDistance')">
                    <span
                        v-for="(n, i) in bars"
                        :key="i"
                        class="distance-bar"
                        :class="{ empty: n === 0 }"
                        :style="{ height: n === 0 ? '1px' : `${Math.max(4, (n * 100) / tallest)}%` }"
                        :title="String(n)"
                    />
                </div>
                <div class="chart-axis muted">
                    <span>{{ t('nj.kad.close') }}</span
                    ><span>{{ t('nj.kad.far') }}</span>
                </div>
            </section>
            <dl class="card facts">
                <template v-for="[name, value] in facts" :key="name">
                    <dt class="muted">{{ name }}</dt>
                    <dd>{{ value }}</dd>
                </template>
            </dl>
        </div>
        <details class="join" :open="joinOpen" @toggle="joinOpen = ($event.target as HTMLDetailsElement).open">
            <summary><Icon name="CHEVRON_RIGHT" class="chevron" />{{ t('nj.kad.join') }}</summary>
            <p class="muted">{{ t('nj.kad.joinHint') }}</p>
            <label class="field-label" for="nodes-url">{{ t('IDS_BOOTSRAPNODESDAT') }}</label>
            <div class="row">
                <input id="nodes-url" v-model="nodesUrl" class="field" dir="ltr" />
                <button class="button" @click="download"><Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}</button>
            </div>
            <label class="field-label" for="kad-node">{{ t('nj.kad.fromClient') }}</label>
            <div class="row">
                <input id="kad-node" v-model="node" class="field" dir="ltr" :placeholder="t('nj.kad.nodePrompt')" />
                <button class="button" @click="bootstrap">{{ t('IDS_BOOTSTRAP') }}</button>
            </div>
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
            :columns-label="t('IDS_ID')"
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
            :columns-label="t('IDS_KADID')"
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

.say.success {
    background: var(--color-success-subtle);
    color: var(--color-success-fg);
    --nj-icon-color: var(--color-success-fg);
}

.say.warning {
    background: var(--color-warning-subtle);
    color: var(--color-warning-fg);
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
    gap: 4px;
    height: 72px;
    direction: ltr;
}

/* The Kad contacts by distance: bars in the accent of the progress bars, an empty distance as a hairline. */
.distance-bar {
    flex: 1;
    border-radius: 2px 2px 0 0;
    background: var(--nj-bar-sources-max);
}

.distance-bar.empty {
    background: var(--color-border-muted);
}

.chart-axis {
    display: flex;
    justify-content: space-between;
    font-size: 0.92em;
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
    direction: ltr;
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

.field-label {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.row {
    display: flex;
    gap: 8px;
}

.row .field {
    flex: 1;
    min-width: 0;
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
