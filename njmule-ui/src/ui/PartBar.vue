<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import type { Schemas } from '../api/client';
import { t } from '../i18n';

// The map of a download's parts: what is here dark, what is missing bluer the more sources have it, rose where none
// has it, what is asked for now in yellow; the share completed as a line along the top.

const props = defineProps<{ parts: Schemas['PartMap']; completed: number }>();

const PART = 9_728_000;
const WIDTH = 1000;
const HEIGHT = 36;
const TOP_LINE = 4;

function x(offset: number): number {
    return props.parts.size > 0 ? (offset / props.parts.size) * WIDTH : 0;
}

const most = computed(() => Math.max(1, ...props.parts.sources));

const missing = computed(() =>
    props.parts.missing.map(([start = 0, end = 0]) => {
        const sources = props.parts.sources[Math.floor(start / PART)] ?? 0;
        return {
            x: x(start),
            width: Math.max(0.5, x(end) - x(start)),
            kind: sources === 0 ? 'none' : 'available',
            // Bluer the more sources have the part, from the lightest tint to the strongest.
            mix: sources === 0 ? 0 : Math.round((100 * sources) / most.value),
        };
    }),
);

const requested = computed(() =>
    props.parts.requested.map(([start = 0, end = 0]) => ({ x: x(start), width: Math.max(0.5, x(end) - x(start)) })),
);

const share = computed(() => (props.parts.size > 0 ? Math.min(1, props.completed / props.parts.size) : 0));
</script>

<template>
    <div class="part-bar">
        <svg
            class="bar"
            :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
            preserveAspectRatio="none"
            role="img"
            :aria-label="t('IDS_DL_PROGRESS')"
        >
            <rect class="have" x="0" y="0" :width="WIDTH" :height="HEIGHT" />
            <rect
                v-for="(r, i) in missing"
                :key="`m${i}`"
                :class="r.kind"
                :x="r.x"
                y="0"
                :width="r.width"
                :height="HEIGHT"
                :style="
                    r.kind === 'available'
                        ? { fill: `color-mix(in srgb, var(--nj-bar-sources-max) ${r.mix}%, var(--nj-bar-sources-min))` }
                        : undefined
                "
            />
            <rect
                v-for="(r, i) in requested"
                :key="`r${i}`"
                class="requested"
                :x="r.x"
                y="0"
                :width="r.width"
                :height="HEIGHT"
            />
            <rect class="done" x="0" y="0" :width="share * WIDTH" :height="TOP_LINE" />
        </svg>
        <ul class="legend">
            <li><span class="swatch have" />{{ t('nj.legend.have') }}</li>
            <li><span class="swatch requested" />{{ t('nj.legend.requested') }}</li>
            <li><span class="swatch available" />{{ t('nj.legend.sources') }}</li>
            <li><span class="swatch none" />{{ t('nj.legend.noSource') }}</li>
            <li><span class="swatch done" />{{ t('nj.legend.done') }}</li>
        </ul>
    </div>
</template>

<style scoped>
.bar {
    display: block;
    width: 100%;
    height: 36px;
    border-radius: 3px;
}

.have {
    fill: var(--nj-bar-have);
    background: var(--nj-bar-have);
}

.available {
    background: var(--nj-bar-sources-max);
}

.none {
    fill: var(--nj-bar-missing);
    background: var(--nj-bar-missing);
}

.requested {
    fill: var(--nj-bar-requested);
    background: var(--nj-bar-requested);
}

.done {
    fill: var(--nj-bar-complete);
    background: var(--nj-bar-complete);
}

.legend {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    margin: 6px 0 0;
    padding: 0;
    list-style: none;
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.legend li {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

.swatch {
    width: 12px;
    height: 12px;
    border-radius: 2px;
}
</style>
