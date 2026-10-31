<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue';
import { answer, api } from '../api/client';
import { rate } from '../format';
import { keepLanguage, keptLanguage, LANGUAGES, t } from '../i18n';
import { currentPlatform, type Platform } from '../platform';
import {
    defaultWords,
    holdsWords,
    LANGUAGE_NAMES,
    matches,
    PAGES,
    rowTitle,
    type Action,
    type Page,
    type Row,
    type Section,
} from '../settings';
import { family, type Family, mode, type Mode, setTheme } from '../theme';
import { message, problem, success } from '../toasts';
import Icon from '../ui/Icon.vue';
import {
    changed,
    commit,
    commitAll,
    defaults,
    held,
    PLACES,
    readDefaults,
    reset,
    rowError,
    saveHeld,
    typed,
    wrong,
} from './settings/options';
import RowControls from './settings/RowControls.vue';
import SchedulerPage from './settings/SchedulerPage.vue';
import ServerListsDialog from './settings/ServerListsDialog.vue';
import SettingRow from './settings/SettingRow.vue';

// The options one page at a time, chosen from a list beside them, with a box that finds options by their words on
// every page and a chip that keeps only those changed from their defaults. Changes apply at once, except those that
// take effect when njMule connects again or starts, which wait in a bar for Apply.

const PAGE_KEY = 'njmule.settings.page';
const FAMILIES: Family[] = ['nord', 'primer', 'cupertino', 'dracula'];
const MODES: Mode[] = ['system', 'light', 'dark'];

const find = ref('');
const changedOnly = ref(false);
const pageId = ref(readPage());
const startLanguage = keptLanguage();
const languageChoice = ref(startLanguage);
const platform = shallowRef<Platform | undefined>();
// Who installs a new version: where a package manager does, njMule only downloads it.
const installer = ref('');
const listsOpen = ref(false);

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
    void currentPlatform().then((p) => (platform.value = p));
    void readDefaults();
    try {
        installer.value = (await answer(api().GET('/update'))).installer;
    } catch {
        // The note without what the package manager does.
    }
});

// The language and the look, kept by this window rather than the engine.
const themeChanged = computed(() => family.value !== 'nord' || mode.value !== 'system');
const languageChanged = computed(() => languageChoice.value !== '');

function isChanged(row: Row): boolean {
    if (row.local === 'language') {
        return languageChanged.value;
    }
    return row.local === 'theme' ? themeChanged.value : changed(row);
}

function hintOf(row: Row): string {
    if (row.hint === undefined) {
        return '';
    }
    const note = t(row.hint);
    return row.fields[0]?.key === 'update.mode' && installer.value === 'none'
        ? `${note} ${t('nj.update.cannotInstall')}`
        : note;
}

// The control the name is for: the one of a row with one.
function controlOf(row: Row): string | undefined {
    if (row.local !== undefined) {
        return `option-${row.local}`;
    }
    return row.fields.length === 1 ? `option-${row.fields[0]!.key}` : undefined;
}

function defaultTextOf(row: Row): string {
    if (row.local === 'language') {
        return t('nj.settings.systemLanguage');
    }
    if (row.local === 'theme') {
        return `${t('nj.theme.nord')}, ${t('nj.mode.system')}`;
    }
    return defaultWords(row, defaults.value, t, rate);
}

function resetRow(row: Row): void {
    if (row.local === 'language') {
        languageChoice.value = '';
    } else if (row.local === 'theme') {
        setTheme('nord', 'system');
    } else {
        void reset(row);
    }
}

function chosen(e: Event): string {
    return (e.target as HTMLSelectElement).value;
}

function rowShown(row: Row, section: Section, page: Page): boolean {
    return matches(row, section, page, find.value, t) && (!changedOnly.value || isChanged(row));
}

// The scheduler and the web interface are pages of their own, found by their names.
function special(page: Page): boolean {
    return page.sections.length === 0;
}

function pageShown(page: Page): boolean {
    if (special(page)) {
        return !changedOnly.value && holdsWords(t(page.title), find.value);
    }
    return page.sections.some((s) => s.rows.some((r) => rowShown(r, s, page)));
}

function pageChanged(page: Page): boolean {
    return page.sections.some((s) => s.rows.some(isChanged));
}

const changedCount = computed(() => PLACES.filter((p) => isChanged(p.row)).length);

const shownPages = computed(() => PAGES.filter(pageShown));
const current = computed(() => shownPages.value.find((p) => p.id === pageId.value) ?? shownPages.value[0]);

const heldCount = computed(() => Object.keys(held.value).length);
const restartPending = computed(() => languageChoice.value !== startLanguage);
const heldWrong = computed(() => Object.keys(typed.value).length > 0);

async function apply(): Promise<void> {
    if (!(await saveHeld())) {
        return;
    }
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

// ------------------------------------------------------------------ the buttons of the rows

// Looks now, whatever the mode, and tells what it found.
async function checkUpdate(): Promise<void> {
    message(t('nj.update.checking'));
    try {
        const update = await answer(api().POST('/update/check'));
        if (update.available) {
            message(t('nj.update.available', update.available.version));
        } else if (update.failure) {
            problem(t('nj.update.failed', update.failure));
        } else if (!update.checking) {
            message(t('nj.update.newest', update.current));
        }
    } catch (e) {
        problem((e as Error).message);
    }
}

// The lists typed are kept first: the engine fetches those of the preferences.
async function updateIpFilter(row: Row): Promise<void> {
    const field = row.fields[0]!;
    await commit(row, field);
    if (wrong(field)) {
        return;
    }
    message(t('IDS_DWL_IPFILTERFILE'));
    try {
        const loaded = await answer(api().POST('/ipfilter/lists'));
        message(t('IDS_IPFILTERLOADED', loaded.added));
    } catch (e) {
        problem((e as Error).message);
    }
}

// What is set goes first, so that the test sends with it.
async function testNotifications(): Promise<void> {
    await commitAll();
    try {
        const results = await answer(api().POST('/notifications/test'));
        if (results.length === 0) {
            problem(t('nj.notify.none'));
        }
        for (const result of results) {
            const channel = t(result.channel === 'email' ? 'IDS_PW_EMAILNOTIFICATIONS' : 'nj.notify.hook');
            if (result.error) {
                problem(`${channel}: ${result.error}`);
            } else {
                message(t('nj.notify.sent', channel));
            }
        }
    } catch (e) {
        problem((e as Error).message);
    }
}

function act(row: Row, action: Action): void {
    switch (action) {
        case 'checkUpdate':
            void checkUpdate();
            break;
        case 'serverLists':
            listsOpen.value = true;
            break;
        case 'updateIpFilter':
            void updateIpFilter(row);
            break;
        case 'testNotifications':
            void testNotifications();
            break;
    }
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
                        <section v-if="section.rows.some((r) => rowShown(r, section, current!))" class="settings-card">
                            <strong v-if="section.title" class="card-title">{{ t(section.title) }}</strong>
                            <template v-for="(row, r) in section.rows" :key="r">
                                <SettingRow
                                    v-if="rowShown(row, section, current)"
                                    :title="rowTitle(row, t)"
                                    :hint="hintOf(row)"
                                    :for="controlOf(row)"
                                    :changed="isChanged(row)"
                                    :default-text="defaultTextOf(row)"
                                    :error="rowError(row)"
                                    :wide="row.wide"
                                    @reset="resetRow(row)"
                                >
                                    <select
                                        v-if="row.local === 'language'"
                                        id="option-language"
                                        v-model="languageChoice"
                                        class="field choice"
                                    >
                                        <option value="">{{ t('nj.settings.systemLanguage') }}</option>
                                        <option v-for="tag in LANGUAGES" :key="tag" :value="tag">
                                            {{ LANGUAGE_NAMES[tag] ?? tag }}
                                        </option>
                                    </select>
                                    <template v-else-if="row.local === 'theme'">
                                        <select
                                            id="option-theme"
                                            class="field family"
                                            :value="family"
                                            @change="setTheme(chosen($event) as Family, mode)"
                                        >
                                            <option v-for="f in FAMILIES" :key="f" :value="f">
                                                {{ t(`nj.theme.${f}`) }}
                                            </option>
                                        </select>
                                        <select
                                            class="field choice"
                                            :value="mode"
                                            :aria-label="t('nj.settings.theme')"
                                            @change="setTheme(family, chosen($event) as Mode)"
                                        >
                                            <option v-for="m in MODES" :key="m" :value="m">
                                                {{ t(`nj.mode.${m}`) }}
                                            </option>
                                        </select>
                                    </template>
                                    <RowControls
                                        v-else
                                        :row="row"
                                        :title="rowTitle(row, t)"
                                        :platform="platform"
                                        @act="act(row, $event)"
                                    />
                                </SettingRow>
                            </template>
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
        <ServerListsDialog v-model:open="listsOpen" />
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

.field.choice {
    min-width: 176px;
    max-width: 320px;
}

.field.family {
    width: 108px;
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
