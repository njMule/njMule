<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef } from 'vue';
import { rate, size } from '../format';
import { t } from '../i18n';
import type { TrayChoice } from '../platform';
import Sparkline from '../shell/Sparkline.vue';
import { glanceOf, stateKey, type Glance } from '../tray';
import Icon from '../ui/Icon.vue';
import type { PopupHost } from './host';

// A small window over the others with njMule at a glance — connected, the rates with their last half minute, the
// downloads finished in this run and the free space where they go — and buttons to the incoming folder, the options
// and the main window. A click on the notification area's icon opens it; it stays until closed or the window returns.

const props = defineProps<{ host: PopupHost }>();

// The width of the window, as the sparkline is drawn the width of its rows.
const WIDTH = 300;
const PADDING = 12;

const root = ref<HTMLElement | null>(null);
const glance = shallowRef<Glance>(glanceOf('disconnected', false, null, 0, []));

const free = computed(() => (glance.value.free === null ? '?' : size(glance.value.free)));

async function show(): Promise<void> {
    glance.value = (await props.host.glance()) ?? glance.value;
    await nextTick();
    // As a window opens, its first button takes the keys.
    root.value?.querySelector('button')?.focus();
    const box = root.value?.getBoundingClientRect();
    if (box) {
        props.host.ready(box.width, box.height);
    }
}

function choose(choice: TrayChoice | 'show'): void {
    props.host.choose(choice);
}

onMounted(() => {
    props.host.onGlance((g) => (glance.value = g));
    props.host.onShow(() => void show());
});
</script>

<template>
    <div ref="root" class="minimule" :style="{ width: `${WIDTH}px`, padding: `${PADDING}px` }">
        <div class="minimule-state">
            <span class="minimule-dot" :class="{ online: glance.online }" />
            <strong>{{ t(stateKey(glance, false)) }}</strong>
        </div>
        <div class="minimule-grid">
            <span class="minimule-name down"><Icon name="ARROW_DOWN" />{{ t('IDS_PW_CON_DOWNLBL') }}</span>
            <strong class="minimule-value">{{ rate(glance.down) }}</strong>
            <Sparkline class="minimule-rates" :rates="glance.rates" :width="WIDTH - 2 * PADDING" />
            <span class="minimule-name up"><Icon name="ARROW_UP" />{{ t('IDS_PW_CON_UPLBL') }}</span>
            <strong class="minimule-value">{{ rate(glance.up) }}</strong>
            <span class="minimule-name muted">{{ t('IDS_DL_TRANSFCOMPL') }}</span>
            <strong class="minimule-value">{{ glance.completed }}</strong>
            <span class="minimule-name muted">{{ t('IDS_STATS_FREESPACE') }}</span>
            <strong class="minimule-value">{{ free }}</strong>
        </div>
        <div class="minimule-buttons">
            <button type="button" class="button small" @click="choose('incoming')">
                <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENINC') }}
            </button>
            <button type="button" class="button small" @click="choose('options')">
                <Icon name="OPTIONS" />{{ t('IDS_EM_PREFS') }}
            </button>
            <button type="button" class="button small" @click="choose('show')">
                <Icon name="OPEN_EXTERNAL" />{{ t('nj.tray.show') }}
            </button>
        </div>
    </div>
</template>

<style scoped>
/* The state with its dot, the rates with their last half minute, then the buttons. */
/* The theme's size for windows of their own, as the desktop app's. */
.minimule {
    display: flex;
    flex-direction: column;
    gap: 10px;
    font-size: 14px;
    line-height: 20px;
}

.minimule-state {
    display: flex;
    align-items: center;
    gap: 8px;
}

.minimule-dot {
    width: 8px;
    height: 8px;
    border-radius: 4px;
    background: var(--color-fg-subtle);
}

.minimule-dot.online {
    background: var(--color-success-fg);
}

.minimule-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 6px 12px;
}

.minimule-name {
    display: flex;
    align-items: center;
    gap: 4px;
    --nj-icon-size: 14px;
}

.minimule-name.down {
    --nj-icon-color: var(--nj-graph-down);
}

.minimule-name.up {
    --nj-icon-color: var(--nj-graph-up);
}

.minimule-name.muted {
    color: var(--color-fg-muted);
}

.minimule-value {
    text-align: end;
}

.minimule-rates {
    grid-column: 1 / -1;
}

.minimule-buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

/* The theme's small buttons. */
.minimule-buttons .button {
    height: auto;
    padding: 5.7px 8.6px;
    line-height: normal;
}
</style>
