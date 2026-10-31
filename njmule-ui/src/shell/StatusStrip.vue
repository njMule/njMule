<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { PopoverRoot, PopoverTrigger } from 'reka-ui';
import { computed, ref } from 'vue';
import { size } from '../format';
import { t } from '../i18n';
import { limitsOf, limitText } from '../limits';
import { folders, lastLog, preferences, servers, status } from '../model/engine';
import { networkCounts } from '../network';
import { showLog } from '../places';
import Tip from '../ui/Tip.vue';
import Icon from '../ui/Icon.vue';
import { showUpdates, stripText, update } from '../updates';
import SpeedLimits from './SpeedLimits.vue';

// The status strip: the last line of the log (which opens the log), the networks' users and files, the limits (which
// open their sliders) and the room left where downloads end up.

const limits = computed(() => {
    const l = limitsOf(preferences.value);
    return `${t('IDS_PW_CON_LIMITFRM')}: ↓ ${limitText(l.down)} · ↑ ${limitText(l.up)}`;
});
const freeSpace = computed(() => status.value?.freeSpace?.incoming);
const limitsOpen = ref(false);
const limitsTrigger = ref<InstanceType<typeof PopoverTrigger> | null>(null);
// A new version on its way: its download, then that it is ready; opens the updates.
const updateText = computed(() => {
    const words = stripText(update.value);
    return words === null ? '' : t(...words);
});
</script>

<template>
    <footer class="status-strip">
        <Tip :text="t('IDS_SV_LOG')">
            <button class="status-link last-log ellipsis" @click="showLog()">{{ lastLog }}</button>
        </Tip>
        <button v-if="updateText" id="update-link" class="status-link update-link" @click="showUpdates()">
            <Icon name="DOWNLOAD" />{{ updateText }}
        </button>
        <span class="status-text">{{ networkCounts(status, servers) }}</span>
        <PopoverRoot v-model:open="limitsOpen">
            <Tip :text="t('IDS_SPEED_LIMITS')">
                <PopoverTrigger id="limits" ref="limitsTrigger" class="status-link">{{ limits }}</PopoverTrigger>
            </Tip>
            <SpeedLimits :open="limitsOpen" side="top" :reference="limitsTrigger?.$el" />
        </PopoverRoot>
        <!-- The folder where downloads end up, the engine's default one included. -->
        <Tip v-if="freeSpace !== undefined" :text="folders.incoming">
            <span class="status-text">{{ t('IDS_STATS_FREESPACE') }}: {{ size(freeSpace) }}</span>
        </Tip>
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

.update-link {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    --nj-icon-size: 14px;
}
</style>
