<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElSegmented } from 'element-plus';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import NjIcon from '../components/NjIcon.vue';
import PortTest from '../components/PortTest.vue';
import { status } from '../engine';
import { count } from '../format';
import { t } from '../i18n';
import { forwardingText } from '../ports';
import KadView from './network/KadView.vue';
import LogView from './network/LogView.vue';
import { guest } from '../role';
import ServersView from './network/ServersView.vue';

const route = useRoute();
const router = useRouter();
const part = computed({
    get: () => (route.params.part as string | undefined) ?? 'servers',
    set: (value: string) => void router.replace(`/network/${value}`),
});

const PARTS = computed(() =>
    [
        { value: 'servers', label: t('IDS_SV_SERVERLIST') },
        { value: 'kad', label: t('IDS_EM_KADEMLIA') },
        { value: 'log', label: t('IDS_SV_LOG') },
    ].filter((p) => !guest || p.value !== 'log'),
);

const server = computed(() => status.value?.server ?? null);
const kad = computed(() => status.value?.kad ?? null);
</script>

<template>
    <div class="cards">
        <section class="card">
            <h2><NjIcon name="SERVER" />eD2K</h2>
            <p class="headline">
                {{
                    server
                        ? status?.lowId
                            ? t('nj.network.unreachable')
                            : t('nj.network.reachable')
                        : t('IDS_NOTCONNECTED')
                }}
            </p>
            <p v-if="server">{{ server.name || server.address }}</p>
            <p v-if="server">{{ t('nj.status.server', count(server.users), count(server.files)) }}</p>
        </section>
        <section class="card">
            <h2><NjIcon name="KAD" />Kad</h2>
            <p class="headline">
                {{
                    kad === null
                        ? t('nj.kad.off')
                        : !kad.connected
                          ? t('IDS_CONNECTING')
                          : kad.firewalled
                            ? t('nj.network.unreachable')
                            : t('nj.network.reachable')
                }}
            </p>
            <p v-if="kad">{{ t('IDS_KADCONTACTLAB') }}: {{ count(kad.contacts) }}</p>
            <p v-if="kad?.connected">{{ kad.udpFirewalled ? t('nj.kad.udpFirewalled') : t('nj.kad.udpOpen') }}</p>
        </section>
        <section class="card">
            <h2><NjIcon name="PORT_TEST" />{{ t('nj.network.ports') }}</h2>
            <p class="headline">
                TCP {{ status?.tcpPort }}<template v-if="kad"> · UDP {{ kad.udpPort }}</template>
            </p>
            <p v-if="status?.forwarding">{{ forwardingText(status.forwarding, status.router ?? '') }}</p>
            <p v-if="status?.publicIp">IP: {{ status.publicIp }}</p>
            <p v-if="status?.publicIpv6">IPv6: {{ status.publicIpv6 }}</p>
            <p>
                {{ t('nj.network.natTraversal') }}:
                {{ status?.routerTraversal && kad ? t('IDS_YES') : t('IDS_NO') }}
            </p>
            <PortTest v-if="!guest" />
        </section>
    </div>
    <el-segmented v-model="part" :options="PARTS" class="parts" />
    <ServersView v-if="part === 'servers'" />
    <KadView v-else-if="part === 'kad'" />
    <LogView v-else />
</template>

<style scoped>
.cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 12px;
    margin-bottom: 16px;
}
.card {
    padding: 12px 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.card h2 {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 6px;
    font-size: 15px;
}
.card p {
    margin: 2px 0;
    color: var(--el-text-color-secondary);
}
.card .headline {
    color: var(--el-text-color-primary);
    font-weight: 600;
}
.parts {
    margin-bottom: 14px;
}
</style>
