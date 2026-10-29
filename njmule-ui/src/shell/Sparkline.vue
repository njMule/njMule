<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import type { RateSample } from '../model/engine';

// The last half minute of the rates: the newest second at the right edge, the bottom for nothing.
const props = defineProps<{ rates: RateSample[] }>();

const SAMPLES = 30;
const WIDTH = 56;
const HEIGHT = 22;
// Half the stroke: the lines are not cut at the edges.
const INSET = 1;

const last = computed(() => props.rates.slice(-SAMPLES));
const top = computed(() => Math.max(1, ...last.value.map((s) => Math.max(s.down, s.up))));

function points(rate: (s: RateSample) => number): string {
    const w = WIDTH - 2 * INSET;
    const h = HEIGHT - 2 * INSET;
    const step = w / (SAMPLES - 1);
    const samples = last.value;
    return samples
        .map((s, i) => `${INSET + w - (samples.length - 1 - i) * step},${INSET + h - (h * rate(s)) / top.value}`)
        .join(' ');
}
</script>

<template>
    <svg class="sparkline" :width="WIDTH" :height="HEIGHT" :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" aria-hidden="true">
        <!-- Up first: the download line, the one more often watched, is drawn over it. -->
        <polyline class="up" :points="points((s) => s.up)" />
        <polyline class="down" :points="points((s) => s.down)" />
    </svg>
</template>

<style scoped>
.sparkline {
    direction: ltr;
    flex: none;
}

polyline {
    fill: none;
    stroke-width: 1.75;
    stroke-linejoin: round;
    stroke-linecap: round;
}

.down {
    stroke: var(--nj-graph-down);
}

.up {
    stroke: var(--nj-graph-up);
}
</style>
