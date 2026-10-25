<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInput } from 'element-plus';
import { onMounted, ref } from 'vue';
import { api } from '../api';
import { t } from '../i18n';
import { attempt, failed, told } from '../notify';

// What a settings page does besides its options: the IP filter's update, the addresses of the server lists, the
// test of the notifications.
const props = defineProps<{ page: string }>();
const addresses = ref('');

onMounted(async () => {
    if (props.page === 'servers') {
        await attempt(async () => {
            addresses.value = (await api.get<{ addresses: string[] }>('/servers/lists')).addresses.join('\n');
        });
    }
});

async function updateIpFilter(): Promise<void> {
    told(t('IDS_DWL_IPFILTERFILE'));
    await attempt(async () => {
        const { added } = await api.post<{ added: number }>('/ipfilter/lists');
        told(t('IDS_IPFILTERLOADED', added), 'success');
    });
}

interface Sent {
    channel: 'email' | 'webhook';
    sent: boolean;
    error?: string;
}

async function testNotifications(): Promise<void> {
    await attempt(async () => {
        const results = await api.post<Sent[]>('/notifications/test');
        if (results.length === 0) {
            told(t('nj.notify.none'));
        }
        for (const r of results) {
            const channel = t(r.channel === 'email' ? 'IDS_PW_EMAILNOTIFICATIONS' : 'nj.notify.hook');
            if (r.sent) {
                told(t('nj.notify.sent', channel), 'success');
            } else {
                failed(`${channel}: ${r.error ?? ''}`);
            }
        }
    });
}

async function saveAddresses(): Promise<void> {
    const list = addresses.value
        .split('\n')
        .map((l) => l.trim())
        .filter((l) => l !== '');
    if (await attempt(() => api.put('/servers/lists', { addresses: list }))) {
        told(t('nj.settings.saved'), 'success');
    }
}
</script>

<template>
    <div v-if="page === 'ipfilter'" class="extra">
        <el-button @click="updateIpFilter">{{ t('IDS_SV_UPDATE') }}</el-button>
    </div>
    <div v-else-if="page === 'notify'" class="extra">
        <el-button @click="testNotifications">{{ t('IDS_TEST') }}</el-button>
    </div>
    <div v-else-if="page === 'servers'" class="extra">
        <label for="nj-server-lists">
            {{ t('nj.settings.addressesTitle') }}
            <small>{{ t('nj.settings.serverListAddresses') }}</small>
        </label>
        <el-input id="nj-server-lists" v-model="addresses" type="textarea" :rows="4" />
        <el-button @click="saveAddresses">{{ t('IDS_SAVE') }}</el-button>
    </div>
</template>

<style scoped>
.extra {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
    padding: 12px 0;
}
.extra label {
    display: flex;
    flex-direction: column;
}
.extra small {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
</style>
