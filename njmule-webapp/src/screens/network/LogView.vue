<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { api } from '../../api';
import NjIcon from '../../components/NjIcon.vue';
import { language, t } from '../../i18n';
import type { IconName } from '../../icons';

interface Line {
    sequence: number;
    time: string;
    level: 'debug' | 'info' | 'success' | 'warning' | 'error';
    message: string;
}

type Level = 'all' | 'error' | 'warning' | 'info';

// The lines of this page, newest last as they came; the engine keeps the last ones only.
const lines = ref<Line[]>([]);
const level = ref<Level>('all');
const MAX = 5000;

const LEVELS: { value: Level; key: string }[] = [
    { value: 'all', key: 'nj.all' },
    { value: 'error', key: 'IDS_ERROR' },
    { value: 'warning', key: 'IDS_WARNING' },
    { value: 'info', key: 'IDS_INFO' },
];

const ICONS: Partial<Record<Line['level'], IconName>> = { error: 'ERROR', warning: 'WARNING', success: 'CHECK_CIRCLE' };

function levelOf(l: Line): Level {
    return l.level === 'error' ? 'error' : l.level === 'warning' ? 'warning' : 'info';
}

const counts = computed(() => {
    const all = new Map<Level, number>([['all', lines.value.length]]);
    for (const l of lines.value) {
        all.set(levelOf(l), (all.get(levelOf(l)) ?? 0) + 1);
    }
    return all;
});

const shown = computed(() =>
    lines.value
        .filter((l) => level.value === 'all' || levelOf(l) === level.value)
        .slice()
        .reverse(),
);

function when(time: string): string {
    return new Date(time).toLocaleString(language.value);
}

async function read(): Promise<void> {
    const after = lines.value.at(-1)?.sequence ?? 0;
    try {
        const fresh = await api.get<Line[]>(`/log?after=${after}`);
        if (fresh.length > 0) {
            lines.value = [...lines.value, ...fresh].slice(-MAX);
        }
    } catch {
        // The top bar tells when the engine does not answer.
    }
}

let timer = 0;
onMounted(() => {
    void read();
    timer = window.setInterval(() => void read(), 2000);
});
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <div class="chips" role="radiogroup">
        <button
            v-for="l in LEVELS"
            :key="l.value"
            type="button"
            role="radio"
            :aria-checked="level === l.value"
            class="nj-chip"
            :class="{ busy: level === l.value }"
            @click="level = l.value"
        >
            {{ t(l.key) }} <b>{{ counts.get(l.value) ?? 0 }}</b>
        </button>
    </div>
    <ol class="log">
        <li v-for="l in shown" :key="l.sequence" :class="l.level">
            <NjIcon v-if="ICONS[l.level]" :name="ICONS[l.level]!" />
            <time>{{ when(l.time) }}</time>
            <span>{{ l.message }}</span>
        </li>
    </ol>
</template>

<style scoped>
.chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 12px;
}
.chips button {
    border: 0;
    cursor: pointer;
    font: inherit;
    font-size: 13px;
}
.log {
    max-height: 70vh;
    overflow-y: auto;
    margin: 0;
    padding: 8px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
    list-style: none;
    font-family: ui-monospace, monospace;
    font-size: 12.5px;
}
.log li {
    display: flex;
    align-items: baseline;
    gap: 8px;
    padding: 1px 0;
    user-select: text;
}
.log .nj-icon {
    width: 14px;
    height: 14px;
    align-self: center;
}
.log time {
    flex: none;
    color: var(--el-text-color-secondary);
}
.log .error {
    color: var(--nj-error-fg);
}
.log .warning {
    color: var(--nj-warn-fg);
}
.log .success {
    color: var(--nj-ok-fg);
}
</style>
