<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import { status, usePoll } from '../engine';
import { t } from '../i18n';
import NjIcon from './NjIcon.vue';

interface Update {
    available: { version: string; critical: boolean } | null;
    skipped?: string;
}

const update = usePoll(() => api.get<Update>('/update'), 60000, null as Update | null);

// A new version the user did not skip; one with security fixes is told whatever was skipped.
const version = computed(() => {
    const u = update.data.value;
    const v = u?.available;
    return v && (v.critical || u?.skipped !== v.version) ? v.version : null;
});

type Kind = 'interface' | 'offline' | 'unreachable' | null;

// The desktop app's banner: what keeps njMule from the network, the gravest first.
const kind = computed<Kind>(() => {
    const s = status.value;
    if (s === null) {
        return null;
    }
    if (s.missingInterface !== undefined) {
        return 'interface';
    }
    if (s.server === null && !s.kad?.connected) {
        return 'offline';
    }
    return s.server !== null && s.lowId ? 'unreachable' : null;
});

// Closed until the state changes.
const closed = ref<Kind>(null);
watch(kind, (now) => {
    if (now !== closed.value) {
        closed.value = null;
    }
});

const text = computed(() => {
    switch (kind.value) {
        case 'interface':
            return t('nj.banner.interfaceMissing', status.value?.missingInterface ?? '');
        case 'offline':
            return t('nj.banner.offline');
        case 'unreachable':
            return t('nj.banner.unreachable', status.value?.tcpPort ?? 0);
        default:
            return '';
    }
});
</script>

<template>
    <p v-if="version" class="banner update" role="status">
        <NjIcon name="INFO" />{{ t('nj.update.available', version) }}
        <router-link to="/settings">{{ t('nj.update.details') }}</router-link>
    </p>
    <p v-if="kind && closed !== kind" class="banner warn" role="status">
        <NjIcon name="WARNING" />{{ text }}
        <button type="button" class="close" :aria-label="t('IDS_FD_CLOSE')" @click="closed = kind">
            <NjIcon name="CLOSE" />
        </button>
    </p>
</template>

<style scoped>
.banner {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 12px 20px 0;
    padding: 8px 14px;
    border-radius: 8px;
}
.banner .nj-icon {
    width: 18px;
    height: 18px;
}
.update {
    border-inline-start: 4px solid var(--nj-accent);
    background: var(--el-bg-color);
}
.warn {
    background: var(--nj-warn-bg);
    color: var(--nj-warn-fg);
}
.close {
    display: flex;
    margin-inline-start: auto;
    padding: 2px;
    border: 0;
    background: none;
    color: inherit;
    cursor: pointer;
}
</style>
