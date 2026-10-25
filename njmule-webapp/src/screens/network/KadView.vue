<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInput, ElTable, ElTableColumn, ElTabPane, ElTabs } from 'element-plus';
import { computed, ref } from 'vue';
import { api } from '../../api';
import { status, usePoll } from '../../engine';
import { count } from '../../format';
import { t } from '../../i18n';
import { attempt, told } from '../../notify';
import { distanceBars } from '../../kad';

interface Contact {
    id: string;
    address: string;
    type: number;
    distance: string;
    version: number;
    verified: boolean;
}

interface Lookup {
    target: string;
    kind: 'node' | 'keyword' | 'sources' | 'notes' | 'store_keyword' | 'store_source' | 'store_notes';
    answers: number;
}

const contacts = usePoll(() => api.get<Contact[]>('/kad/contacts'), 6000, [] as Contact[]);
const lookups = usePoll(() => api.get<Lookup[]>('/kad/searches'), 3000, [] as Lookup[]);
const nodesUrl = ref('https://upd.emule-security.org/nodes.dat');
const node = ref('');

const kad = computed(() => status.value?.kad ?? null);
// Our contacts from the closest to the farthest, each bar as tall as its share of the fullest.
const bars = computed(() => distanceBars(contacts.data.value));
const fullest = computed(() => Math.max(1, ...bars.value));
const said = computed(() => {
    if (kad.value === null) {
        return t('nj.kad.sayOff');
    }
    if (!kad.value.connected) {
        return t('nj.kad.sayConnecting');
    }
    return kad.value.firewalled ? t('nj.kad.sayFirewalled', kad.value.udpPort) : t('nj.kad.sayOpen');
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

async function download(): Promise<void> {
    const url = nodesUrl.value.trim();
    told(t('IDS_DOWNLOADING_NODESDAT_FROM', url));
    await attempt(async () => {
        const { added } = await api.post<{ added: number }>('/kad/nodes', { url });
        told(t('nj.kad.fromUrl', added, url), 'success');
    });
}

async function bootstrap(): Promise<void> {
    await attempt(() => api.post('/kad/bootstrap', { address: node.value.trim() }));
}

async function toggle(): Promise<void> {
    await attempt(() => api.post(kad.value?.connected ? '/kad/disconnect' : '/kad/connect'));
}

async function recheck(): Promise<void> {
    await attempt(() => api.post('/kad/recheck'));
}
</script>

<template>
    <p class="said">{{ said }}</p>
    <div class="facts">
        <dl>
            <dt>{{ t('nj.kad.state') }}</dt>
            <dd>
                {{
                    kad === null
                        ? t('nj.kad.off')
                        : !kad.connected
                          ? t('IDS_NOTCONNECTED')
                          : kad.firewalled
                            ? t('IDS_FIREWALLED')
                            : t('IDS_KADOPEN')
                }}
            </dd>
            <template v-if="kad">
                <dt>{{ t('nj.kad.udpState') }}</dt>
                <dd>
                    {{
                        !kad.udpVerified ? t('IDS_UNKNOWN') : kad.udpFirewalled ? t('IDS_FIREWALLED') : t('IDS_KADOPEN')
                    }}
                </dd>
                <dt>{{ t('IDS_KADCONTACTLAB') }}</dt>
                <dd>{{ count(kad.contacts) }}</dd>
                <dt>{{ t('nj.kad.verified') }}</dt>
                <dd>{{ count(kad.verifiedContacts) }}</dd>
                <dt>{{ t('IDS_KADID') }}</dt>
                <dd class="id">{{ kad.id }}</dd>
            </template>
        </dl>
        <div class="buttons">
            <el-button type="primary" @click="toggle">
                {{ kad?.connected ? t('IDS_MAIN_BTN_DISCONNECT') : t('IDS_MAIN_BTN_CONNECT') }}
            </el-button>
            <el-button :disabled="!kad?.connected" @click="recheck">{{ t('IDS_KAD_RECHECKFW') }}</el-button>
        </div>
    </div>

    <section class="join">
        <h2>{{ t('nj.kad.join') }}</h2>
        <p class="hint">{{ t('nj.kad.joinHint') }}</p>
        <form class="line" @submit.prevent="download">
            <label for="nj-nodes">{{ t('IDS_BOOTSRAPNODESDAT') }}</label>
            <el-input id="nj-nodes" v-model="nodesUrl" />
            <el-button native-type="submit">{{ t('IDS_DOWNLOAD') }}</el-button>
        </form>
        <form class="line" @submit.prevent="bootstrap">
            <label for="nj-node">{{ t('nj.kad.fromClient') }}</label>
            <el-input id="nj-node" v-model="node" :placeholder="t('nj.kad.nodePrompt')" />
            <el-button native-type="submit">{{ t('IDS_BOOTSTRAP') }}</el-button>
        </form>
    </section>

    <section class="distance" :aria-label="t('nj.kad.byDistance')">
        <h3>{{ t('nj.kad.byDistance') }}</h3>
        <div class="bars">
            <div
                v-for="(n, i) in bars"
                :key="i"
                class="bar"
                :class="{ empty: n === 0 }"
                :style="{ height: `${Math.max(2, (n / fullest) * 72)}px` }"
                :title="String(n)"
            />
        </div>
    </section>

    <el-tabs>
        <el-tab-pane :label="`${t('IDS_KADCONTACTLAB')} (${contacts.data.value.length})`">
            <el-table :data="contacts.data.value" :empty-text="t('nj.kad.off')" height="420">
                <el-table-column :label="t('IDS_ID')" prop="id" min-width="280" show-overflow-tooltip />
                <el-table-column :label="t('IDS_TYPE')" prop="type" width="80" sortable align="right" />
                <el-table-column :label="t('IDS_KADDISTANCE')" prop="distance" min-width="280" sortable />
                <el-table-column :label="t('IDS_IP')" prop="address" width="190" />
                <el-table-column label="Kad" prop="version" width="70" align="right" />
            </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`${t('IDS_KADSEARCHLAB')} (${lookups.data.value.length})`">
            <el-table :data="lookups.data.value" :empty-text="t('nj.kad.noSearches')">
                <el-table-column :label="t('IDS_KADID')" prop="target" min-width="280" />
                <el-table-column :label="t('IDS_TYPE')" width="180">
                    <template #default="{ row }">{{ t(KINDS[(row as Lookup).kind]) }}</template>
                </el-table-column>
                <el-table-column :label="t('nj.kad.answers')" prop="answers" width="110" align="right" />
            </el-table>
        </el-tab-pane>
    </el-tabs>
</template>

<style scoped>
.distance h3 {
    margin: 0 0 8px;
    font-size: 14px;
}
.bars {
    display: flex;
    align-items: flex-end;
    gap: 3px;
    height: 72px;
    margin-bottom: 12px;
}
.bar {
    flex: 1;
    background: var(--el-color-primary-light-5);
    border-radius: 2px 2px 0 0;
}
.bar.empty {
    background: var(--el-border-color);
}
.said {
    margin: 0 0 12px;
}
.facts {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 14px;
}
dl {
    display: grid;
    grid-template-columns: max-content auto;
    gap: 4px 16px;
    margin: 0;
}
dt {
    color: var(--el-text-color-secondary);
}
dd {
    margin: 0;
}
.id {
    font-family: ui-monospace, monospace;
}
.join {
    margin-bottom: 14px;
    padding: 12px 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.join h2 {
    margin: 0 0 4px;
    font-size: 15px;
}
.hint {
    margin: 0 0 10px;
    color: var(--el-text-color-secondary);
}
.line {
    display: grid;
    grid-template-columns: 220px minmax(0, 1fr) auto;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
}
</style>
