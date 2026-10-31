<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { rate, shortTime } from '../format';
import type { RateSample } from '../model/engine';
import { scaleTop } from '../stats';

// The download and upload rates over time as two filled lines, the newest at the right edge, under a scale up to the
// highest rate shown and over the times of its width; under the pointer, the rates of that moment; a word while
// nothing moved. Time runs to the right in every language.

const props = defineProps<{
    samples: RateSample[];
    /** The seconds between two samples and the seconds shown. */
    step: number;
    span: number;
    /** When the newest sample was taken, in milliseconds. */
    newest: number;
    /** The word shown while nothing moved. */
    empty: string;
    /** The graph in words, for those who cannot see it. */
    label: string;
}>();

// The band under the lines that holds the times, and how many times it shows.
const AXIS = 18;
const TICKS = 5;

const box = ref<HTMLElement | null>(null);
const width = ref(0);
const height = ref(0);
const pointer = ref(-1);
let observer: ResizeObserver | null = null;

onMounted(() => {
    observer = new ResizeObserver(() => {
        width.value = box.value?.clientWidth ?? 0;
        height.value = box.value?.clientHeight ?? 0;
    });
    if (box.value) {
        observer.observe(box.value);
    }
});
onUnmounted(() => observer?.disconnect());

const slots = computed(() => Math.max(1, Math.round(props.span / props.step)));
const shown = computed(() => props.samples.slice(-slots.value));
const top = computed(() => scaleTop(shown.value.reduce((m, s) => Math.max(m, s.down, s.up), 1024)));
const plot = computed(() => Math.max(0, height.value - AXIS));
const stepWidth = computed(() => width.value / Math.max(1, slots.value - 1));
const quiet = computed(() => shown.value.every((s) => s.down === 0 && s.up === 0));

const grid = computed(() => [1, 2, 3, 4].map((i) => Math.round((plot.value * i) / 4) + 0.5));

// A line and the area under it; samples closer than a pixel are drawn as their mean, one point per pixel.
function paths(pick: (s: RateSample) => number): { line: string; area: string } {
    const all = shown.value;
    if (all.length === 0 || width.value < 2 || plot.value < 2) {
        return { line: '', area: '' };
    }
    const h = plot.value;
    const step = stepWidth.value;
    const x0 = width.value - (all.length - 1) * step;
    const perPoint = Math.max(1, Math.ceil(1 / step));
    const points: string[] = [];
    for (let first = 0; first < all.length; first += perPoint) {
        const last = Math.min(all.length, first + perPoint);
        let sum = 0;
        for (let i = first; i < last; i++) {
            sum += pick(all[i]!);
        }
        const x = x0 + (last - 1) * step;
        const y = h - (sum / (last - first) / top.value) * (h - 4);
        points.push(`${x.toFixed(1)},${y.toFixed(1)}`);
    }
    const line = `M${points.join('L')}`;
    const end = points.at(-1)!.split(',')[0];
    return { line, area: `${line}L${end},${h}L${points[0]!.split(',')[0]},${h}Z` };
}

const down = computed(() => paths((s) => s.down));
const up = computed(() => paths((s) => s.up));

const times = computed(() =>
    Array.from({ length: TICKS }, (_, i) => {
        const back = (props.span * (TICKS - 1 - i)) / (TICKS - 1);
        return shortTime(new Date(props.newest - back * 1000));
    }),
);

// The sample under the pointer, its time and rates beside a mark, on the side with room: after it while it fits.
const readout = ref<HTMLElement | null>(null);
const readoutWidth = ref(0);
const pointed = computed(() => {
    const all = shown.value;
    if (pointer.value < 0 || all.length === 0) {
        return null;
    }
    const index = all.length - 1 - Math.round((width.value - pointer.value) / stepWidth.value);
    if (index < 0 || index >= all.length) {
        return null;
    }
    const sample = all[index]!;
    const x = Math.round(width.value - (all.length - 1 - index) * stepWidth.value) + 0.5;
    const when = new Date(props.newest - (all.length - 1 - index) * props.step * 1000);
    const left = x + 8 + readoutWidth.value <= width.value ? x + 8 : Math.max(0, x - 8 - readoutWidth.value);
    return { x, left, text: `${shortTime(when)}  ↓ ${rate(sample.down)}  ↑ ${rate(sample.up)}` };
});

watch(
    () => pointed.value?.text,
    () => (readoutWidth.value = readout.value?.offsetWidth ?? 0),
    { flush: 'post' },
);

// Measured from the graph itself: over a word of the scale or a time, the pointer's own offset is the word's.
function moved(e: MouseEvent): void {
    const graph = box.value;
    pointer.value = graph === null ? -1 : e.clientX - graph.getBoundingClientRect().left - graph.clientLeft;
}
</script>

<template>
    <div
        ref="box"
        class="rate-graph"
        role="img"
        :aria-label="quiet ? empty : label"
        dir="ltr"
        @mousemove="moved"
        @mouseleave="pointer = -1"
    >
        <svg :width="width" :height="plot" aria-hidden="true">
            <line v-for="y in grid" :key="y" class="grid" x1="0" :x2="width" :y1="y" :y2="y" />
            <path class="area down" :d="down.area" />
            <path class="line down" :d="down.line" />
            <path class="area up" :d="up.area" />
            <path class="line up" :d="up.line" />
            <line v-if="pointed" class="grid" :x1="pointed.x" :x2="pointed.x" y1="0" :y2="plot" />
        </svg>
        <span class="scale" style="top: 2px">{{ rate(top) }}</span>
        <span class="scale" :style="{ top: `${Math.round(plot / 2) + 2}px` }">{{ rate(top / 2) }}</span>
        <span
            v-for="(time, i) in times"
            :key="i"
            class="time"
            :class="{ first: i === 0, last: i === TICKS - 1 }"
            :style="i === TICKS - 1 ? { right: '0px' } : { left: `${(width * i) / (TICKS - 1)}px` }"
            >{{ time }}</span
        >
        <span v-if="quiet" class="empty" :style="{ top: `${plot / 2}px` }">{{ empty }}</span>
        <span v-if="pointed" ref="readout" class="pointed" :style="{ left: `${pointed.left}px` }">{{
            pointed.text
        }}</span>
    </div>
</template>

<style scoped>
.rate-graph {
    position: relative;
    height: 260px;
    min-height: 160px;
    overflow: hidden;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-subtle);
}

svg {
    position: absolute;
    inset: 0 auto auto 0;
}

.grid {
    stroke: var(--nj-graph-grid);
    stroke-width: 1;
}

.line {
    fill: none;
    stroke-width: 1.5;
    stroke-linejoin: round;
}

.area {
    fill-opacity: 0.25;
}

.down {
    stroke: var(--nj-graph-down);
    fill: var(--nj-graph-down);
}

.up {
    stroke: var(--nj-graph-up);
    fill: var(--nj-graph-up);
}

.line.down,
.line.up {
    fill: none;
}

.area.down,
.area.up {
    stroke: none;
}

/* The scale and the times: small and muted, on the plot's own background. */
.scale,
.time {
    position: absolute;
    color: var(--color-fg-muted);
    font-size: 0.8em;
    white-space: nowrap;
}

.scale {
    left: 6px;
}

.time {
    bottom: 1px;
    transform: translateX(-50%);
}

.time.first {
    transform: none;
}

.time.last {
    transform: none;
}

.empty {
    position: absolute;
    left: 50%;
    padding: 6px 10px;
    border-radius: 6px;
    background: var(--color-bg-default);
    color: var(--color-fg-muted);
    transform: translate(-50%, -50%);
}

/* The time and rates under the pointer, on a card of the theme. */
.pointed {
    position: absolute;
    top: 4px;
    padding: 2px 6px;
    border: 1px solid var(--color-border-default);
    border-radius: 4px;
    background: var(--color-bg-default);
    font-size: 0.85em;
    white-space: pre;
    pointer-events: none;
}
</style>
