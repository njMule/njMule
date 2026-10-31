<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue';
import { type BannerKind, bannerKind } from '../banner';
import { t } from '../i18n';
import { connection, status } from '../model/engine';
import { current } from '../places';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// A line above the screens for a state that lasts and wants something done. It waits a moment before it shows, so a
// reconnection does not make it flicker, and once closed it stays away until the state changes.
const emit = defineEmits<{ connect: [] }>();

const SETTLE_MS = 2000;

const wanted = computed(() => bannerKind(connection.value, status.value));
const shown = ref<BannerKind>('none');
let closed: BannerKind = 'none';
let settling = 0;

watch(
    wanted,
    (kind) => {
        if (kind !== closed) {
            closed = 'none';
        }
        window.clearTimeout(settling);
        settling = window.setTimeout(() => (shown.value = wanted.value === closed ? 'none' : wanted.value), SETTLE_MS);
    },
    { immediate: true },
);
onUnmounted(() => window.clearTimeout(settling));

const text = computed(() => {
    switch (shown.value) {
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

const ICONS = { interface: 'LINK_OFF', offline: 'CLOUD_OFF', unreachable: 'WARNING' } as const;

function act(): void {
    if (shown.value === 'offline') {
        emit('connect');
    } else {
        current.value = 'settings';
    }
}

function close(): void {
    closed = shown.value;
    shown.value = 'none';
}
</script>

<template>
    <div
        v-if="shown !== 'none'"
        class="banner"
        :class="shown === 'unreachable' ? 'unreachable' : 'offline'"
        role="status"
    >
        <Icon class="banner-icon" :name="ICONS[shown]" />
        <span class="banner-text">{{ text }}</span>
        <button type="button" class="button" @click="act">
            {{ t(shown === 'offline' ? 'IDS_MAIN_BTN_CONNECT' : 'IDS_EM_PREFS') }}
        </button>
        <Tip :text="t('IDS_FD_CLOSE')">
            <button type="button" class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')" @click="close">
                <Icon name="CLOSE" />
            </button>
        </Tip>
    </div>
</template>

<style scoped>
.banner {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 12px;
    border-bottom: 1px solid var(--color-border-muted);
}

.banner.offline {
    background: var(--color-accent-subtle);
}

.banner.unreachable {
    background: var(--color-warning-subtle);
}

.banner-icon {
    flex: none;
    --nj-icon-size: 18px;
    --nj-icon-color: var(--color-fg-muted);
}

.unreachable .banner-icon {
    --nj-icon-color: var(--color-warning-fg);
}

.banner-text {
    flex: 1;
    min-width: 0;
}

.banner .button {
    flex: none;
}
</style>
