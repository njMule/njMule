<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    ElButton,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElInput,
    ElMessageBox,
    ElPopover,
    ElTable,
    ElTableColumn,
} from 'element-plus';
import { computed, ref } from 'vue';
import { api } from '../../api';
import { copy } from '../../clipboard';
import NjIcon from '../../components/NjIcon.vue';
import { status, usePoll } from '../../engine';
import { count } from '../../format';
import { t } from '../../i18n';
import { guest } from '../../role';
import { attempt, told } from '../../notify';
import type { Server } from '../../types';

const servers = usePoll(() => api.get<Server[]>('/servers'), 10000, [] as Server[]);
const filter = ref('');
const chosen = ref<Server[]>([]);
const metUrl = ref('https://upd.emule-security.org/server.met');
const address = ref('');

const current = computed(() => status.value?.server?.address ?? '');

const visible = computed(() => {
    const words = filter.value
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return servers.data.value.filter((s) => {
        const text = `${s.name} ${s.address} ${s.description} ${s.version}`.toLowerCase();
        return words.every((w) => text.includes(w));
    });
});

const PRIORITY: Record<Server['priority'], string> = {
    low: 'IDS_PRIOLOW',
    normal: 'IDS_PRIONORMAL',
    high: 'IDS_PRIOHIGH',
};

async function update(): Promise<void> {
    const url = metUrl.value.trim();
    told(t('IDS_DOWNLOADING_SERVERMET_FROM', url));
    await attempt(async () => {
        const { added } = await api.post<{ added: number }>('/servers/import', { url });
        told(t('nj.servers.fromUrl', added, url), 'success');
    });
    await servers.refresh();
}

// A guest's double click does nothing.
function connectOnDoubleClick(row: Server): void {
    if (!guest) {
        void connectTo(row.address);
    }
}

async function connectTo(where?: string): Promise<void> {
    const body = where === undefined ? {} : { address: where };
    await attempt(() => api.post('/servers/connect', body));
}

async function addAndConnect(): Promise<void> {
    const where = address.value.trim();
    if (!/^[^\s:]+:\d{1,5}$/.test(where)) {
        told(t('nj.servers.badAddress', where));
        return;
    }
    await attempt(async () => {
        try {
            await api.post('/servers', { address: where });
        } catch {
            // Known already: connect all the same.
        }
        await api.post('/servers/connect', { address: where });
    });
    await servers.refresh();
}

async function remove(all: boolean): Promise<void> {
    const gone = all ? servers.data.value : chosen.value;
    if (all) {
        try {
            await ElMessageBox.confirm(t('nj.servers.removeAllText'), t('nj.servers.removeAllTitle', gone.length), {
                type: 'warning',
                confirmButtonText: t('IDS_REMOVEALL'),
                cancelButtonText: t('nj.servers.keepAll'),
                confirmButtonClass: 'el-button--danger',
            });
        } catch {
            return;
        }
    }
    await attempt(async () => {
        for (const s of gone) {
            await api.delete(`/servers/${encodeURIComponent(s.address)}`);
        }
    });
    await servers.refresh();
}

async function each(call: (s: Server) => Promise<unknown>): Promise<void> {
    await attempt(async () => {
        for (const s of chosen.value) {
            await call(s);
        }
    });
    await servers.refresh();
}

function setPriority(priority: Server['priority']): void {
    void each((s) => api.put(`/servers/${encodeURIComponent(s.address)}/priority`, { priority }));
}

function setStatic(on: boolean): void {
    void each((s) => api.put(`/servers/${encodeURIComponent(s.address)}/static`, { static: on }));
}

function copyLinks(): void {
    const links = chosen.value.map((s) => {
        const colon = s.address.lastIndexOf(':');
        return `ed2k://|server|${s.address.substring(0, colon)}|${s.address.substring(colon + 1)}|/`;
    });
    void copy(links.join('\n'), t('IDS_DL_LINK1'));
}
</script>

<template>
    <div class="tools">
        <template v-if="!guest">
            <el-popover trigger="click" :width="420">
                <template #reference>
                    <el-button type="primary"><NjIcon name="ADD" />{{ t('nj.servers.add') }}</el-button>
                </template>
                <form class="add" @submit.prevent="update">
                    <label for="nj-met">{{ t('IDS_SV_MET') }}</label>
                    <div class="line">
                        <el-input id="nj-met" v-model="metUrl" />
                        <el-button native-type="submit">{{ t('IDS_SV_UPDATE') }}</el-button>
                    </div>
                </form>
                <form class="add" @submit.prevent="addAndConnect">
                    <label for="nj-address">{{ t('IDS_SV_ADDRESS') }}</label>
                    <div class="line">
                        <el-input id="nj-address" v-model="address" :placeholder="t('nj.servers.addressPrompt')" />
                        <el-button native-type="submit">{{ t('nj.servers.addConnect') }}</el-button>
                    </div>
                </form>
            </el-popover>
            <el-button :disabled="chosen.length !== 1" @click="connectTo(chosen[0]?.address)">
                {{ t('IDS_CONNECTTHIS') }}
            </el-button>
            <el-dropdown :disabled="chosen.length === 0" trigger="click" @command="setPriority">
                <el-button :disabled="chosen.length === 0">{{ t('IDS_PRIORITY') }}</el-button>
                <template #dropdown>
                    <el-dropdown-menu>
                        <el-dropdown-item v-for="(key, value) in PRIORITY" :key="value" :command="value">
                            {{ t(key) }}
                        </el-dropdown-item>
                    </el-dropdown-menu>
                </template>
            </el-dropdown>
            <el-button :disabled="chosen.length === 0" @click="setStatic(true)">{{ t('IDS_ADDTOSTATIC') }}</el-button>
            <el-button :disabled="chosen.length === 0" @click="setStatic(false)">{{
                t('IDS_REMOVEFROMSTATIC')
            }}</el-button>
            <el-button :disabled="chosen.length === 0" @click="copyLinks">
                <NjIcon name="COPY" />{{ t('IDS_DL_LINK1') }}
            </el-button>
            <el-button :disabled="chosen.length === 0" @click="remove(false)">{{ t('IDS_REMOVETHIS') }}</el-button>
            <el-button :disabled="servers.data.value.length === 0" type="danger" plain @click="remove(true)">
                {{ t('IDS_REMOVEALL') }}
            </el-button>
        </template>
        <span class="grow"></span>
        <span class="count">{{ t('IDS_SV_SERVERLIST') }} ({{ visible.length }}/{{ servers.data.value.length }})</span>
        <el-input v-model="filter" class="filter" clearable :placeholder="t('nj.servers.filter')" />
    </div>
    <el-table
        :data="visible"
        row-key="address"
        :empty-text="t('nj.servers.none')"
        :row-class-name="({ row }: { row: Server }) => (row.address === current ? 'current' : '')"
        @selection-change="(rows: Server[]) => (chosen = rows)"
        @row-dblclick="(row: Server) => connectOnDoubleClick(row)"
    >
        <el-table-column type="selection" width="40" />
        <el-table-column :label="t('IDS_SL_SERVERNAME')" prop="name" min-width="180" sortable show-overflow-tooltip />
        <el-table-column :label="t('IDS_IP')" prop="address" width="170" sortable />
        <el-table-column :label="t('IDS_DESCRIPTION')" prop="description" min-width="160" show-overflow-tooltip />
        <el-table-column :label="t('IDS_PING')" prop="ping" width="80" sortable align="right" />
        <el-table-column :label="t('IDS_UUSERS')" prop="users" width="100" sortable align="right">
            <template #default="{ row }">{{ count(row.users) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_MAXCLIENT')" prop="maxUsers" width="110" sortable align="right">
            <template #default="{ row }">{{ count(row.maxUsers) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_PW_FILES')" prop="files" width="120" sortable align="right">
            <template #default="{ row }">{{ count(row.files) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_PRIORITY')" width="100">
            <template #default="{ row }">{{ t(PRIORITY[(row as Server).priority]) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_UFAILED')" prop="failed" width="90" sortable align="right" />
        <el-table-column :label="t('IDS_STATICSERVER')" width="90">
            <template #default="{ row }">{{ row.static ? t('IDS_YES') : t('IDS_NO') }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_SOFTFILES')" prop="softFiles" width="120" align="right" />
        <el-table-column :label="t('IDS_IDLOW')" prop="lowIdUsers" width="100" align="right" />
        <el-table-column :label="t('IDS_OBFUSCATION')" width="110">
            <template #default="{ row }">{{ row.obfuscation ? t('IDS_YES') : t('IDS_NO') }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_VERSION')" prop="version" width="100" />
    </el-table>
</template>

<style scoped>
.tools {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
}
.tools .el-button + .el-button {
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
.count {
    color: var(--el-text-color-secondary);
}
.filter {
    width: 220px;
}
.add label {
    display: block;
    margin: 8px 0 4px;
    font-weight: 600;
}
.line {
    display: flex;
    gap: 8px;
}
:deep(.current) {
    font-weight: 600;
}
</style>
