<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, shallowRef, watch } from 'vue';
import { answer, api } from '../api/client';
import { count, duration, rate, size } from '../format';
import { t } from '../i18n';
import {
    completedCount,
    preferences,
    type RateSample,
    servers,
    shared,
    sources,
    status,
    uploads,
} from '../model/engine';
import { peaks } from '../peaks';
import { statsTree } from '../stats';
import Icon from '../ui/Icon.vue';
import RateGraph from '../ui/RateGraph.vue';
import Segments from '../ui/Segments.vue';
import StatsTree from './statistics/StatsTree.vue';

// What happens now first, then the rates over time, then the session and all sessions, then every figure, closed
// until wanted: the cards above say the main ones.

type Span = '600' | '3600' | '86400';

const span = ref<Span>('600');
const samples = shallowRef<RateSample[]>([]);
const step = ref(1);
const newest = ref(Date.now());

// In the units of the language: 10 mins, 1 h, 24 h.
function spanName(seconds: number): string {
    return seconds < 3600 ? `${seconds / 60} ${t('IDS_MINS')}` : `${seconds / 3600} ${t('IDS_HOURS')}`;
}

const SPANS = computed(() =>
    (['600', '3600', '86400'] as const).map((value) => ({ value, title: spanName(Number(value)) })),
);

// The rates the engine sampled, however long this window was closed: a second each up to an hour, a minute each for a
// day.
async function readRates(): Promise<void> {
    try {
        const history = await answer(
            api().GET('/rates', { params: { query: { span: Number(span.value) as 600 | 3600 | 86400 } } }),
        );
        step.value = history.step;
        samples.value = history.rates.map(([down, up]) => ({ down: down ?? 0, up: up ?? 0 }));
        newest.value = Date.now();
    } catch {
        // The top bar tells when the engine does not answer.
    }
}

let timer = 0;

function follow(): void {
    window.clearInterval(timer);
    void readRates();
    const every = { '600': 1000, '3600': 2000, '86400': 60_000 }[span.value];
    timer = window.setInterval(() => void readRates(), every);
}

watch(span, follow);
onActivated(follow);
onDeactivated(() => window.clearInterval(timer));
onUnmounted(() => window.clearInterval(timer));

const graphLabel = computed(() => {
    const shown = samples.value.slice(-Math.round(Number(span.value) / step.value));
    const last = shown.at(-1) ?? { down: 0, up: 0 };
    return t(
        'nj.stats.graphSummary',
        spanName(Number(span.value)),
        rate(last.down),
        rate(last.up),
        rate(shown.reduce((m, s) => Math.max(m, s.down, s.up), 0)),
    );
});

const tiles = computed(() => {
    const s = status.value;
    return [
        { name: t('nj.stats.downRate'), value: s ? rate(s.downloadBytesPerSecond) : '-' },
        { name: t('nj.stats.upRate'), value: s ? rate(s.uploadBytesPerSecond) : '-' },
        { name: t('nj.stats.downloads'), value: s ? count(s.downloads) : '-' },
        { name: t('nj.transfers.uploading'), value: s ? count(s.uploads) : '-' },
        { name: t('IDS_ONQUEUE'), value: s ? count(s.queued) : '-' },
        { name: t('IDS_SHAREDFILES'), value: s ? count(s.sharedFiles) : '-' },
    ];
});

const totals = computed(() => {
    const s = status.value;
    return [
        {
            title: t('IDS_STATS_SESSION'),
            facts: [
                [t('IDS_CD_DOWN'), s ? size(s.downloaded) : '-'],
                [t('IDS_CD_UP'), s ? size(s.uploaded) : '-'],
                [t('nj.stats.runtime'), s ? `${duration(s.sessionSeconds)} / ${duration(s.totalSeconds)}` : '-'],
            ],
        },
        {
            title: t('IDS_STATS_CUMULATIVE'),
            facts: [
                [t('IDS_CD_TDOWN'), s ? size(s.totalDownloaded) : '-'],
                [t('IDS_CD_TUP'), s ? size(s.totalUploaded) : '-'],
                [t('nj.stats.sessions'), s ? count(s.sessions) : '-'],
            ],
        },
    ];
});

const tree = computed(() => {
    const s = status.value;
    if (s === null) {
        return [];
    }
    return statsTree({
        status: s,
        sources: sources.value,
        uploads: uploads.value,
        servers: servers.value,
        shared: shared.value,
        completed: completedCount.value,
        maxDown: peaks.value.down,
        maxUp: peaks.value.up,
        dirs: { temp: preferences.value['dirs.temp'] ?? '', incoming: preferences.value['dirs.incoming'] ?? '' },
    });
});
</script>

<template>
    <div class="screen">
        <div class="tiles">
            <div v-for="tile in tiles" :key="tile.name" class="stat-card">
                <span class="stat-name">{{ tile.name }}</span>
                <strong class="stat-value">{{ tile.value }}</strong>
            </div>
        </div>
        <section class="stats-card">
            <div class="rates-head">
                <strong>{{ t('nj.stats.rates') }}</strong>
                <span class="legend">
                    <span class="swatch down" />{{ t('IDS_PW_CON_DOWNLBL') }}<span class="swatch up" />{{
                        t('IDS_PW_CON_UPLBL')
                    }}
                </span>
                <Segments v-model="span" :items="SPANS" :label="t('nj.stats.rates')" />
            </div>
            <RateGraph
                :samples="samples"
                :step="step"
                :span="Number(span)"
                :newest="newest"
                :empty="t('nj.stats.noData')"
                :label="graphLabel"
            />
        </section>
        <div class="totals">
            <section v-for="card in totals" :key="card.title" class="stats-card">
                <strong>{{ card.title }}</strong>
                <dl>
                    <template v-for="[name, value] in card.facts" :key="name">
                        <dt class="muted">{{ name }}</dt>
                        <dd>{{ value }}</dd>
                    </template>
                </dl>
            </section>
        </div>
        <details class="all">
            <summary><Icon name="CHEVRON_RIGHT" class="chevron" />{{ t('nj.stats.all') }}</summary>
            <StatsTree :entries="tree" :label="t('nj.stats.all')" />
        </details>
    </div>
</template>

<style scoped>
.screen {
    display: flex;
    flex-direction: column;
    gap: 14px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
    overflow-y: auto;
}

.tiles {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
}

.stat-card {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 170px;
    padding: 10px 14px;
    line-height: 1.4;
    border-radius: 8px;
    background: var(--color-bg-subtle);
}

.stat-name {
    color: var(--color-fg-muted);
}

.stat-value {
    font-size: 1.3em;
}

/* The cards of the statistics: a heading, then a graph or named figures. */
.stats-card {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 12px 16px 14px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
}

.rates-head {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 16px;
}

.legend {
    display: inline-flex;
    align-items: center;
    gap: 8px;
}

.swatch {
    width: 14px;
    height: 4px;
}

.swatch.down {
    background: var(--nj-graph-down);
}

.swatch.up {
    background: var(--nj-graph-up);
}

/* The session and all sessions side by side, as wide as the wider. */
.totals {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
    width: fit-content;
    max-width: 100%;
}

dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 6px 16px;
    margin: 0;
}

dd {
    margin: 0;
    font-weight: bold;
}

.all {
    border: 1px solid var(--color-border-default);
}

.all summary {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 5px 10px;
    list-style: none;
    cursor: pointer;
    font-size: 1.05em;
}

.all summary::-webkit-details-marker {
    display: none;
}

.all[open] summary .chevron {
    transform: rotate(90deg);
}

.all:not([open]) summary .chevron:dir(rtl) {
    transform: scaleX(-1);
}
</style>
