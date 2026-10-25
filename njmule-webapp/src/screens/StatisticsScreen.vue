<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElSegmented } from 'element-plus';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { api } from '../api';
import { status } from '../engine';
import { count, duration, rate, size } from '../format';
import { t } from '../i18n';

const range = ref(600);
const RANGES = computed(() => [
    { value: 600, label: `10 ${t('IDS_MINS')}` },
    { value: 3600, label: `1 ${t('IDS_HOURS')}` },
    { value: 86400, label: `24 ${t('IDS_HOURS')}` },
]);

// The rates njMule sampled, however long this page was closed: a second each up to an hour, a minute each for a day.
const rates = ref<{ down: number; up: number }[]>([]);
const step = ref(1);
let timer = 0;

async function readRates(): Promise<void> {
    try {
        const answer = await api.get<{ step: number; rates: [number, number][] }>(`/rates?span=${range.value}`);
        step.value = answer.step;
        rates.value = answer.rates.map(([down, up]) => ({ down, up }));
    } catch {
        // The top bar tells when the engine does not answer.
    }
}

function follow(): void {
    window.clearInterval(timer);
    void readRates();
    timer = window.setInterval(() => void readRates(), range.value > 3600 ? 60000 : 2000);
}

watch(range, follow);
onMounted(follow);
onUnmounted(() => window.clearInterval(timer));

const WIDTH = 720;
const HEIGHT = 180;

const slots = computed(() => Math.round(range.value / step.value));
const shown = computed(() => rates.value.slice(-slots.value));
const highest = computed(() => Math.max(1024, ...shown.value.map((r) => Math.max(r.down, r.up))));

// The samples from the left edge on, newest at the right.
function line(pick: (r: { down: number; up: number }) => number): string {
    const width = WIDTH / Math.max(1, slots.value - 1);
    const start = slots.value - shown.value.length;
    return shown.value
        .map(
            (r, i) => `${((start + i) * width).toFixed(1)},${(HEIGHT - (pick(r) / highest.value) * HEIGHT).toFixed(1)}`,
        )
        .join(' ');
}

const summary = computed(() =>
    t(
        'nj.stats.graphSummary',
        RANGES.value.find((r) => r.value === range.value)?.label ?? '',
        rate(status.value?.downloadBytesPerSecond ?? 0),
        rate(status.value?.uploadBytesPerSecond ?? 0),
        rate(highest.value),
    ),
);

function ratio(up: number, down: number): string {
    return up > 0 ? `1 : ${(down / up).toFixed(2)}` : '-';
}
</script>

<template>
    <template v-if="status">
        <div class="tiles">
            <div class="tile">
                <span>{{ t('nj.stats.downRate') }}</span
                ><b>{{ rate(status.downloadBytesPerSecond) }}</b>
            </div>
            <div class="tile">
                <span>{{ t('nj.stats.upRate') }}</span
                ><b>{{ rate(status.uploadBytesPerSecond) }}</b>
            </div>
            <div class="tile">
                <span>{{ t('nj.stats.downloads') }}</span
                ><b>{{ count(status.downloads) }}</b>
            </div>
            <div class="tile">
                <span>{{ t('nj.transfers.uploading') }}</span
                ><b>{{ count(status.uploads) }}</b>
            </div>
            <div class="tile">
                <span>{{ t('IDS_ONQUEUE') }}</span
                ><b>{{ count(status.queued) }}</b>
            </div>
            <div class="tile">
                <span>{{ t('IDS_SHAREDFILES') }}</span
                ><b>{{ count(status.sharedFiles) }}</b>
            </div>
        </div>

        <section class="card">
            <div class="head">
                <h2>{{ t('nj.stats.rates') }}</h2>
                <span class="legend down">{{ t('IDS_PW_CON_DOWNLBL') }}</span>
                <span class="legend up">{{ t('IDS_PW_CON_UPLBL') }}</span>
                <span class="grow"></span>
                <el-segmented v-model="range" :options="RANGES" size="small" />
            </div>
            <svg :viewBox="`0 0 ${WIDTH} ${HEIGHT}`" class="graph" role="img" :aria-label="summary">
                <polyline class="down" :points="line((r) => r.down)" />
                <polyline class="up" :points="line((r) => r.up)" />
            </svg>
            <p class="hint">{{ summary }}</p>
        </section>

        <section v-if="status.freeSpace" class="card">
            <h2>{{ t('IDS_STATS_FREESPACE') }}</h2>
            <dl>
                <template v-if="status.freeSpace.incoming !== undefined">
                    <dt>{{ t('IDS_PW_INCOMING') }}</dt>
                    <dd>{{ size(status.freeSpace.incoming) }}</dd>
                </template>
                <template v-if="status.freeSpace.temp !== undefined">
                    <dt>{{ t('IDS_PW_TEMP') }}</dt>
                    <dd>{{ size(status.freeSpace.temp) }}</dd>
                </template>
            </dl>
        </section>

        <div class="cards">
            <section class="card">
                <h2>{{ t('IDS_STATS_SESSION') }}</h2>
                <dl>
                    <dt>{{ t('IDS_CD_DOWN') }}</dt>
                    <dd>{{ size(status.downloaded) }}</dd>
                    <dt>{{ t('IDS_CD_UP') }}</dt>
                    <dd>{{ size(status.uploaded) }}</dd>
                    <dt>{{ t('IDS_STATS_OVRHD') }}</dt>
                    <dd>
                        &darr; {{ size(status.overheadDownloaded) }} &middot; &uarr;
                        {{ size(status.overheadUploaded) }}
                    </dd>
                    <dt>UL:DL</dt>
                    <dd>{{ ratio(status.uploaded, status.downloaded) }}</dd>
                    <dt>{{ t('nj.stats.runtime') }}</dt>
                    <dd>{{ duration(status.sessionSeconds) }} / {{ duration(status.totalSeconds) }}</dd>
                </dl>
            </section>
            <section class="card">
                <h2>{{ t('IDS_STATS_CUMULATIVE') }}</h2>
                <dl>
                    <dt>{{ t('IDS_CD_TDOWN') }}</dt>
                    <dd>{{ size(status.totalDownloaded) }}</dd>
                    <dt>{{ t('IDS_CD_TUP') }}</dt>
                    <dd>{{ size(status.totalUploaded) }}</dd>
                    <dt>UL:DL</dt>
                    <dd>{{ ratio(status.totalUploaded, status.totalDownloaded) }}</dd>
                    <dt>{{ t('nj.stats.sessions') }}</dt>
                    <dd>{{ count(status.sessions) }}</dd>
                </dl>
            </section>
        </div>
    </template>
</template>

<style scoped>
.tiles {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 10px;
    margin-bottom: 16px;
}
.tile {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 10px 14px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.tile span {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.tile b {
    font-size: 18px;
    font-variant-numeric: tabular-nums;
}
.card {
    margin-bottom: 16px;
    padding: 12px 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.card h2 {
    margin: 0 0 8px;
    font-size: 15px;
}
.head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
}
.head h2 {
    margin: 0;
}
.legend::before {
    content: '';
    display: inline-block;
    width: 18px;
    height: 3px;
    margin-inline-end: 6px;
    vertical-align: middle;
}
.legend.down::before {
    background: var(--el-color-primary);
}
.legend.up::before {
    border-top: 3px dashed var(--el-color-success);
}
.grow {
    flex: 1;
}
.graph {
    width: 100%;
    height: 180px;
    margin-top: 10px;
    background: var(--nj-subtle);
    border-radius: 6px;
}
.graph polyline {
    fill: none;
    stroke-width: 2;
    vector-effect: non-scaling-stroke;
}
.graph .down {
    stroke: var(--el-color-primary);
}
.graph .up {
    stroke: var(--el-color-success);
    stroke-dasharray: 6 4;
}
.hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 16px;
}
dl {
    display: grid;
    grid-template-columns: max-content auto;
    gap: 4px 16px;
    margin: 0;
}
dt {
    color: var(--el-text-color-secondary);
}
dd {
    margin: 0;
    font-variant-numeric: tabular-nums;
}
</style>
