<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { computed, onMounted, ref, shallowRef, watch } from 'vue';
import { answer, api, ApiError, type Schemas } from '../../api/client';
import { rate } from '../../format';
import { language, t } from '../../i18n';
import { categories } from '../../model/engine';
import { problem } from '../../toasts';
import DataTable from '../../ui/DataTable.vue';
import Icon from '../../ui/Icon.vue';
import FieldInput from './FieldInput.vue';
import {
    optionalNumberRules,
    rateKib,
    rateRules,
    rateText,
    suitedUnit,
    textRules,
    timeOfDay,
    timeRules,
    type InputRules,
    type RateUnit,
} from './inputs';
import RateField from './RateField.vue';

// The scheduler's rules: a list and a form for one rule, saved to the engine as each is added, replaced or removed.
// Empty limit fields leave the preferences' value.

type Rule = Schemas['ScheduleRule'];
type Day = NonNullable<Rule['days']>[number];

interface Listed {
    rule: Rule;
    index: number;
}

const DAYS: Day[] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
const MAX_NAME = 100;
const MAX_CONNECTIONS = 10000;
const MAX_SOURCES = 10000;
// A category id for the downloads in none.
const NO_CATEGORY = '0';

interface Speed {
    text: string;
    unit: RateUnit;
    none: boolean;
}

const rules = shallowRef<Rule[]>([]);
const active = ref<string | undefined>();
const selected = ref<string[]>([]);
const name = ref('');
const enabled = ref(true);
const days = ref<Day[]>([]);
const start = ref('00:00');
const end = ref('00:00');
const speeds = ref<Record<'upload' | 'download', Speed>>({
    upload: { text: '', unit: 'KB', none: false },
    download: { text: '', unit: 'KB', none: false },
});
const counts = ref<Record<'connections' | 'sources', string>>({ connections: '', sources: '' });
const stopCategory = ref('');
const resumeCategory = ref('');

const NAME_RULES = textRules(MAX_NAME, t);
const TIME_RULES = timeRules(t);
const SPEEDS = [
    { name: 'upload', key: 'nj.settings.upload' },
    { name: 'download', key: 'nj.settings.download' },
] as const;
const COUNTS = [
    { name: 'connections', key: 'IDS_PW_MAXC', rules: optionalNumberRules(1, MAX_CONNECTIONS, t) },
    { name: 'sources', key: 'IDS_PW_MAXSOURCES', rules: optionalNumberRules(1, MAX_SOURCES, t) },
] as const;

function speedRules(speed: Speed): InputRules {
    return rateRules(speed.unit, 1, true, t, rate);
}

function speedWrong(speed: Speed): boolean {
    return !speed.none && !speedRules(speed).valid(speed.text);
}

const invalid = computed(
    () =>
        !NAME_RULES.valid(name.value) ||
        !TIME_RULES.valid(start.value) ||
        !TIME_RULES.valid(end.value) ||
        SPEEDS.some((s) => speedWrong(speeds.value[s.name])) ||
        COUNTS.some((c) => !c.rules.valid(counts.value[c.name].trim())),
);

const chosen = computed(() => {
    const id = selected.value.at(-1);
    return id === undefined ? -1 : Number(id);
});

// The short name of a day in the language of the window.
function day(name: Day): string {
    const date = new Date(Date.UTC(2024, 0, 1 + DAYS.indexOf(name)));
    return date.toLocaleDateString(language.value, { weekday: 'short', timeZone: 'UTC' });
}

// A limit of a rule: nothing when it changes none, no limit, or the speed in the unit that suits it.
function limit(kib?: number): string {
    return kib === undefined ? '' : kib === 0 ? t('nj.rate.noLimit') : rate(kib * 1024);
}

const columns: ColumnDef<any, Listed, any>[] = [
    { id: 'name', header: t('nj.scheduler.name'), accessorFn: (l: Listed) => l.rule.name ?? '', size: 150 },
    {
        id: 'days',
        header: t('nj.scheduler.days'),
        accessorFn: (l: Listed) =>
            DAYS.filter((d) => l.rule.days?.includes(d))
                .map(day)
                .join(' '),
        size: 170,
    },
    { id: 'from', header: t('nj.scheduler.from'), accessorFn: (l: Listed) => l.rule.start, size: 70 },
    { id: 'to', header: t('nj.scheduler.to'), accessorFn: (l: Listed) => l.rule.end, size: 70 },
    { id: 'upload', header: t('nj.settings.upload'), accessorFn: (l: Listed) => limit(l.rule.uploadKiBps), size: 90 },
    {
        id: 'download',
        header: t('nj.settings.download'),
        accessorFn: (l: Listed) => limit(l.rule.downloadKiBps),
        size: 90,
    },
    {
        id: 'enabled',
        header: t('nj.scheduler.enabled'),
        accessorFn: (l: Listed) => (l.rule.enabled ? '✓' : ''),
        size: 70,
    },
];

const listed = computed<Listed[]>(() => rules.value.map((rule, index) => ({ rule, index })));

// No action, the downloads in no category, then the categories, and one a rule names that is gone.
const categoryChoices = computed(() => {
    const choices = [
        { value: '', name: '-' },
        { value: NO_CATEGORY, name: t('IDS_ALLOTHERS') },
        ...categories.value.map((c) => ({ value: String(c.id), name: c.name })),
    ];
    for (const id of [stopCategory.value, resumeCategory.value]) {
        if (id !== '' && !choices.some((c) => c.value === id)) {
            choices.push({ value: id, name: `#${id}` });
        }
    }
    return choices;
});

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
        const text = (e as Error).message;
        problem(e instanceof ApiError && e.status === 400 ? t('nj.settings.invalid', text) : text);
    }
}

// A limit shows in the unit that suits it, nothing for none, the box ticked for no limit.
function speedOf(kib: number | undefined, was: Speed): Speed {
    if (kib === undefined) {
        return { text: '', unit: was.unit, none: false };
    }
    if (kib === 0) {
        return { text: '', unit: was.unit, none: true };
    }
    const unit = suitedUnit(kib);
    return { text: rateText(kib, unit, language.value), unit, none: false };
}

function numberText(value?: number): string {
    return value === undefined ? '' : String(value);
}

// The rule chosen fills the form.
watch(chosen, (index) => {
    const r = rules.value[index];
    if (r === undefined) {
        return;
    }
    name.value = r.name ?? '';
    enabled.value = r.enabled;
    days.value = [...(r.days ?? [])];
    start.value = r.start;
    end.value = r.end;
    speeds.value = {
        upload: speedOf(r.uploadKiBps, speeds.value.upload),
        download: speedOf(r.downloadKiBps, speeds.value.download),
    };
    counts.value = { connections: numberText(r.maxConnections), sources: numberText(r.maxSourcesPerFile) };
    stopCategory.value = numberText(r.stopCategory);
    resumeCategory.value = numberText(r.resumeCategory);
});

function kibOf(speed: Speed): number | undefined {
    return speed.none ? 0 : (rateKib(speed.text, speed.unit, 1) ?? undefined);
}

function optional(text: string): number | undefined {
    return text.trim() === '' ? undefined : Number(text.trim());
}

// The rule of the form; a value left empty is left out, as it changes nothing.
function rule(): Rule {
    const r: Rule = {
        name: name.value.trim(),
        enabled: enabled.value,
        days: DAYS.filter((d) => days.value.includes(d)),
        start: timeOfDay(start.value) ?? start.value,
        end: timeOfDay(end.value) ?? end.value,
    };
    const optionals: [keyof Rule, number | undefined][] = [
        ['uploadKiBps', kibOf(speeds.value.upload)],
        ['downloadKiBps', kibOf(speeds.value.download)],
        ['maxConnections', optional(counts.value.connections)],
        ['maxSourcesPerFile', optional(counts.value.sources)],
        ['stopCategory', optional(stopCategory.value)],
        ['resumeCategory', optional(resumeCategory.value)],
    ];
    for (const [key, value] of optionals) {
        if (value !== undefined) {
            (r as Record<string, unknown>)[key] = value;
        }
    }
    return r;
}

function add(): Promise<void> {
    return write([...rules.value, rule()]);
}

function replace(): Promise<void> | undefined {
    const index = chosen.value;
    return index < 0 ? undefined : write(rules.value.map((r, i) => (i === index ? rule() : r)));
}

async function remove(): Promise<void> {
    const index = chosen.value;
    if (index < 0) {
        return;
    }
    selected.value = [];
    await write(rules.value.filter((_, i) => i !== index));
}

function setSpeed(which: 'upload' | 'download', change: Partial<Speed>): void {
    speeds.value = { ...speeds.value, [which]: { ...speeds.value[which], ...change } };
}

// Another unit keeps the speed typed: the number is written again in it.
function setUnit(which: 'upload' | 'download', unit: RateUnit): void {
    const speed = speeds.value[which];
    const kib = rateKib(speed.text, speed.unit, 0);
    setSpeed(which, {
        text: kib === null || kib === undefined ? speed.text : rateText(kib, unit, language.value),
        unit,
    });
}

function setCount(which: 'connections' | 'sources', text: string): void {
    counts.value = { ...counts.value, [which]: text };
}
</script>

<template>
    <section class="settings-card scheduler">
        <div class="rules">
            <DataTable
                v-model:selected="selected"
                name="schedule"
                :rows="listed"
                :columns="columns"
                :row-id="(l: Listed) => String(l.index)"
                :label="t('nj.scheduler.title')"
                :columns-label="t('nj.scheduler.title')"
            >
                <template #empty>{{ t('nj.scheduler.none') }}</template>
            </DataTable>
        </div>
        <div class="form">
            <label for="rule-name">{{ t('nj.scheduler.name') }}</label>
            <div class="line">
                <FieldInput
                    id="rule-name"
                    v-model="name"
                    class="name"
                    dir="auto"
                    :allowed="NAME_RULES.allowed"
                    :max="NAME_RULES.max"
                    :tip="NAME_RULES.hint"
                    :invalid="!NAME_RULES.valid(name)"
                />
                <label class="check"><input v-model="enabled" type="checkbox" />{{ t('nj.scheduler.enabled') }}</label>
            </div>
            <span>{{ t('nj.scheduler.days') }}</span>
            <div class="line days">
                <label v-for="d in DAYS" :key="d" class="check"
                    ><input v-model="days" type="checkbox" :value="d" />{{ day(d) }}</label
                >
            </div>
            <label for="rule-start">{{ t('nj.scheduler.from') }} / {{ t('nj.scheduler.to') }}</label>
            <div class="line">
                <FieldInput
                    id="rule-start"
                    v-model="start"
                    class="narrow"
                    dir="ltr"
                    :allowed="TIME_RULES.allowed"
                    :max="TIME_RULES.max"
                    :tip="TIME_RULES.hint"
                    :invalid="!TIME_RULES.valid(start)"
                />
                <FieldInput
                    v-model="end"
                    class="narrow"
                    dir="ltr"
                    :aria-label="t('nj.scheduler.to')"
                    :allowed="TIME_RULES.allowed"
                    :max="TIME_RULES.max"
                    :tip="TIME_RULES.hint"
                    :invalid="!TIME_RULES.valid(end)"
                />
            </div>
            <template v-for="s in SPEEDS" :key="s.name">
                <label :for="`rule-${s.name}`">{{ t(s.key) }}</label>
                <div class="line">
                    <RateField
                        :id="`rule-${s.name}`"
                        :text="speeds[s.name].text"
                        :unit="speeds[s.name].unit"
                        :units="['KB', 'MB']"
                        :none="speeds[s.name].none"
                        :tip="speedRules(speeds[s.name]).hint"
                        :invalid="speedWrong(speeds[s.name])"
                        :label="t(s.key)"
                        @text="setSpeed(s.name, { text: $event })"
                        @unit="setUnit(s.name, $event)"
                        @none="setSpeed(s.name, { none: $event })"
                    />
                </div>
            </template>
            <template v-for="c in COUNTS" :key="c.name">
                <label :for="`rule-${c.name}`">{{ t(c.key) }}</label>
                <div class="line">
                    <FieldInput
                        :id="`rule-${c.name}`"
                        class="narrow"
                        :model-value="counts[c.name]"
                        dir="ltr"
                        inputmode="numeric"
                        :allowed="c.rules.allowed"
                        :max="c.rules.max"
                        :tip="c.rules.hint"
                        :invalid="!c.rules.valid(counts[c.name].trim())"
                        @update:model-value="setCount(c.name, $event)"
                    />
                </div>
            </template>
            <label for="rule-stop">{{ t('IDS_SCHED_CATSTOP') }}</label>
            <div class="line">
                <select id="rule-stop" v-model="stopCategory" class="field category">
                    <option v-for="c in categoryChoices" :key="c.value" :value="c.value">{{ c.name }}</option>
                </select>
            </div>
            <label for="rule-resume">{{ t('IDS_SCHED_CATRESUME') }}</label>
            <div class="line">
                <select id="rule-resume" v-model="resumeCategory" class="field category">
                    <option v-for="c in categoryChoices" :key="c.value" :value="c.value">{{ c.name }}</option>
                </select>
            </div>
        </div>
        <div class="tools">
            <button class="button" :disabled="invalid" @click="add"><Icon name="ADD" />{{ t('IDS_ADD') }}</button>
            <button class="button" :disabled="invalid" @click="replace">
                <Icon name="REFRESH" />{{ t('nj.scheduler.replace') }}
            </button>
            <button class="button" @click="remove"><Icon name="DELETE" />{{ t('IDS_REMOVE') }}</button>
            <span class="spacer" />
            <span>{{ active ? t('nj.scheduler.active', active) : t('nj.scheduler.inactive') }}</span>
        </div>
    </section>
</template>

<style scoped>
.scheduler {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-block: 12px;
}

/* As high as the desktop app's list of rules. */
.rules {
    display: flex;
    flex-direction: column;
    height: 160px;
}

.form {
    display: grid;
    grid-template-columns: auto 1fr;
    align-items: center;
    gap: 6px 10px;
}

.line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.days {
    gap: 4px 8px;
}

/* Fields in columns of a wide letter, as the desktop app sizes them. */
.line :deep(.name) {
    width: calc(12 * 0.94em + 18px);
}

.line :deep(.narrow) {
    width: calc(6 * 0.94em + 18px);
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

.category {
    min-width: 176px;
    max-width: 100%;
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
