<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import type { Schemas } from '../api/client';
import { t } from '../i18n';
import { missingSpans } from '../transfers';

// The bar of a download, with eMule's meaning in njMule's colours: what we have, what is missing (bluer the more sources
// have it, red when none has), the blocks being requested, and a thin strip on top for the completed share. Drawn in
// pixels, so that a small gap still shows; the start of the file stays on the left in every language.

/** {@code text} tells the share and the parts for those who do not see the bar. */
const props = defineProps<{ parts: Schemas['PartMap']; completed: number; text?: string }>();

const HEIGHT = 22;
const STRIP = 3;

const svg = ref<SVGSVGElement | null>(null);
const width = ref(0);
let observer: ResizeObserver | null = null;

onMounted(() => {
    observer = new ResizeObserver(() => (width.value = svg.value?.clientWidth ?? 0));
    if (svg.value) {
        observer.observe(svg.value);
    }
});

onUnmounted(() => observer?.disconnect());

const scale = computed(() => (props.parts.size > 0 ? width.value / props.parts.size : 0));
const missing = computed(() => missingSpans(props.parts, width.value));
const requested = computed(() =>
    props.parts.requested.map(([start = 0, end = 0]) => ({
        x: start * scale.value,
        width: Math.max(1, (end - start) * scale.value),
    })),
);
const share = computed(() => (props.parts.size > 0 ? Math.min(1, props.completed / props.parts.size) : 0));
</script>

<template>
    <div class="part-bar">
        <svg
            ref="svg"
            class="bar"
            :viewBox="`0 0 ${Math.max(1, width)} ${HEIGHT}`"
            preserveAspectRatio="none"
            role="progressbar"
            :aria-label="t('IDS_DL_PROGRESS')"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="Math.round(share * 100)"
            :aria-valuetext="text"
        >
            <rect class="track" x="0" y="0" :width="width" :height="HEIGHT" />
            <template v-if="parts.size > 0">
                <rect class="have" x="0" :y="STRIP" :width="width" :height="HEIGHT - STRIP" />
                <rect
                    v-for="(r, i) in missing"
                    :key="`m${i}`"
                    :class="r.tone === null ? 'none' : 'available'"
                    :x="r.x"
                    :y="STRIP"
                    :width="r.width"
                    :height="HEIGHT - STRIP"
                    :style="
                        r.tone === null
                            ? undefined
                            : {
                                  fill: `color-mix(in srgb, var(--nj-bar-sources-max) ${r.tone}%, var(--nj-bar-sources-min))`,
                              }
                    "
                />
                <rect
                    v-for="(r, i) in requested"
                    :key="`r${i}`"
                    class="requested"
                    :x="r.x"
                    :y="STRIP"
                    :width="r.width"
                    :height="HEIGHT - STRIP"
                />
                <rect class="done" x="0" y="0" :width="share * width" :height="STRIP" />
            </template>
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
.part-bar {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.bar {
    display: block;
    width: 100%;
    height: 22px;
    direction: ltr;
}

.track {
    fill: var(--nj-bar-bg);
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

/* What each colour means, flowing over as many lines as the pane needs: the words are there, not only the colours. */
.legend {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    margin: 0;
    padding: 0;
    list-style: none;
    color: var(--color-fg-muted);
    font-size: 0.85em;
}

.legend li {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

.swatch {
    width: 10px;
    height: 10px;
    border-radius: 2px;
}
</style>
