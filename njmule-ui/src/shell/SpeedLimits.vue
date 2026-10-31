<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { PopoverContent, PopoverPortal } from 'reka-ui';
import { ref } from 'vue';
import { t } from '../i18n';
import { setPreset } from '../limits';
import { problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import PopoverHeader from '../ui/PopoverHeader.vue';
import SpeedSliders from './SpeedSliders.vue';

// The speed limits over the rates or the status strip, as in the notification area's menu: the sliders and two
// presets. It goes inside the PopoverRoot of its trigger.
defineProps<{ open: boolean; side: 'top' | 'bottom'; reference?: HTMLElement }>();

const sliders = ref<InstanceType<typeof SpeedSliders> | null>(null);

async function preset(fast: boolean): Promise<void> {
    try {
        await setPreset(fast);
    } catch (e) {
        problem((e as Error).message);
    }
    sliders.value?.load();
}
</script>

<template>
    <PopoverPortal>
        <PopoverContent
            class="menu limits-popover"
            :class="{ 'in-strip': side === 'top' }"
            :side="side"
            align="center"
            :side-offset="2"
            :reference="reference"
            :aria-label="t('nj.tray.limits')"
        >
            <PopoverHeader :title="t('nj.tray.limits')" />
            <SpeedSliders ref="sliders" :open="open" />
            <div class="presets">
                <button type="button" class="button" @click="preset(true)">
                    <Icon name="SPEED" />{{ t('nj.tray.fullSpeed') }}
                </button>
                <button type="button" class="button" @click="preset(false)">
                    <Icon name="SLOW" />{{ t('nj.tray.slowDown') }}
                </button>
            </div>
        </PopoverContent>
    </PopoverPortal>
</template>

<style scoped>
:deep(.limits-popover) {
    padding: 14px;
    border-radius: 6px;
}

/* Over the status strip it takes the strip's smaller text, as in the desktop app. */
:deep(.limits-popover.in-strip) {
    font-size: 0.9em;
}

.presets {
    display: flex;
    gap: 8px;
    margin-top: 10px;
}
</style>
