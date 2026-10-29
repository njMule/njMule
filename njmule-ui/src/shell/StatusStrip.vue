<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { rate, size } from '../format';
import { t } from '../i18n';
import { lastLog, preferences, servers, status } from '../model/engine';
import { networkCounts } from '../network';
import { current, networkPart } from '../places';
import Tip from '../ui/Tip.vue';

// The status strip: the last line of the log (which opens the log), the networks' users and files, the limits and the
// room left where downloads end up.

function limit(kib: string | undefined): string {
    const value = Number(kib ?? 0);
    return value === 0 ? t('nj.rate.noLimit') : rate(value * 1024);
}

const limits = computed(
    () =>
        `${t('IDS_PW_CON_LIMITFRM')}: ↓ ${limit(preferences.value['limits.downloadKiBps'])} · ↑ ${limit(preferences.value['limits.uploadKiBps'])}`,
);
const freeSpace = computed(() => status.value?.freeSpace?.incoming);
</script>

<template>
    <footer class="status-strip">
        <Tip :text="t('IDS_SV_LOG')">
            <button
                class="status-link last-log ellipsis"
                @click="
                    networkPart = 'log';
                    current = 'network';
                "
            >
                {{ lastLog }}
            </button>
        </Tip>
        <span class="status-text">{{ networkCounts(status, servers) }}</span>
        <Tip :text="t('IDS_SPEED_LIMITS')">
            <button id="limits" class="status-link">{{ limits }}</button>
        </Tip>
        <span v-if="freeSpace !== undefined" class="status-text"
            >{{ t('IDS_STATS_FREESPACE') }}: {{ size(freeSpace) }}</span
        >
    </footer>
</template>

<style scoped>
.status-strip {
    display: flex;
    align-items: center;
    gap: 20px;
    min-height: 28px;
    padding: 4px 20px;
    background: var(--color-bg-subtle);
    border-top: 1px solid var(--color-border-muted);
    color: var(--color-fg-muted);
    font-size: 0.9em;
    white-space: nowrap;
}

.status-link {
    padding: 0;
    border: 0;
    background: transparent;
    color: var(--color-fg-muted);
    cursor: pointer;
}

.status-link:hover {
    color: var(--color-fg-default);
    text-decoration: underline;
}

.last-log {
    flex: 1;
    min-width: 0;
    text-align: start;
    direction: ltr;
}
</style>
