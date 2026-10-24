<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    ElButton,
    ElCheckbox,
    ElCheckboxGroup,
    ElInput,
    ElInputNumber,
    ElOption,
    ElSelect,
    ElTable,
    ElTableColumn,
    ElTimeSelect,
} from 'element-plus';
import { onMounted, ref } from 'vue';
import { api } from '../api';
import { usePoll } from '../engine';
import { language, t } from '../i18n';
import { attempt } from '../notify';
import type { Category } from '../types';

interface Rule {
    name: string;
    enabled: boolean;
    days: string[];
    start: string;
    end: string;
    uploadKiBps?: number;
    downloadKiBps?: number;
    maxConnections?: number;
    maxSourcesPerFile?: number;
    stopCategory?: number;
    resumeCategory?: number;
}

const DAYS = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];

const rules = ref<Rule[]>([]);
const active = ref<string | undefined>();
const chosen = ref<number | null>(null);
const form = ref<Rule>(empty());
const categories = usePoll(() => api.get<Category[]>('/categories'), 60000, [] as Category[]);

function empty(): Rule {
    return { name: '', enabled: true, days: [...DAYS], start: '00:00', end: '00:00' };
}

// The short name of a day in the language of the page.
function day(name: string): string {
    const monday = new Date(Date.UTC(2024, 0, 1 + DAYS.indexOf(name)));
    return monday.toLocaleDateString(language.value, { weekday: 'short', timeZone: 'UTC' });
}

async function load(): Promise<void> {
    await attempt(async () => {
        const s = await api.get<{ rules: Rule[]; active?: string }>('/schedule');
        rules.value = s.rules;
        active.value = s.active;
    });
}

onMounted(load);

async function write(next: Rule[]): Promise<void> {
    if (await attempt(() => api.put('/schedule', { rules: next }))) {
        chosen.value = null;
        form.value = empty();
        await load();
    }
}

function choose(row: Rule): void {
    chosen.value = rules.value.indexOf(row);
    form.value = { ...row, days: [...row.days] };
}

// A value left empty leaves the preference as it is.
function cleaned(r: Rule): Rule {
    const out: Rule = { name: r.name, enabled: r.enabled, days: r.days, start: r.start, end: r.end };
    for (const key of [
        'uploadKiBps',
        'downloadKiBps',
        'maxConnections',
        'maxSourcesPerFile',
        'stopCategory',
        'resumeCategory',
    ] as const) {
        const value = r[key];
        if (value !== undefined && value !== null) {
            out[key] = value;
        }
    }
    return out;
}

function limit(kib?: number): string {
    return kib === undefined ? '' : kib === 0 ? t('nj.rate.noLimit') : `${kib} KB/s`;
}
</script>

<template>
    <p class="hint">{{ active ? t('nj.scheduler.active', active) : t('nj.scheduler.inactive') }}</p>
    <el-table
        :data="rules"
        :empty-text="t('nj.scheduler.none')"
        highlight-current-row
        @current-change="(row: Rule | null) => row && choose(row)"
    >
        <el-table-column :label="t('nj.scheduler.name')" prop="name" min-width="120" />
        <el-table-column :label="t('nj.scheduler.days')" min-width="160">
            <template #default="{ row }">{{ (row as Rule).days.map(day).join(' ') }}</template>
        </el-table-column>
        <el-table-column :label="t('nj.scheduler.from')" prop="start" width="80" />
        <el-table-column :label="t('nj.scheduler.to')" prop="end" width="80" />
        <el-table-column :label="t('nj.settings.upload')" width="120">
            <template #default="{ row }">{{ limit(row.uploadKiBps) }}</template>
        </el-table-column>
        <el-table-column :label="t('nj.settings.download')" width="120">
            <template #default="{ row }">{{ limit(row.downloadKiBps) }}</template>
        </el-table-column>
        <el-table-column :label="t('nj.scheduler.enabled')" width="60">
            <template #default="{ row }">{{ row.enabled ? '✓' : '' }}</template>
        </el-table-column>
    </el-table>

    <form class="form" @submit.prevent>
        <label>{{ t('nj.scheduler.name') }}<el-input v-model="form.name" maxlength="100" /></label>
        <el-checkbox v-model="form.enabled">{{ t('nj.scheduler.enabled') }}</el-checkbox>
        <label
            >{{ t('nj.scheduler.days') }}
            <el-checkbox-group v-model="form.days">
                <el-checkbox v-for="d in DAYS" :key="d" :value="d">{{ day(d) }}</el-checkbox>
            </el-checkbox-group>
        </label>
        <div class="times">
            <label
                >{{ t('nj.scheduler.from')
                }}<el-time-select v-model="form.start" start="00:00" step="00:15" end="23:45"
            /></label>
            <label
                >{{ t('nj.scheduler.to') }}<el-time-select v-model="form.end" start="00:00" step="00:15" end="23:45"
            /></label>
        </div>
        <div class="numbers">
            <label
                >{{ t('nj.settings.upload') }} (KB/s)<el-input-number
                    v-model="form.uploadKiBps"
                    :min="0"
                    :max="10000000"
            /></label>
            <label
                >{{ t('nj.settings.download') }} (KB/s)<el-input-number
                    v-model="form.downloadKiBps"
                    :min="0"
                    :max="10000000"
            /></label>
            <label>{{ t('IDS_PW_MAXC') }}<el-input-number v-model="form.maxConnections" :min="1" :max="10000" /></label>
            <label
                >{{ t('IDS_PW_MAXSOURCES') }}<el-input-number v-model="form.maxSourcesPerFile" :min="1" :max="10000"
            /></label>
        </div>
        <div class="numbers">
            <label
                >{{ t('IDS_SCHED_CATSTOP') }}
                <el-select v-model="form.stopCategory" clearable placeholder="-">
                    <el-option :value="0" :label="t('IDS_ALLOTHERS')" />
                    <el-option v-for="c in categories.data.value" :key="c.id" :value="c.id" :label="c.name" />
                </el-select>
            </label>
            <label
                >{{ t('IDS_SCHED_CATRESUME') }}
                <el-select v-model="form.resumeCategory" clearable placeholder="-">
                    <el-option :value="0" :label="t('IDS_ALLOTHERS')" />
                    <el-option v-for="c in categories.data.value" :key="c.id" :value="c.id" :label="c.name" />
                </el-select>
            </label>
        </div>
        <div class="buttons">
            <el-button type="primary" @click="write([...rules, cleaned(form)])">{{ t('IDS_ADD') }}</el-button>
            <el-button
                :disabled="chosen === null"
                @click="write(rules.map((r, i) => (i === chosen ? cleaned(form) : r)))"
            >
                {{ t('nj.scheduler.replace') }}
            </el-button>
            <el-button :disabled="chosen === null" @click="write(rules.filter((_, i) => i !== chosen))">
                {{ t('IDS_REMOVE') }}
            </el-button>
        </div>
    </form>
</template>

<style scoped>
.hint {
    color: var(--el-text-color-secondary);
}
.form {
    display: flex;
    flex-direction: column;
    gap: 10px;
    margin-top: 16px;
}
.form label {
    display: flex;
    flex-direction: column;
    gap: 4px;
}
.times,
.numbers,
.buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
}
.buttons .el-button + .el-button {
    margin-left: 0;
}
</style>
