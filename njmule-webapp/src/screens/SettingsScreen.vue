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
    ElSwitch,
} from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { api } from '../api';
import GuestPanel from '../components/GuestPanel.vue';
import HostsPanel from '../components/HostsPanel.vue';
import ImportPanel from '../components/ImportPanel.vue';
import SetupWizard from '../components/SetupWizard.vue';
import SchedulerPanel from '../components/SchedulerPanel.vue';
import SettingsExtras from '../components/SettingsExtras.vue';
import UpdatePanel from '../components/UpdatePanel.vue';
import { t } from '../i18n';
import { attempt, told } from '../notify';
import { label, matches, PAGES, type Page, type Row } from '../settings';

type Values = Record<string, string>;

const values = ref<Values>({});
const held = ref<Values>({});
const page = ref(PAGES[0]?.id ?? 'general');
const find = ref('');
const setupOpen = ref(false);

onMounted(async () => {
    await attempt(async () => {
        values.value = await api.get<Values>('/preferences');
    });
});

const shownPages = computed(() =>
    PAGES.filter((p) => p.sections.some((s) => s.rows.some((r) => matches(r, p, find.value, t)))),
);
const current = computed<Page | undefined>(
    () => shownPages.value.find((p) => p.id === page.value) ?? shownPages.value[0],
);

function value(key: string): string {
    return held.value[key] ?? values.value[key] ?? '';
}

function visible(row: Row, p: Page): boolean {
    return (row.when === undefined || row.when.values.includes(value(row.when.key))) && matches(row, p, find.value, t);
}

// The options ticked in a flags row.
function flags(key: string): string[] {
    return value(key)
        .split(',')
        .filter((v) => v !== '');
}

async function save(changes: Values): Promise<boolean> {
    return attempt(async () => {
        values.value = await api.patch<Values>('/preferences', changes);
    });
}

// Ports, UPnP, the proxy and the network interface wait for "Apply and reconnect", as they take effect then.
async function change(row: Row, next: string): Promise<void> {
    if (row.held) {
        held.value = { ...held.value, [row.key]: next };
    } else if (next !== values.value[row.key]) {
        await save({ [row.key]: next });
    }
}

async function applyHeld(): Promise<void> {
    if (await save(held.value)) {
        held.value = {};
        told(t('nj.settings.saved'), 'success');
    }
}
</script>

<template>
    <div class="layout">
        <nav class="pages">
            <el-input v-model="find" clearable :placeholder="t('nj.settings.find')" />
            <ul>
                <li v-for="p in shownPages" :key="p.id">
                    <button
                        type="button"
                        :class="{ current: !['web', 'scheduler', 'import'].includes(page) && current?.id === p.id }"
                        @click="page = p.id"
                    >
                        {{ t(p.title) }}
                    </button>
                </li>
            </ul>
            <p v-if="shownPages.length === 0" class="hint">{{ t('nj.settings.noMatch') }}</p>
            <button type="button" class="web" :class="{ current: page === 'scheduler' }" @click="page = 'scheduler'">
                {{ t('nj.scheduler.title') }}
            </button>
            <button type="button" class="web" :class="{ current: page === 'import' }" @click="page = 'import'">
                {{ t('nj.import.title') }}
            </button>
            <button type="button" class="web" @click="setupOpen = true">{{ t('nj.wizard.title') }}</button>
            <button type="button" class="web" :class="{ current: page === 'web' }" @click="page = 'web'">
                {{ t('IDS_PW_WS') }}
            </button>
        </nav>
        <section v-if="page === 'import'" class="page">
            <h2>{{ t('nj.import.heading') }}</h2>
            <ImportPanel />
        </section>
        <section v-else-if="page === 'scheduler'" class="page">
            <h2>{{ t('nj.scheduler.title') }}</h2>
            <p class="hint">{{ t('nj.settings.page.scheduler') }}</p>
            <SchedulerPanel />
        </section>
        <section v-else-if="page === 'web'" class="page">
            <h2>{{ t('IDS_PW_WS') }}</h2>
            <HostsPanel />
            <GuestPanel />
        </section>
        <section v-else-if="current" class="page">
            <h2>{{ t(current.title) }}</h2>
            <p class="hint">{{ t(current.summary) }}</p>
            <template v-for="(section, i) in current.sections" :key="i">
                <h3 v-if="section.title && section.rows.some((r) => visible(r, current!))">{{ t(section.title) }}</h3>
                <template v-for="row in section.rows" :key="row.key">
                    <div v-if="visible(row, current)" class="row" :class="{ wide: row.kind === 'lines' }">
                        <label :for="`nj-${row.key}`">
                            {{ label(t(row.label)) }}
                            <small v-if="row.hint">{{ t(row.hint) }}</small>
                        </label>
                        <el-switch
                            v-if="row.kind === 'switch'"
                            :id="`nj-${row.key}`"
                            :model-value="value(row.key) === 'true'"
                            @change="(on) => change(row, String(Boolean(on)))"
                        />
                        <el-input-number
                            v-else-if="row.kind === 'number'"
                            :id="`nj-${row.key}`"
                            :model-value="Number(value(row.key))"
                            :min="row.min"
                            :max="row.max"
                            controls-position="right"
                            @change="(n) => n !== undefined && n !== null && change(row, String(n))"
                        />
                        <el-select
                            v-else-if="row.kind === 'choice'"
                            :id="`nj-${row.key}`"
                            :model-value="value(row.key)"
                            class="choice"
                            @change="(v) => change(row, String(v))"
                        >
                            <el-option v-for="o in row.options" :key="o.value" :value="o.value" :label="t(o.label)" />
                        </el-select>
                        <el-checkbox-group
                            v-else-if="row.kind === 'flags'"
                            :id="`nj-${row.key}`"
                            :model-value="flags(row.key)"
                            class="flags"
                            @change="(v) => change(row, (v as string[]).join(','))"
                        >
                            <el-checkbox v-for="o in row.options" :key="o.value" :value="o.value" :label="t(o.label)" />
                        </el-checkbox-group>
                        <el-input
                            v-else
                            :id="`nj-${row.key}`"
                            :model-value="value(row.key)"
                            :type="row.kind === 'lines' ? 'textarea' : row.kind === 'password' ? 'password' : 'text'"
                            :rows="4"
                            :maxlength="row.max"
                            :show-password="row.kind === 'password'"
                            class="text"
                            @change="(v) => change(row, String(v))"
                        />
                    </div>
                </template>
            </template>
            <UpdatePanel v-if="current.id === 'general'" />
            <SettingsExtras :key="current.id" :page="current.id" />
        </section>
    </div>
    <SetupWizard v-model="setupOpen" />
    <div v-if="Object.keys(held).length > 0" class="pending" role="status">
        <span>{{ t('nj.settings.pendingReconnect') }}</span>
        <el-button @click="held = {}">{{ t('nj.settings.discard') }}</el-button>
        <el-button type="primary" @click="applyHeld">{{ t('nj.settings.applyReconnect') }}</el-button>
    </div>
</template>

<style scoped>
.layout {
    display: grid;
    grid-template-columns: 240px minmax(0, 1fr);
    gap: 20px;
}
.pages ul {
    margin: 12px 0;
    padding: 0;
    list-style: none;
}
.pages button {
    width: 100%;
    padding: 7px 10px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-primary);
    font: inherit;
    text-align: start;
    cursor: pointer;
}
.pages .web {
    width: 100%;
}
.pages button.current {
    background: var(--el-fill-color);
    font-weight: 600;
}
.page {
    max-width: 860px;
    padding: 4px 20px 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.page h2 {
    margin: 12px 0 2px;
    font-size: 18px;
}
.page h3 {
    margin: 18px 0 6px;
    font-size: 14px;
    color: var(--el-text-color-secondary);
    text-transform: uppercase;
    letter-spacing: 0.04em;
}
.row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 8px 16px;
    padding: 10px 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
}
.row label {
    display: flex;
    flex: 1 1 300px;
    flex-direction: column;
}
.row small,
.hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.row.wide .text {
    flex-basis: 100%;
}
.choice {
    width: 340px;
}
.text {
    width: 340px;
}
.pending {
    position: sticky;
    bottom: 0;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    margin-top: 16px;
    padding: 10px 16px;
    border-radius: 8px;
    background: var(--nj-warn-bg);
    color: var(--nj-warn-fg);
}
.pending span {
    flex: 1;
}
@media (max-width: 900px) {
    .layout {
        grid-template-columns: 1fr;
    }
}
</style>
