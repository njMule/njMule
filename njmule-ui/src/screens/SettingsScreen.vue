<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue';
import { answer, api } from '../api/client';
import { keepLanguage, keptLanguage, LANGUAGES, t } from '../i18n';
import { preferences } from '../model/engine';
import { label, matches, PAGES, type Page, type Row } from '../settings';
import { family, type Family, mode, type Mode, setTheme } from '../theme';
import { problem, success } from '../toasts';
import Icon from '../ui/Icon.vue';
import Switch from '../ui/Switch.vue';
import SchedulerPage from './settings/SchedulerPage.vue';
import SettingRow from './settings/SettingRow.vue';

// The options one page at a time, chosen from a list beside them, with a box that finds options by their words on
// every page and a chip that keeps only those changed from their defaults. Changes apply at once, except those that
// take effect when njMule connects again or starts, which wait in a bar for Apply.

const PAGE_KEY = 'njmule.settings.page';
const FAMILIES: Family[] = ['nord', 'primer', 'cupertino', 'dracula'];
const MODES: Mode[] = ['system', 'light', 'dark'];

const defaults = shallowRef<Record<string, string>>({});
const held = ref<Record<string, string>>({});
// What is typed in a field and not saved yet: saved when the field is left, if right.
const typed = ref<Record<string, string>>({});
const find = ref('');
const changedOnly = ref(false);
const pageId = ref(readPage());
const startLanguage = keptLanguage();
const languageChoice = ref(startLanguage);

function readPage(): string {
    try {
        return localStorage.getItem(PAGE_KEY) ?? 'general';
    } catch {
        return 'general';
    }
}

watch(pageId, (id) => {
    try {
        localStorage.setItem(PAGE_KEY, id);
    } catch {
        // The general page next time.
    }
});

onMounted(async () => {
    try {
        defaults.value = await answer(api().GET('/preferences/defaults'));
    } catch {
        // An engine without the defaults: no option shows as changed.
    }
});

// A row's name: a translation key, or a word that is the same in every language, such as UDP.
function rowLabel(row: Row): string {
    return /^(IDS_|nj\.)/.test(row.label) ? label(t(row.label)) : row.label;
}

function value(key: string): string {
    return held.value[key] ?? preferences.value[key] ?? '';
}

function shown(row: Row): string {
    return typed.value[row.key] ?? value(row.key);
}

function changed(row: Row): boolean {
    const d = defaults.value[row.key];
    return row.kind !== 'password' && d !== undefined && value(row.key) !== d;
}

// The default in words, as the control shows it.
function defaultText(row: Row): string {
    const d = defaults.value[row.key] ?? '';
    switch (row.kind) {
        case 'switch':
            return t(d === 'true' ? 'IDS_YES' : 'IDS_NO');
        case 'choice':
            return label(t(row.options?.find((o) => o.value === d)?.label ?? d));
        case 'flags':
            return d
                .split(',')
                .filter((v) => v !== '')
                .map((v) => label(t(row.options?.find((o) => o.value === v)?.label ?? v)))
                .join(', ');
        case 'lines':
            return '';
        default:
            return d;
    }
}

// What is wrong with the value typed, in words; empty while it is right.
function error(row: Row): string {
    const text = typed.value[row.key];
    if (text === undefined) {
        return '';
    }
    if (row.kind === 'number') {
        const n = Number(text);
        return /^\d+$/.test(text.trim()) && n >= (row.min ?? 0) && n <= (row.max ?? Number.MAX_SAFE_INTEGER)
            ? ''
            : t('nj.input.number', row.min ?? 0, row.max ?? Number.MAX_SAFE_INTEGER);
    }
    if (row.key === 'user.nick' && text.trim() === '') {
        return t('nj.input.name', row.max ?? 50);
    }
    if (row.max !== undefined && text.length > row.max) {
        return t(row.key === 'user.nick' ? 'nj.input.name' : 'nj.input.optionalName', row.max);
    }
    return '';
}

async function save(changes: Record<string, string>): Promise<boolean> {
    try {
        preferences.value = await answer(api().PATCH('/preferences', { body: changes }));
        return true;
    } catch (e) {
        problem((e as Error).message);
        return false;
    }
}

// Ports, UPnP, the proxy and the network interface wait for Apply, as they take effect when njMule connects again.
async function change(row: Row, next: string): Promise<void> {
    if (row.held) {
        const rest = { ...held.value };
        delete rest[row.key];
        held.value =
            next === (preferences.value[row.key] ?? '') && row.kind !== 'password'
                ? rest
                : { ...rest, [row.key]: next };
    } else if (next !== preferences.value[row.key]) {
        await save({ [row.key]: next });
    }
}

function edit(row: Row, e: Event): void {
    typed.value = { ...typed.value, [row.key]: (e.target as HTMLInputElement).value };
}

// A field left keeps its value when right, and shows what is wrong otherwise.
async function commit(row: Row): Promise<void> {
    const text = typed.value[row.key];
    if (text === undefined || error(row) !== '') {
        return;
    }
    const rest = { ...typed.value };
    delete rest[row.key];
    typed.value = rest;
    await change(row, row.kind === 'number' ? String(Number(text)) : text);
}

function reset(row: Row): Promise<void> {
    const rest = { ...typed.value };
    delete rest[row.key];
    typed.value = rest;
    return change(row, defaults.value[row.key] ?? '');
}

function flags(row: Row): string[] {
    return value(row.key)
        .split(',')
        .filter((v) => v !== '');
}

function toggleFlag(row: Row, flag: string, on: boolean): Promise<void> {
    const now = flags(row).filter((v) => v !== flag);
    return change(row, (on ? [...now, flag] : now).join(','));
}

function chosen(e: Event): string {
    return (e.target as HTMLSelectElement).value;
}

// The language and the look, kept by this window rather than the engine.
const themeChanged = computed(() => family.value !== 'nord' || mode.value !== 'system');
const languageChanged = computed(() => languageChoice.value !== '');

function languageName(tag: string): string {
    try {
        return new Intl.DisplayNames([tag], { type: 'language' }).of(tag) ?? tag;
    } catch {
        return tag;
    }
}

const displayWords = computed(() => [t('IDS_PW_DISPLAY'), t('IDS_PW_LANG'), t('nj.settings.theme')]);

function displayMatches(words: string): boolean {
    const page = PAGES[0]!;
    const all = [...displayWords.value, t(page.title), t(page.summary)].join(' ').toLowerCase();
    return words
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '')
        .every((w) => all.includes(w));
}

const displayShown = computed(
    () => displayMatches(find.value) && (!changedOnly.value || themeChanged.value || languageChanged.value),
);

function rowShown(row: Row, page: Page): boolean {
    return (
        (row.when === undefined || row.when.values.includes(value(row.when.key))) &&
        matches(row, page, find.value, t) &&
        (!changedOnly.value || changed(row))
    );
}

// The scheduler and the web interface are pages of their own, found by their names.
function special(page: Page): boolean {
    return page.sections.length === 0;
}

function pageShown(page: Page): boolean {
    if (special(page)) {
        return !changedOnly.value && matches({ key: '', label: page.title, kind: 'text' }, page, find.value, t);
    }
    return (
        (page.id === 'general' && displayShown.value) ||
        page.sections.some((s) => s.rows.some((r) => rowShown(r, page)))
    );
}

function pageChanged(page: Page): boolean {
    return (
        (page.id === 'general' && (themeChanged.value || languageChanged.value)) ||
        page.sections.some((s) => s.rows.some((r) => changed(r)))
    );
}

const changedCount = computed(
    () =>
        PAGES.flatMap((p) => p.sections.flatMap((s) => s.rows)).filter((r) => changed(r)).length +
        (themeChanged.value ? 1 : 0) +
        (languageChanged.value ? 1 : 0),
);

const shownPages = computed(() => PAGES.filter(pageShown));
const current = computed(() => shownPages.value.find((p) => p.id === pageId.value) ?? shownPages.value[0]);

const heldCount = computed(() => Object.keys(held.value).length);
const restartPending = computed(() => languageChoice.value !== startLanguage);
const heldWrong = computed(() => Object.keys(typed.value).length > 0);

async function apply(): Promise<void> {
    if (heldCount.value > 0 && !(await save(held.value))) {
        return;
    }
    held.value = {};
    if (restartPending.value) {
        keepLanguage(languageChoice.value);
        window.location.reload();
        return;
    }
    success(t('nj.settings.saved'));
}

function discard(): void {
    held.value = {};
    languageChoice.value = startLanguage;
}
</script>

<template>
    <div class="settings">
        <aside class="side">
            <label class="field find">
                <Icon name="SEARCH" />
                <input
                    v-model="find"
                    :placeholder="t('nj.settings.find')"
                    :aria-label="t('nj.settings.find')"
                    @keydown.esc="find = ''"
                />
            </label>
            <button class="chip" :aria-pressed="changedOnly" @click="changedOnly = !changedOnly">
                {{ t('nj.settings.changedOnly') }}<span class="chip-count">{{ changedCount }}</span>
            </button>
            <nav class="pages" :aria-label="t('IDS_EM_PREFS')">
                <button
                    v-for="p in shownPages"
                    :key="p.id"
                    class="page-item"
                    :aria-current="p.id === current?.id ? 'page' : undefined"
                    @click="pageId = p.id"
                >
                    <Icon :name="p.icon" /><span class="page-name">{{ t(p.title) }}</span
                    ><span v-if="pageChanged(p)" class="dot" :title="t('nj.settings.changed')" />
                </button>
            </nav>
        </aside>
        <div class="page-column">
            <div class="page">
                <template v-if="current">
                    <h2 class="page-title display">{{ t(current.title) }}</h2>
                    <p class="page-description">{{ t(current.summary) }}</p>
                    <SchedulerPage v-if="current.id === 'scheduler'" />
                    <template v-for="(section, i) in current.sections" v-else :key="i">
                        <section v-if="section.rows.some((r) => rowShown(r, current!))" class="settings-card">
                            <strong v-if="section.title" class="card-title">{{ t(section.title) }}</strong>
                            <template v-for="row in section.rows" :key="row.key">
                                <SettingRow
                                    v-if="rowShown(row, current)"
                                    :title="rowLabel(row)"
                                    :hint="row.hint ? t(row.hint) : ''"
                                    :for="`option-${row.key}`"
                                    :changed="changed(row)"
                                    :default-text="defaultText(row)"
                                    :error="error(row)"
                                    :wide="row.kind === 'lines' || row.kind === 'flags'"
                                    @reset="reset(row)"
                                >
                                    <Switch
                                        v-if="row.kind === 'switch'"
                                        :id="`option-${row.key}`"
                                        :model-value="value(row.key) === 'true'"
                                        :label="rowLabel(row)"
                                        @update:model-value="(on: boolean) => change(row, String(on))"
                                    />
                                    <select
                                        v-else-if="row.kind === 'choice'"
                                        :id="`option-${row.key}`"
                                        class="field choice"
                                        :value="value(row.key)"
                                        @change="change(row, chosen($event))"
                                    >
                                        <option v-for="o in row.options" :key="o.value" :value="o.value">
                                            {{ label(t(o.label)) }}
                                        </option>
                                    </select>
                                    <textarea
                                        v-else-if="row.kind === 'lines'"
                                        :id="`option-${row.key}`"
                                        class="field lines"
                                        rows="4"
                                        dir="ltr"
                                        :value="shown(row)"
                                        @input="edit(row, $event)"
                                        @change="commit(row)"
                                    />
                                    <div v-else-if="row.kind === 'flags'" :id="`option-${row.key}`" class="flags">
                                        <label v-for="o in row.options" :key="o.value" class="check">
                                            <input
                                                type="checkbox"
                                                :checked="flags(row).includes(o.value)"
                                                @change="
                                                    toggleFlag(
                                                        row,
                                                        o.value,
                                                        ($event.target as HTMLInputElement).checked,
                                                    )
                                                "
                                            />{{ label(t(o.label)) }}
                                        </label>
                                    </div>
                                    <input
                                        v-else
                                        :id="`option-${row.key}`"
                                        class="field"
                                        :class="row.kind === 'number' ? 'number' : 'text'"
                                        :type="row.kind === 'password' ? 'password' : 'text'"
                                        :inputmode="row.kind === 'number' ? 'numeric' : undefined"
                                        :dir="row.kind === 'number' ? 'ltr' : 'auto'"
                                        :value="row.kind === 'password' ? (held[row.key] ?? '') : shown(row)"
                                        :aria-invalid="error(row) !== ''"
                                        @input="edit(row, $event)"
                                        @change="commit(row)"
                                        @keydown.enter="commit(row)"
                                    />
                                </SettingRow>
                            </template>
                        </section>
                        <section v-if="current.id === 'general' && i === 0 && displayShown" class="settings-card">
                            <strong class="card-title">{{ t('IDS_PW_DISPLAY') }}</strong>
                            <SettingRow
                                :title="label(t('IDS_PW_LANG'))"
                                for="option-language"
                                :changed="languageChanged"
                                :default-text="t('nj.settings.systemLanguage')"
                                @reset="languageChoice = ''"
                            >
                                <select id="option-language" v-model="languageChoice" class="field choice">
                                    <option value="">{{ t('nj.settings.systemLanguage') }}</option>
                                    <option v-for="tag in LANGUAGES" :key="tag" :value="tag">
                                        {{ languageName(tag) }}
                                    </option>
                                </select>
                            </SettingRow>
                            <SettingRow
                                :title="t('nj.settings.theme')"
                                for="option-theme"
                                :changed="themeChanged"
                                :default-text="`${t('nj.theme.nord')}, ${t('nj.mode.system')}`"
                                @reset="setTheme('nord', 'system')"
                            >
                                <select
                                    id="option-theme"
                                    class="field family"
                                    :value="family"
                                    @change="setTheme(chosen($event) as Family, mode)"
                                >
                                    <option v-for="f in FAMILIES" :key="f" :value="f">{{ t(`nj.theme.${f}`) }}</option>
                                </select>
                                <select
                                    class="field choice"
                                    :value="mode"
                                    :aria-label="t('nj.settings.theme')"
                                    :disabled="family === 'dracula'"
                                    @change="setTheme(family, chosen($event) as Mode)"
                                >
                                    <option v-for="m in MODES" :key="m" :value="m">{{ t(`nj.mode.${m}`) }}</option>
                                </select>
                            </SettingRow>
                        </section>
                    </template>
                </template>
                <p v-else class="muted">{{ t('nj.settings.noMatch') }}</p>
            </div>
            <div v-if="heldCount > 0 || restartPending" class="pending-bar" role="status">
                <Icon name="INFO" />
                <span class="pending-text">{{
                    t(restartPending ? 'nj.settings.pendingRestart' : 'nj.settings.pendingReconnect')
                }}</span>
                <button class="button flat" @click="discard">
                    <Icon name="CLOSE" />{{ t('nj.settings.discard') }}
                </button>
                <button class="button accent" :disabled="heldWrong" @click="apply">
                    {{ t(restartPending ? 'nj.settings.applyRestart' : 'nj.settings.applyReconnect') }}
                </button>
            </div>
        </div>
    </div>
</template>

<style scoped>
.settings {
    display: flex;
    gap: 16px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
}

/* The list of pages keeps its width whatever the page beside it asks for. */
.side {
    display: flex;
    flex-direction: column;
    gap: 8px;
    flex: 0 0 210px;
    min-height: 0;
}

.chip {
    display: inline-flex;
    align-self: flex-start;
    align-items: center;
    gap: 6px;
    height: 24px;
    padding: 0 10px;
    border: 1px solid var(--color-border-default);
    border-radius: 12px;
    background: var(--color-bg-default);
    cursor: pointer;
}

.chip[aria-pressed='true'] {
    border-color: var(--color-accent-emphasis);
    background: var(--color-accent-muted);
}

.chip-count {
    color: var(--color-fg-muted);
    font-size: 0.9em;
}

.pages {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    border: 1px solid var(--color-border-default);
    background: var(--color-bg-default);
}

.page-item {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: none;
    height: 34px;
    padding: 0 12px;
    border: 0;
    border-radius: 6px;
    background: none;
    text-align: start;
    cursor: pointer;
    --nj-icon-color: var(--color-fg-muted);
}

.page-item:hover {
    background: var(--color-bg-subtle);
}

.page-item[aria-current='page'] {
    background: var(--color-accent-muted);
    font-weight: bold;
}

.page-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* A page with options changed from their defaults; the chip and the rows say it in words. */
.dot {
    flex: none;
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--color-fg-muted);
}

.page-column {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-width: 0;
    min-height: 0;
}

.page {
    display: flex;
    flex-direction: column;
    gap: 8px;
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding-inline-end: 8px;
}

.page-title {
    margin: 0;
    font-size: 1.5em;
}

.page-description {
    margin: 0 0 4px;
    color: var(--color-fg-muted);
}

.settings-card {
    display: flex;
    flex-direction: column;
    flex: none;
    padding: 8px 16px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
}

.card-title {
    padding-top: 10px;
}

.field.text {
    width: 236px;
}

.field.number {
    width: 104px;
}

.field.choice {
    min-width: 176px;
    max-width: 320px;
}

.field.family {
    width: 108px;
}

.field.lines {
    width: 100%;
    height: auto;
    padding: 6px 8px;
    resize: vertical;
}

.field[aria-invalid='true'] {
    border-color: var(--color-danger-emphasis);
}

.flags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
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

/* The changes that wait for Apply, under the page. */
.pending-bar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 10px 12px 10px 16px;
    border: 1px solid var(--color-border-default);
    border-radius: 8px;
    background: var(--color-bg-subtle);
    --nj-icon-color: var(--color-accent-fg);
}

.pending-text {
    flex: 1;
    min-width: 0;
}
</style>
