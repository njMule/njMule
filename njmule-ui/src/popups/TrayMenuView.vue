<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, nextTick, onMounted, ref, shallowRef } from 'vue';
import { answer, api } from '../api/client';
import logo from '../assets/njmule-icon.svg';
import { rate } from '../format';
import { t } from '../i18n';
import { preferences } from '../model/engine';
import type { TrayChoice } from '../platform';
import SpeedSliders from '../shell/SpeedSliders.vue';
import { glanceOf, stateLine, type Glance } from '../tray';
import Icon from '../ui/Icon.vue';
import type { PopupHost } from './host';

// The menu of the notification area's icon, a window of its own as a plain menu cannot hold sliders: njMule's name
// with its state and rates, the speed limits with their two presets, then the window (its default), connecting or
// disconnecting, the options and quitting. It goes when it loses the focus, on Escape, or once an item is chosen.

const props = defineProps<{ host: PopupHost }>();

const root = ref<HTMLElement | null>(null);
const glance = shallowRef<Glance>(glanceOf('disconnected', false, null, 0, []));
// On while the menu shows, so that the sliders show the limits as they are as it opens.
const open = ref(false);

const state = computed(() => stateLine(glance.value, t, rate));
// Only the one that applies shows: Disconnect while connected or trying a server.
const connected = computed(() => glance.value.connection !== 'disconnected');

async function show(): Promise<void> {
    open.value = false;
    glance.value = (await props.host.glance()) ?? glance.value;
    try {
        preferences.value = await answer(api().GET('/preferences'));
    } catch {
        // The limits as last read.
    }
    open.value = true;
    await nextTick();
    root.value?.focus();
    const size = root.value?.getBoundingClientRect();
    if (size) {
        props.host.ready(size.width, size.height);
    }
}

// Escape closes the menu; the arrows go from item to item, as between the buttons of the desktop app's.
function key(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
        props.host.done();
        return;
    }
    const items = [...(root.value?.querySelectorAll<HTMLElement>('.tray-item') ?? [])];
    const at = items.indexOf(document.activeElement as HTMLElement);
    const step = e.key === 'ArrowDown' ? 1 : e.key === 'ArrowUp' ? -1 : 0;
    if (step !== 0 && (at >= 0 || document.activeElement === root.value)) {
        e.preventDefault();
        items[at < 0 ? 0 : (at + step + items.length) % items.length]?.focus();
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
    <div ref="root" class="tray-menu" tabindex="-1" @keydown="key">
        <div class="tray-head">
            <img :src="logo" alt="" width="24" height="24" />
            <div class="tray-words">
                <span class="tray-name wordmark">njMule</span>
                <span class="tray-state">{{ state }}</span>
            </div>
        </div>
        <div class="tray-limits">
            <span class="dialog-label">{{ t('nj.tray.limits') }}</span>
            <SpeedSliders :open="open" />
            <div class="tray-presets">
                <button type="button" class="button tray-preset" @click="choose('full-speed')">
                    <Icon name="SPEED" />{{ t('nj.tray.fullSpeed') }}
                </button>
                <button type="button" class="button tray-preset" @click="choose('slow-down')">
                    <Icon name="SLOW" />{{ t('nj.tray.slowDown') }}
                </button>
            </div>
        </div>
        <div class="tray-items">
            <button type="button" class="tray-item tray-default" @click="choose('show')">
                <Icon name="OPEN_EXTERNAL" />{{ t('nj.tray.show') }}
            </button>
            <button v-if="connected" type="button" class="tray-item" @click="choose('connect')">
                <Icon name="LINK_OFF" />{{ t('IDS_MAIN_BTN_DISCONNECT') }}
            </button>
            <button v-else type="button" class="tray-item" @click="choose('connect')">
                <Icon name="LINK" />{{ t('IDS_MAIN_BTN_CONNECT') }}
            </button>
            <button type="button" class="tray-item" @click="choose('options')">
                <Icon name="OPTIONS" />{{ t('IDS_EM_PREFS') }}
            </button>
            <hr class="tray-separator" />
            <button type="button" class="tray-item" @click="choose('quit')">
                <Icon name="POWER" />{{ t('nj.exit.quit') }}
            </button>
        </div>
    </div>
</template>

<style scoped>
/* Compact, as a menu of the notification area is: the interface's text size, rows close together. */
.tray-menu {
    width: 280px;
    border: 1px solid var(--color-border-default);
    background: var(--color-bg-default);
    font-size: 12px;
    outline: none;
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-fg-muted);
}

/* The mark and the name on the brand's blue, with the state and the rates under the name. */
.tray-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 9px 12px;
    background: var(--nj-brand);
    color: var(--nj-brand-mark);
}

/* The heights of the desktop app's labels, the name's font taller than the text's. */
.tray-words {
    display: flex;
    flex-direction: column;
    min-width: 0;
    line-height: 16px;
}

.tray-state {
    font-size: 11px;
    line-height: 15px;
    white-space: nowrap;
}

/* The sliders of the limits, then the two settings one reaches for. */
.tray-limits {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 9px 12px;
    border-bottom: 1px solid var(--color-border-muted);
}

.dialog-label {
    color: var(--color-fg-muted);
    font-size: 0.92em;
    font-weight: bold;
    line-height: 16px;
}

/* The sliders take the width the menu leaves them; the arrows are as muted as the menu's other icons. */
.tray-limits :deep(.speeds) {
    grid-template-columns: auto auto minmax(0, 116px) auto;
}

.tray-limits :deep(.speeds .nj-icon) {
    --nj-icon-color: var(--color-fg-muted);
}

.tray-presets {
    display: flex;
    gap: 6px;
}

.tray-preset {
    gap: 6px;
    height: auto;
    padding: 3px 8px;
    font-size: 11px;
}

.tray-items {
    display: flex;
    flex-direction: column;
    padding: 4px;
}

.tray-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 10px;
    border: 0;
    border-radius: 6px;
    line-height: 16px;
    background: transparent;
    color: var(--color-fg-default);
    text-align: start;
    cursor: pointer;
}

.tray-item:hover {
    background: var(--color-bg-subtle);
}

.tray-item:focus-visible {
    outline: 2px solid var(--color-accent-emphasis);
    outline-offset: -2px;
}

.tray-default {
    font-weight: bold;
}

.tray-separator {
    margin: 4px 8px;
    border: 0;
    border-top: 1px solid var(--color-border-default);
}
</style>
