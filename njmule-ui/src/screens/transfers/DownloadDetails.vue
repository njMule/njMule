<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import { duration, percent, rate, size } from '../../format';
import { t } from '../../i18n';
import { categories, sources, type Download } from '../../model/engine';
import {
    bySending,
    kindIcon,
    pausable,
    priorityKey,
    remaining,
    resumable,
    sourceDot,
    sourceShown,
    SOURCE_STATES,
    stoppable,
} from '../../transfers';
import { copyLinks, each } from '../../transfers-actions';
import Icon from '../../ui/Icon.vue';
import PartBar from '../../ui/PartBar.vue';

// The details of the chosen download under the list: what it is, how far it got and its map of parts, its buttons;
// beside them, its sources and facts in tabs.

const props = defineProps<{ chosen: Download[] }>();
const emit = defineEmits<{ close: [] }>();

type Tab = 'sources' | 'facts';
const tab = ref<Tab>('sources');
const parts = shallowRef<Schemas['PartMap'] | null>(null);

const one = computed(() => (props.chosen.length === 1 ? props.chosen[0]! : null));
const itsSources = computed(() =>
    one.value === null ? [] : bySending(sources.value.filter((s) => s.file === one.value!.hash)),
);

// The map of parts, read again with each second's news of the download.
watch(
    () => [one.value?.hash, one.value?.completed],
    async () => {
        const d = one.value;
        if (d === null) {
            parts.value = null;
            return;
        }
        try {
            parts.value = (
                await answer(api().GET('/downloads/{hash}/parts', { params: { path: { hash: d.hash } } }))
            ).parts;
        } catch {
            parts.value = null;
        }
    },
    { immediate: true },
);

const facts = computed(() => {
    const d = one.value;
    if (d === null) {
        return [];
    }
    return [
        [t('IDS_DL_FILENAME'), d.name],
        [t('IDS_DL_SIZE'), size(d.size)],
        [t('IDS_PRIORITY'), t(priorityKey(d))],
        [t('IDS_CAT'), categories.value.find((c) => c.id === d.category)?.name ?? '—'],
        [t('IDS_DL_SOURCES'), `${d.transferring}/${d.sources}`],
        ['eD2K', d.link],
    ];
});
</script>

<template>
    <section class="download-details">
        <div v-if="one === null" class="details-none">
            {{ chosen.length === 0 ? t('nj.details.none') : t('nj.details.chosen', chosen.length) }}
        </div>
        <template v-else>
            <div class="details-head">
                <div class="details-title">
                    <span class="kind-box"><Icon :name="kindIcon(one.kind)" /></span>
                    <div class="details-name">
                        <strong class="ellipsis" dir="auto">{{ one.name }}</strong>
                        <span class="muted">{{
                            [size(one.size), categories.find((c) => c.id === one!.category)?.name, t(priorityKey(one))]
                                .filter((x) => x)
                                .join(' · ')
                        }}</span>
                    </div>
                </div>
                <div class="details-progress">
                    <span class="big-percent display">{{ percent(one.completed, one.size) }}</span>
                    <span class="details-figures muted">
                        <span>{{ t('nj.details.doneOf', size(one.completed), size(one.size)) }}</span>
                        <span v-if="one.bytesPerSecond > 0"
                            >↓ {{ rate(one.bytesPerSecond) }} ·
                            {{ remaining(one) < 0 ? '—' : duration(remaining(one)) }}</span
                        >
                    </span>
                </div>
                <PartBar v-if="parts !== null" :parts="parts" :completed="one.completed" />
                <div class="details-actions">
                    <button v-if="pausable(one)" class="button" @click="each('/downloads/{hash}/pause', [one])">
                        <Icon name="PAUSE" />{{ t('IDS_DL_PAUSE') }}
                    </button>
                    <button v-if="resumable(one)" class="button" @click="each('/downloads/{hash}/resume', [one])">
                        <Icon name="PLAY" />{{ t('IDS_DL_RESUME') }}
                    </button>
                    <button v-if="stoppable(one)" class="button" @click="each('/downloads/{hash}/stop', [one])">
                        <Icon name="STOP" />{{ t('IDS_DL_STOP') }}
                    </button>
                    <div class="spacer" />
                    <button class="button icon-only" :aria-label="t('nj.copyLink')" @click="copyLinks([one])">
                        <Icon name="COPY" />
                    </button>
                </div>
            </div>
            <div class="details-tabs">
                <div class="tab-row" role="tablist">
                    <button class="tab" role="tab" :aria-selected="tab === 'sources'" @click="tab = 'sources'">
                        {{ t('IDS_DL_SOURCES') }}<span class="tab-count">{{ itsSources.length }}</span>
                    </button>
                    <button class="tab" role="tab" :aria-selected="tab === 'facts'" @click="tab = 'facts'">
                        {{ t('IDS_DETAILS') }}
                    </button>
                    <div class="spacer" />
                    <button class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')" @click="emit('close')">
                        <Icon name="CLOSE" />
                    </button>
                </div>
                <div v-if="tab === 'sources'" class="source-list">
                    <div v-if="itsSources.length === 0" class="muted pad">{{ t('nj.transfers.noSources') }}</div>
                    <div v-for="s in itsSources" :key="s.address" class="source-item">
                        <div class="source-top">
                            <strong>{{ s.nick ?? s.address }}</strong>
                            <span class="muted">{{ s.software }}</span>
                            <span v-if="sourceShown(s) === 'queued' && s.queueRank > 0" class="queue-rank"
                                >{{ t('IDS_QR') }} {{ s.queueRank }}</span
                            >
                            <span v-else-if="s.received > 0" class="queue-rank">{{ size(s.received) }}</span>
                        </div>
                        <div class="source-bottom">
                            <span class="source-dot" :class="sourceDot(sourceShown(s))">{{
                                sourceDot(sourceShown(s)) === 'unknown' ? '?' : ''
                            }}</span>
                            <span>{{ t(SOURCE_STATES[sourceShown(s)]) }}</span>
                            <span class="spacer" />
                            <span class="thin-bar mini"
                                ><span
                                    class="thin-bar-fill accent"
                                    :style="{
                                        width: `${s.partCount === 0 ? 0 : (s.parts * 100) / s.partCount}%`,
                                        display: 'block',
                                    }"
                            /></span>
                        </div>
                    </div>
                </div>
                <dl v-else class="facts">
                    <template v-for="[name, value] in facts" :key="name">
                        <dt class="muted">{{ name }}</dt>
                        <dd class="ellipsis" dir="auto">{{ value }}</dd>
                    </template>
                </dl>
            </div>
        </template>
    </section>
</template>

<style scoped>
.download-details {
    display: flex;
    flex: none;
    height: 300px;
    border: 1px solid var(--color-border-default);
    border-radius: 8px;
    background: var(--color-bg-default);
    overflow: hidden;
}

.details-none {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--color-fg-muted);
}

.details-head {
    display: flex;
    flex-direction: column;
    gap: 8px;
    width: 50%;
    min-width: 0;
    padding: 16px 24px;
    border-inline-end: 1px solid var(--color-border-muted);
}

.details-title {
    display: flex;
    align-items: center;
    gap: 16px;
    min-width: 0;
}

.kind-box {
    display: inline-flex;
    padding: 12px;
    border-radius: 8px;
    background: var(--color-bg-subtle);
    --nj-icon-size: 22px;
    --nj-icon-color: var(--color-fg-muted);
}

.details-name {
    display: flex;
    flex-direction: column;
    min-width: 0;
}

.details-progress {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
}

.big-percent {
    font-size: 2.2em;
    line-height: 1;
}

.details-figures {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
}

.details-actions {
    display: flex;
    gap: 8px;
    margin-top: auto;
}

.spacer {
    flex: 1;
}

.details-tabs {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
}

.tab-row {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px 0 16px;
    border-bottom: 1px solid var(--color-border-muted);
}

.tab {
    display: inline-flex;
    gap: 8px;
    padding: 8px 12px;
    border: 0;
    border-bottom: 2px solid transparent;
    background: transparent;
    color: var(--color-fg-muted);
    cursor: pointer;
}

.tab[aria-selected='true'] {
    color: var(--color-fg-default);
    border-bottom-color: var(--color-accent-emphasis);
    font-weight: bold;
}

.tab-count {
    color: var(--color-fg-muted);
}

.source-list {
    flex: 1;
    overflow-y: auto;
}

.source-item {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 10px 16px;
    border-bottom: 1px solid var(--color-border-muted);
}

.source-top,
.source-bottom {
    display: flex;
    align-items: center;
    gap: 10px;
}

.queue-rank {
    margin-inline-start: auto;
    font-weight: bold;
}

.thin-bar.mini {
    flex: none;
    width: 160px;
}

.facts {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 8px 16px;
    margin: 0;
    padding: 16px;
    overflow-y: auto;
}

.facts dd {
    margin: 0;
}

.pad {
    padding: 16px;
}
</style>
