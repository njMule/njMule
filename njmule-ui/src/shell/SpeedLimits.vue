<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { PopoverContent, PopoverPortal } from 'reka-ui';
import { ref, watch } from 'vue';
import { t } from '../i18n';
import { kibAt, limitsOf, limitText, positionOf, setLimits, setPreset, STEPS } from '../limits';
import { preferences } from '../model/engine';
import { problem } from '../toasts';
import Icon from '../ui/Icon.vue';

// The speed limits over the rates or the status strip, as in the notification area's menu: a slider for each way,
// applied as it is let go, the limit written beside, and two presets. It goes inside the PopoverRoot of its trigger.
const props = defineProps<{ open: boolean; side: 'top' | 'bottom'; reference?: HTMLElement }>();

interface Way {
    id: 'down' | 'up';
    icon: 'ARROW_DOWN' | 'ARROW_UP';
    label: string;
    position: number;
    // The slider's top: the line's capacity, or the limit set past it.
    top: number;
    text: string;
}

const ways = ref<Way[]>([]);

// The limits the preferences hold now; the text is the limit as set, not as the position gives it back.
function load(): void {
    const l = limitsOf(preferences.value);
    const downTop = Math.max(l.downCapacity, l.down);
    const upTop = Math.max(l.upCapacity, l.up);
    ways.value = [
        {
            id: 'down',
            icon: 'ARROW_DOWN',
            label: t('IDS_PW_CON_DOWNLBL'),
            position: positionOf(l.down, downTop),
            top: downTop,
            text: limitText(l.down),
        },
        {
            id: 'up',
            icon: 'ARROW_UP',
            label: t('IDS_PW_CON_UPLBL'),
            position: positionOf(l.up, upTop),
            top: upTop,
            text: limitText(l.up),
        },
    ];
}

watch(
    () => props.open,
    (now) => {
        if (now) {
            load();
        }
    },
    { immediate: true },
);

function moved(way: Way, e: Event): void {
    way.position = Number((e.target as HTMLInputElement).value);
    way.text = limitText(kibAt(way.position, way.top));
}

async function apply(change: { downloadKiBps?: number; uploadKiBps?: number }): Promise<void> {
    try {
        await setLimits(change);
    } catch (e) {
        problem((e as Error).message);
    }
}

// Let go, or moved by the keys: the limit applies at once.
function chosen(way: Way): void {
    const kib = kibAt(way.position, way.top);
    const l = limitsOf(preferences.value);
    if (kib !== (way.id === 'down' ? l.down : l.up)) {
        void apply(way.id === 'down' ? { downloadKiBps: kib } : { uploadKiBps: kib });
    }
}

async function preset(fast: boolean): Promise<void> {
    try {
        await setPreset(fast);
    } catch (e) {
        problem((e as Error).message);
    }
    load();
}
</script>

<template>
    <PopoverPortal>
        <PopoverContent
            class="menu limits-popover"
            :side="side"
            align="center"
            :side-offset="6"
            :reference="reference"
            :aria-label="t('nj.tray.limits')"
        >
            <strong class="popover-title">{{ t('nj.tray.limits') }}</strong>
            <div class="speeds">
                <template v-for="way in ways" :key="way.id">
                    <Icon :name="way.icon" :class="way.id" />
                    <span class="speed-name">{{ way.label }}</span>
                    <input
                        type="range"
                        class="speed-slider"
                        min="0"
                        :max="STEPS"
                        step="1"
                        :value="way.position"
                        :aria-label="way.label"
                        :aria-valuetext="way.text"
                        @input="moved(way, $event)"
                        @change="chosen(way)"
                    />
                    <span class="speed-value">{{ way.text }}</span>
                </template>
            </div>
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
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 10px 12px 12px;
}

.popover-title {
    font-size: 1.05em;
}

/* An arrow in the colour of its line, the way's name, the slider, then the limit. */
.speeds {
    display: grid;
    grid-template-columns: auto auto 140px auto;
    align-items: center;
    gap: 6px 8px;
}

.speeds .down {
    --nj-icon-color: var(--nj-graph-down);
}

.speeds .up {
    --nj-icon-color: var(--nj-graph-up);
}

.speed-name,
.speed-value {
    white-space: nowrap;
}

.speed-slider {
    width: 140px;
    margin: 0;
    accent-color: var(--color-accent-emphasis);
}

.presets {
    display: flex;
    gap: 8px;
}
</style>
