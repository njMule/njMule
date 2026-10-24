<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElPopover } from 'element-plus';
import { computed } from 'vue';
import { api } from '../api';
import { status } from '../engine';
import { count } from '../format';
import { t } from '../i18n';
import { attempt } from '../notify';
import NjIcon from './NjIcon.vue';

// The engine names a server while it connects too: the one being tried, before it gave us an ID.
const server = computed(() => status.value?.server ?? null);
const ed2kUp = computed(() => server.value !== null && (status.value?.clientId ?? 0) !== 0);
const ed2kTrying = computed(() => server.value !== null && !ed2kUp.value);
const kad = computed(() => status.value?.kad ?? null);
const kadUp = computed(() => !!kad.value?.connected);
const connected = computed(() => ed2kUp.value || kadUp.value);

const ed2kText = computed(() => {
    if (ed2kUp.value && server.value) {
        const id = status.value?.lowId ? t('IDS_IDLOW') : t('IDS_IDHIGH');
        return `eD2K: ${server.value.name || server.value.address} (${id})`;
    }
    return ed2kTrying.value ? `eD2K: ${t('IDS_CONNECTING')}` : `eD2K: ${t('IDS_NOTCONNECTED')}`;
});

const kadText = computed(() => {
    if (kad.value === null) {
        return `Kad: ${t('nj.kad.off')}`;
    }
    if (!kad.value.connected) {
        return `Kad: ${t('IDS_NOTCONNECTED')}`;
    }
    const udp = kad.value.udpFirewalled ? t('nj.kad.udpFirewalled') : t('nj.kad.udpOpen');
    return `Kad: ${kad.value.firewalled ? t('IDS_FIREWALLED') : t('IDS_KADOPEN')}, ${udp}`;
});

function buttonText(up: boolean, trying: boolean): string {
    return up ? t('IDS_MAIN_BTN_DISCONNECT') : trying ? t('IDS_MAIN_BTN_CANCEL') : t('IDS_MAIN_BTN_CONNECT');
}

async function ed2k(): Promise<void> {
    await attempt(() => api.post(server.value === null ? '/servers/connect' : '/servers/disconnect'));
}

async function kadToggle(): Promise<void> {
    await attempt(() => api.post(kadUp.value ? '/kad/disconnect' : '/kad/connect'));
}

// Both networks at once, as the desktop app's button does.
async function both(): Promise<void> {
    if (connected.value || ed2kTrying.value) {
        await attempt(async () => {
            await api.post('/servers/disconnect');
            if (kad.value !== null) {
                await api.post('/kad/disconnect');
            }
        });
    } else {
        await attempt(async () => {
            await api.post('/servers/connect');
            await api.post('/kad/connect');
        });
    }
}
</script>

<template>
    <el-popover trigger="click" placement="bottom-end" :width="340">
        <template #reference>
            <button type="button" class="nj-chip state" :class="connected ? 'ok' : ed2kTrying ? 'busy' : 'warn'">
                <NjIcon name="DOT" />
                {{ connected ? t('IDS_CONNECTED') : ed2kTrying ? t('IDS_CONNECTING') : t('IDS_NOTCONNECTED') }}
            </button>
        </template>
        <div class="row">
            <div>
                <b>{{ ed2kText }}</b>
                <p v-if="server && ed2kUp">{{ t('nj.status.server', count(server.users), count(server.files)) }}</p>
            </div>
            <el-button size="small" @click="ed2k">{{ buttonText(ed2kUp, ed2kTrying) }}</el-button>
        </div>
        <div class="row">
            <div>
                <b>{{ kadText }}</b>
                <p v-if="kad">{{ t('IDS_KADCONTACTLAB') }}: {{ count(kad.contacts) }}</p>
            </div>
            <el-button size="small" @click="kadToggle">{{ buttonText(kadUp, false) }}</el-button>
        </div>
        <el-button class="both" type="primary" @click="both">{{ buttonText(connected, ed2kTrying) }}</el-button>
    </el-popover>
</template>

<style scoped>
.state {
    border: 0;
    cursor: pointer;
    font: inherit;
    font-size: 13px;
}
.row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
    margin-bottom: 12px;
}
.row p {
    margin: 2px 0 0;
    color: var(--el-text-color-secondary);
}
.both {
    width: 100%;
}
</style>
