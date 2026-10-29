<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import { rate } from '../../format';
import { language, t } from '../../i18n';
import { categories } from '../../model/engine';
import { problem } from '../../toasts';
import Icon from '../../ui/Icon.vue';

// The scheduler's rules: a list and a form for one rule, saved to the engine as each is added, replaced or removed.

type Rule = Schemas['ScheduleRule'];
type Day = NonNullable<Rule['days']>[number];

const DAYS: Day[] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
const MAX_NAME = 100;

interface Form {
    name: string;
    enabled: boolean;
    days: Day[];
    start: string;
    end: string;
    upload: string;
    uploadUnit: 'KB' | 'MB';
    uploadUnlimited: boolean;
    download: string;
    downloadUnit: 'KB' | 'MB';
    downloadUnlimited: boolean;
    connections: string;
    sources: string;
    stopCategory: string;
    resumeCategory: string;
}

const rules = shallowRef<Rule[]>([]);
const active = ref<string | undefined>();
const chosen = ref(-1);
const form = ref<Form>(empty());

function empty(): Form {
    return {
        name: '',
        enabled: true,
        days: [],
        start: '00:00',
        end: '00:00',
        upload: '',
        uploadUnit: 'KB',
        uploadUnlimited: false,
        download: '',
        downloadUnit: 'KB',
        downloadUnlimited: false,
        connections: '',
        sources: '',
        stopCategory: '',
        resumeCategory: '',
    };
}

// The short name of a day in the language of the window.
function day(name: Day): string {
    const date = new Date(Date.UTC(2024, 0, 1 + DAYS.indexOf(name)));
    return date.toLocaleDateString(language.value, { weekday: 'short', timeZone: 'UTC' });
}

function limit(kib?: number): string {
    return kib === undefined ? '' : kib === 0 ? t('nj.rate.noLimit') : rate(kib * 1024);
}

async function load(): Promise<void> {
    try {
        const s = await answer(api().GET('/schedule'));
        rules.value = s.rules;
        active.value = s.active;
    } catch (e) {
        problem((e as Error).message);
    }
}

onMounted(load);

// As the other options, the rules apply as soon as they change.
async function write(next: Rule[]): Promise<void> {
    try {
        await answer(api().PUT('/schedule', { body: { rules: next } }));
        await load();
    } catch (e) {
        problem((e as Error).message);
    }
}

function choose(index: number): void {
    chosen.value = index;
    const r = rules.value[index]!;
    form.value = {
        name: r.name ?? '',
        enabled: r.enabled,
        days: [...(r.days ?? [])],
        start: r.start,
        end: r.end,
        upload: r.uploadKiBps ? String(r.uploadKiBps) : '',
        uploadUnit: 'KB',
        uploadUnlimited: r.uploadKiBps === 0,
        download: r.downloadKiBps ? String(r.downloadKiBps) : '',
        downloadUnit: 'KB',
        downloadUnlimited: r.downloadKiBps === 0,
        connections: r.maxConnections === undefined ? '' : String(r.maxConnections),
        sources: r.maxSourcesPerFile === undefined ? '' : String(r.maxSourcesPerFile),
        stopCategory: r.stopCategory === undefined ? '' : String(r.stopCategory),
        resumeCategory: r.resumeCategory === undefined ? '' : String(r.resumeCategory),
    };
}

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/;

// The first value of the form that is wrong, in words; empty while all are right.
const wrong = computed(() => {
    const f = form.value;
    if (f.name.length > MAX_NAME) {
        return `${t('nj.scheduler.name')}: ${t('nj.input.name', MAX_NAME)}`;
    }
    if (!TIME.test(f.start) || !TIME.test(f.end)) {
        return t('nj.input.time');
    }
    for (const [text, value] of [
        [f.upload, f.uploadUnlimited],
        [f.download, f.downloadUnlimited],
    ] as const) {
        if (!value && text !== '' && !(Number(text) > 0)) {
            return t('nj.input.rate', rate(1024), t('nj.rate.noLimit'));
        }
    }
    for (const n of [f.connections, f.sources]) {
        if (n !== '' && !/^\d+$/.test(n)) {
            return t('nj.input.optionalNumber', 1, 100000);
        }
    }
    return '';
});

// A rule needs a name, which the form asks for without calling it wrong before anything is typed.
const unsaved = computed(() => wrong.value !== '' || form.value.name.trim() === '');

// A limit left empty leaves the preference as it is; no limit is 0.
function kib(text: string, unit: 'KB' | 'MB', unlimited: boolean): number | undefined {
    if (unlimited) {
        return 0;
    }
    return text === '' ? undefined : Math.round(Number(text) * (unit === 'MB' ? 1024 : 1));
}

function rule(): Rule {
    const f = form.value;
    const r: Rule = { name: f.name.trim(), enabled: f.enabled, days: f.days, start: f.start, end: f.end };
    const optional: [keyof Rule, number | undefined][] = [
        ['uploadKiBps', kib(f.upload, f.uploadUnit, f.uploadUnlimited)],
        ['downloadKiBps', kib(f.download, f.downloadUnit, f.downloadUnlimited)],
        ['maxConnections', f.connections === '' ? undefined : Number(f.connections)],
        ['maxSourcesPerFile', f.sources === '' ? undefined : Number(f.sources)],
        ['stopCategory', f.stopCategory === '' ? undefined : Number(f.stopCategory)],
        ['resumeCategory', f.resumeCategory === '' ? undefined : Number(f.resumeCategory)],
    ];
    for (const [key, value] of optional) {
        if (value !== undefined) {
            (r as Record<string, unknown>)[key] = value;
        }
    }
    return r;
}

function add(): Promise<void> {
    return write([...rules.value, rule()]);
}

function replace(): Promise<void> {
    return write(rules.value.map((r, i) => (i === chosen.value ? rule() : r)));
}

async function remove(): Promise<void> {
    await write(rules.value.filter((_, i) => i !== chosen.value));
    chosen.value = -1;
    form.value = empty();
}
</script>

<template>
    <section class="settings-card scheduler">
        <div class="rules" tabindex="0">
            <table>
                <thead>
                    <tr>
                        <th>{{ t('nj.scheduler.name') }}</th>
                        <th>{{ t('nj.scheduler.days') }}</th>
                        <th>{{ t('nj.scheduler.from') }}</th>
                        <th>{{ t('nj.scheduler.to') }}</th>
                        <th>{{ t('nj.settings.upload') }}</th>
                        <th>{{ t('nj.settings.download') }}</th>
                        <th>{{ t('nj.scheduler.enabled') }}</th>
                    </tr>
                </thead>
                <tbody>
                    <tr
                        v-for="(r, i) in rules"
                        :key="i"
                        :class="{ chosen: i === chosen }"
                        :aria-selected="i === chosen"
                        @click="choose(i)"
                    >
                        <td dir="auto">{{ r.name }}</td>
                        <td>{{ (r.days ?? []).map(day).join(' ') }}</td>
                        <td dir="ltr">{{ r.start }}</td>
                        <td dir="ltr">{{ r.end }}</td>
                        <td dir="ltr">{{ limit(r.uploadKiBps) }}</td>
                        <td dir="ltr">{{ limit(r.downloadKiBps) }}</td>
                        <td>{{ r.enabled ? '✓' : '' }}</td>
                    </tr>
                    <tr v-if="rules.length === 0">
                        <td colspan="7" class="muted">{{ t('nj.scheduler.none') }}</td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="form">
            <label for="rule-name">{{ t('nj.scheduler.name') }}</label>
            <div class="line">
                <input id="rule-name" v-model="form.name" class="field wide" :maxlength="MAX_NAME" />
                <label class="check"
                    ><input v-model="form.enabled" type="checkbox" />{{ t('nj.scheduler.enabled') }}</label
                >
            </div>
            <span>{{ t('nj.scheduler.days') }}</span>
            <div class="line days">
                <label v-for="d in DAYS" :key="d" class="check"
                    ><input v-model="form.days" type="checkbox" :value="d" />{{ day(d) }}</label
                >
            </div>
            <label for="rule-start">{{ t('nj.scheduler.from') }} / {{ t('nj.scheduler.to') }}</label>
            <div class="line">
                <input id="rule-start" v-model="form.start" class="field time" dir="ltr" placeholder="hh:mm" />
                <input v-model="form.end" class="field time" dir="ltr" placeholder="hh:mm" />
            </div>
            <template
                v-for="which in [
                    { key: 'nj.settings.upload', value: 'upload', unit: 'uploadUnit', unlimited: 'uploadUnlimited' },
                    {
                        key: 'nj.settings.download',
                        value: 'download',
                        unit: 'downloadUnit',
                        unlimited: 'downloadUnlimited',
                    },
                ] as const"
                :key="which.key"
            >
                <label :for="`rule-${which.value}`">{{ t(which.key) }}</label>
                <div class="line">
                    <input
                        :id="`rule-${which.value}`"
                        v-model="form[which.value]"
                        class="field number"
                        dir="ltr"
                        :disabled="form[which.unlimited]"
                    />
                    <select v-model="form[which.unit]" class="field unit" :disabled="form[which.unlimited]">
                        <option value="KB">{{ t('IDS_KBYTESPERSEC') }}</option>
                        <option value="MB">{{ t('IDS_MBYTESPERSEC') }}</option>
                    </select>
                    <label class="check"
                        ><input v-model="form[which.unlimited]" type="checkbox" />{{ t('nj.rate.noLimit') }}</label
                    >
                </div>
            </template>
            <label for="rule-connections">{{ t('IDS_PW_MAXC') }}</label>
            <input id="rule-connections" v-model="form.connections" class="field wide" dir="ltr" />
            <label for="rule-sources">{{ t('IDS_PW_MAXSOURCES') }}</label>
            <input id="rule-sources" v-model="form.sources" class="field wide" dir="ltr" />
            <template
                v-for="which in [
                    { key: 'IDS_SCHED_CATSTOP', value: 'stopCategory' },
                    { key: 'IDS_SCHED_CATRESUME', value: 'resumeCategory' },
                ] as const"
                :key="which.key"
            >
                <label :for="`rule-${which.value}`">{{ t(which.key) }}</label>
                <select :id="`rule-${which.value}`" v-model="form[which.value]" class="field category">
                    <option value="">-</option>
                    <option v-for="c in categories" :key="c.id" :value="String(c.id)">
                        {{ c.id === 0 ? t('IDS_ALLOTHERS') : c.name }}
                    </option>
                </select>
            </template>
        </div>
        <p v-if="wrong" class="wrong" role="alert"><Icon name="ERROR" />{{ wrong }}</p>
        <div class="tools">
            <button class="button" :disabled="unsaved" @click="add"><Icon name="ADD" />{{ t('IDS_ADD') }}</button>
            <button class="button" :disabled="unsaved || chosen < 0" @click="replace">
                <Icon name="REFRESH" />{{ t('nj.scheduler.replace') }}
            </button>
            <button class="button" :disabled="chosen < 0" @click="remove">
                <Icon name="DELETE" />{{ t('IDS_REMOVE') }}
            </button>
            <span class="spacer" />
            <span class="muted">{{ active ? t('nj.scheduler.active', active) : t('nj.scheduler.inactive') }}</span>
        </div>
    </section>
</template>

<style scoped>
.scheduler {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.rules {
    max-height: 160px;
    overflow-y: auto;
    border: 1px solid var(--color-border-default);
}

table {
    width: 100%;
    border-collapse: collapse;
}

th {
    position: sticky;
    top: 0;
    padding: 8px;
    border-bottom: 1px solid var(--color-border-default);
    background: var(--color-bg-subtle);
    font-weight: bold;
    text-align: start;
}

td {
    height: 32px;
    padding: 0 8px;
    border-bottom: 1px solid var(--color-border-muted);
    white-space: nowrap;
}

tbody tr {
    cursor: pointer;
}

tr.chosen {
    background: var(--color-accent-muted);
}

.form {
    display: grid;
    grid-template-columns: auto 1fr;
    align-items: center;
    gap: 8px 16px;
}

.line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.days {
    gap: 6px 14px;
}

.check {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

.check input {
    width: 16px;
    height: 16px;
    margin: 0;
}

.field.wide {
    width: 280px;
    max-width: 100%;
}

.field.time {
    width: 100px;
}

.field.number {
    width: 112px;
}

.field.unit {
    width: 76px;
}

.field.category {
    width: 180px;
}

.wrong {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 0;
    color: var(--color-danger-fg);
    --nj-icon-color: var(--color-danger-fg);
}

.tools {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.spacer {
    flex: 1;
}
</style>
