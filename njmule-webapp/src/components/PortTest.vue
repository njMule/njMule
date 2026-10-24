<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton } from 'element-plus';
import { onUnmounted, ref } from 'vue';
import { api } from '../api';
import { status } from '../engine';
import { t } from '../i18n';
import { attempt } from '../notify';

interface Test {
    tcpPort: number;
    udpPort: number;
    forwarding: 'off' | 'asking' | 'forwarded' | 'partly' | 'refused' | 'no_router' | 'failed';
    router: string;
    tcp: Reach;
    udp: Reach;
    done: boolean;
}

type Reach = 'testing' | 'open' | 'closed' | 'unknown';

const FORWARDING: Record<Test['forwarding'], string> = {
    off: 'nj.ports.forwarding.OFF',
    asking: 'nj.ports.forwarding.ASKING',
    forwarded: 'nj.ports.forwarding.FORWARDED',
    partly: 'nj.ports.forwarding.PARTLY',
    refused: 'nj.ports.forwarding.REFUSED',
    no_router: 'nj.ports.forwarding.NO_ROUTER',
    failed: 'nj.ports.forwarding.FAILED',
};

const REACH: Record<Reach, string> = {
    testing: 'nj.ports.reach.TESTING',
    open: 'nj.ports.reach.OPEN',
    closed: 'nj.ports.reach.CLOSED',
    unknown: 'nj.ports.reach.UNKNOWN',
};

const test = ref<Test | null>(null);
let timer = 0;

// The test goes on in the engine: it is read again until it is done.
async function start(): Promise<void> {
    await attempt(async () => {
        test.value = await api.post<Test>('/ports/test');
    });
    window.clearInterval(timer);
    timer = window.setInterval(async () => {
        try {
            test.value = await api.get<Test | null>('/ports/test');
        } catch {
            // Read again next time.
        }
        if (test.value === null || test.value.done) {
            window.clearInterval(timer);
        }
    }, 1000);
}

onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <el-button size="small" @click="start">{{ t('nj.network.testPorts') }}</el-button>
    <ul v-if="test" class="lines">
        <li>{{ t(FORWARDING[test.forwarding], test.router) }}</li>
        <li :class="test.tcp">{{ t(REACH[test.tcp], 'TCP', String(test.tcpPort)) }}</li>
        <li v-if="status?.kad" :class="test.udp">
            {{ t(REACH[test.udp], 'UDP', String(test.udpPort)) }}
            <template v-if="test.udp === 'testing'"> {{ t('nj.ports.udpSlow') }}</template>
        </li>
        <li v-else>{{ t('nj.ports.noKad') }}</li>
    </ul>
</template>

<style scoped>
.lines {
    margin: 8px 0 0;
    padding-inline-start: 18px;
    color: var(--el-text-color-secondary);
}
.lines .open {
    color: var(--nj-ok-fg);
}
.lines .closed {
    color: var(--nj-error-fg);
}
</style>
