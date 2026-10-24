<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { t } from '../i18n';
import type { Parts } from '../types';

const props = defineProps<{ parts: Parts }>();

const PART = 9_728_000;
const WIDTH = 1000;

interface Rect {
    x: number;
    width: number;
    kind: string;
    opacity?: number;
}

function x(offset: number): number {
    return props.parts.size > 0 ? (offset / props.parts.size) * WIDTH : 0;
}

// Missing bytes in the tint of their part's sources, bluer the more have it; what is asked for over them.
const rects = computed<Rect[]>(() => {
    const most = Math.max(1, ...props.parts.sources);
    const missing = props.parts.missing.map(([start, end]) => {
        const sources = props.parts.sources[Math.floor(start / PART)] ?? 0;
        return {
            x: x(start),
            width: Math.max(0.5, x(end) - x(start)),
            kind: sources === 0 ? 'none' : 'available',
            opacity: sources === 0 ? 1 : 0.35 + (0.65 * sources) / most,
        };
    });
    const requested = props.parts.requested.map(([start, end]) => ({
        x: x(start),
        width: Math.max(0.5, x(end) - x(start)),
        kind: 'requested',
    }));
    return [...missing, ...requested];
});

const have = computed(() => {
    const missing = props.parts.missing.reduce((sum, [start, end]) => sum + (end - start), 0);
    return props.parts.size - missing;
});
</script>

<template>
    <svg
        class="bar"
        :viewBox="`0 0 ${WIDTH} 14`"
        preserveAspectRatio="none"
        role="img"
        :aria-label="`${parts.size > 0 ? Math.floor((have * 100) / parts.size) : 0}%`"
    >
        <rect class="have" x="0" y="0" :width="WIDTH" height="14" />
        <rect
            v-for="(r, i) in rects"
            :key="i"
            :class="r.kind"
            :x="r.x"
            y="0"
            :width="r.width"
            height="14"
            :opacity="r.opacity"
        />
    </svg>
    <ul class="legend">
        <li><span class="have"></span>{{ t('nj.legend.have') }}</li>
        <li><span class="requested"></span>{{ t('nj.legend.requested') }}</li>
        <li><span class="available"></span>{{ t('nj.legend.sources') }}</li>
        <li><span class="none"></span>{{ t('nj.legend.noSource') }}</li>
    </ul>
</template>

<style scoped>
.bar {
    display: block;
    width: 100%;
    height: 14px;
    border-radius: 3px;
}
.have {
    fill: var(--nj-have);
    background: var(--nj-have);
}
.requested {
    fill: var(--nj-bar-warn);
    background: var(--nj-bar-warn);
}
.available {
    fill: var(--nj-available);
    background: var(--nj-available);
}
.none {
    fill: var(--nj-missing);
    background: var(--nj-missing);
}
.legend {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 14px;
    margin: 6px 0 0;
    padding: 0;
    list-style: none;
    color: var(--el-text-color-secondary);
    font-size: 12px;
}
.legend span {
    display: inline-block;
    width: 10px;
    height: 10px;
    margin-inline-end: 5px;
    border-radius: 2px;
    vertical-align: middle;
}
</style>
