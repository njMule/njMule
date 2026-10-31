<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, shallowRef, watch } from 'vue';
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
    pendingWords,
    rowTitle,
    type Action,
    type Field,
    type Page,
    type Row,
    type Section,
} from '../settings';
import { family, type Family, mode, type Mode, setTheme } from '../theme';
import { message, problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import Switch from '../ui/Switch.vue';
import {
    DESKTOP_DEFAULTS,
    type DesktopOption,
    desktopSettings,
    isDesktopOption,
    setDesktopOption,
    trayAvailable,
    usable,
} from '../desktop-options';
import {
    applyPorts,
    changed,
    commit,
    commitAll,
    defaults,
    dropDrafts,
    firstWrong,
    held,
    PLACES,
    portChoice,
    readDefaults,
    reset,
    rowError,
    rulesOf,
    saveHeld,
    wrong,
    type Place,
} from './settings/options';
import { checkNow } from '../updates';
import RowControls from './settings/RowControls.vue';
import { readInterfaces } from './settings/interfaces';
import { readPasswords } from './settings/passwords';
import SchedulerPage from './settings/SchedulerPage.vue';
import ServerListsDialog from './settings/ServerListsDialog.vue';
import WebPage from './settings/WebPage.vue';
import { changedWebOptions, readWebSettings, WEB_WORDS, webSettings } from './settings/webui';
import SettingRow from './settings/SettingRow.vue';
import PortTestPane from '../ui/PortTestPane.vue';

// The options one page at a time, chosen from a list beside them, with a box that finds options by their words on
// every page and a chip that keeps only those changed from their defaults. Changes apply at once, except those that
// take effect when njMule connects again or starts, which wait in a bar for Apply.

const PAGE_KEY = 'njmule.settings.page';
// The names of the themes and their modes, keys written out: the translations keep only the keys the code holds whole.
const FAMILY_NAMES: Record<Family, string> = {
    nord: 'nj.theme.nord',
    primer: 'nj.theme.primer',
    cupertino: 'nj.theme.cupertino',
    dracula: 'nj.theme.dracula',
};
const MODE_NAMES: Record<Mode, string> = { system: 'nj.mode.system', light: 'nj.mode.light', dark: 'nj.mode.dark' };
const FAMILIES = Object.keys(FAMILY_NAMES) as Family[];
const MODES = Object.keys(MODE_NAMES) as Mode[];

const find = ref('');
const changedOnly = ref(false);
const pageId = ref(readPage());
const startLanguage = keptLanguage();
const languageChoice = ref(startLanguage);
const platform = shallowRef<Platform | undefined>();
// Who installs a new version: where a package manager does, njMule only downloads it.
const installer = ref('');
const listsOpen = ref(false);
const pageList = ref<HTMLElement | null>(null);
const pageView = ref<HTMLElement | null>(null);

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

// The language and the look, kept by this window rather than the engine; the language as it is saved.
const themeChanged = computed(() => family.value !== 'nord' || mode.value !== 'system');
const languageChanged = computed(() => startLanguage !== '');

// The options of the desktop app's shell, set at once; a browser page shows them disabled.
function desktopValue(option: DesktopOption): boolean {
    return (desktopSettings.value ?? DESKTOP_DEFAULTS)[option];
}

function setDesktop(option: DesktopOption, value: boolean): void {
    if (platform.value !== undefined) {
        setDesktopOption(platform.value, option, value).catch((e: unknown) => problem(String(e)));
    }
}

function isChanged(row: Row): boolean {
    if (isDesktopOption(row.local)) {
        return desktopSettings.value !== null && desktopValue(row.local) !== DESKTOP_DEFAULTS[row.local];
    }
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
    if (isDesktopOption(row.local)) {
        return t(DESKTOP_DEFAULTS[row.local] ? 'IDS_YES' : 'IDS_NO');
    }
    if (row.local === 'language') {
        return t('nj.settings.systemLanguage');
    }
    if (row.local === 'theme') {
        return `${t('nj.theme.nord')}, ${t('nj.mode.system')}`;
    }
    return defaultWords(row, defaults.value, t, rate);
}

function resetRow(row: Row): void {
    if (isDesktopOption(row.local)) {
        setDesktop(row.local, DESKTOP_DEFAULTS[row.local]);
    } else if (row.local === 'language') {
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
    if (page.id === 'web') {
        const words = [t(page.title), ...WEB_WORDS.map((k) => t(k))].join(' ');
        return (!changedOnly.value || changedWebOptions(webSettings.value) > 0) && holdsWords(words, find.value);
    }
    if (special(page)) {
        return !changedOnly.value && holdsWords(t(page.title), find.value);
    }
    return page.sections.some((s) => s.rows.some((r) => rowShown(r, s, page)));
}

function pageChanged(page: Page): boolean {
    if (page.id === 'web') {
        return changedWebOptions(webSettings.value) > 0;
    }
    return page.sections.some((s) => s.rows.some(isChanged));
}

const changedCount = computed(
    () => PLACES.filter((p) => isChanged(p.row)).length + changedWebOptions(webSettings.value),
);

const shownPages = computed(() => PAGES.filter(pageShown));
const current = computed(() => shownPages.value.find((p) => p.id === pageId.value) ?? shownPages.value[0]);

// A page the filters hide gives way to the first shown, which stays chosen once the filters are cleared.
watch(shownPages, (pages) => {
    if (pages.length > 0 && !pages.some((p) => p.id === pageId.value)) {
        pageId.value = pages[0]!.id;
    }
});

// Another page shows from its top.
watch(
    () => current.value?.id,
    async () => {
        await nextTick();
        if (pageView.value) {
            pageView.value.scrollTop = 0;
        }
    },
);

// The list is one stop of the keyboard, its arrows choosing the page, as a list of the desktop app.
function listKey(e: KeyboardEvent): void {
    const pages = shownPages.value;
    const at = pages.findIndex((p) => p.id === current.value?.id);
    const to =
        e.key === 'ArrowDown'
            ? at + 1
            : e.key === 'ArrowUp'
              ? at - 1
              : e.key === 'Home'
                ? 0
                : e.key === 'End'
                  ? pages.length - 1
                  : undefined;
    if (to === undefined) {
        return;
    }
    e.preventDefault();
    const next = pages[Math.max(0, Math.min(pages.length - 1, to))];
    if (next !== undefined) {
        pageId.value = next.id;
        void nextTick(() => document.getElementById(`settings-page-${next.id}`)?.scrollIntoView({ block: 'nearest' }));
    }
}

function pageName(page: Page): string {
    return pageChanged(page) ? `${t(page.title)}, ${t('nj.settings.changed')}` : t(page.title);
}

// Escape empties the box, the down arrow goes to the list, Enter to the first option shown.
function findKey(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
        find.value = '';
    } else if (e.key === 'ArrowDown' && shownPages.value.length > 0) {
        e.preventDefault();
        pageList.value?.focus();
    } else if (e.key === 'Enter') {
        e.preventDefault();
        pageView.value
            ?.querySelector<HTMLElement>('.control :is(input, select, textarea, button):not(:disabled)')
            ?.focus();
    }
}

const heldCount = computed(() => Object.keys(held.value).length);
const restartPending = computed(() => languageChoice.value !== startLanguage);
const firstWrongOption = computed(firstWrong);
const pending = computed(() =>
    pendingWords(
        heldCount.value > 0,
        restartPending.value,
        firstWrongOption.value === undefined ? undefined : rowTitle(firstWrongOption.value.place.row, t),
        t,
    ),
);

// The page of an option, the filters cleared if they hide it, and the cursor in its field.
async function reveal(place: Place, field: Field): Promise<void> {
    if (!rowShown(place.row, place.section, place.page)) {
        find.value = '';
        changedOnly.value = false;
    }
    pageId.value = place.page.id;
    await nextTick();
    document.getElementById(`option-${field.key}`)?.focus();
}

// Every option as its field gives it first; a wrong value is shown instead of applying anything.
async function apply(): Promise<void> {
    await commitAll();
    const wrongOne = firstWrong();
    if (wrongOne !== undefined) {
        await reveal(wrongOne.place, wrongOne.field);
        const name = rowTitle(wrongOne.place.row, t);
        problem(t('nj.settings.invalid', `${name}: ${rulesOf(wrongOne.field)?.hint ?? ''}`));
        return;
    }
    const network = heldCount.value > 0;
    if (!(await saveHeld())) {
        return;
    }
    if (network) {
        message(t('nj.settings.saved'));
    }
    if (restartPending.value) {
        keepLanguage(languageChoice.value);
        window.location.reload();
    }
}

// Back to what is saved: what waits, and what is typed in every field.
function discard(): void {
    held.value = {};
    dropDrafts();
    languageChoice.value = startLanguage;
}

// Coming back while nothing waits, the fields show the saved values again, as a wrong value typed is dropped.
onActivated(() => {
    // The interfaces up now, as a VPN comes and goes; which passwords are set.
    void readInterfaces();
    void readPasswords();
    void readWebSettings();
    if (heldCount.value === 0 && !restartPending.value) {
        dropDrafts();
    }
});

// ------------------------------------------------------------------ the buttons of the rows

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
        // Looks now, whatever the mode: the answer opens in its own window.
        case 'checkUpdate':
            checkNow();
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
                    @keydown="findKey"
                />
            </label>
            <button class="chip" :aria-pressed="changedOnly" @click="changedOnly = !changedOnly">
                {{ t('nj.settings.changedOnly') }}<span class="chip-count">{{ changedCount }}</span>
            </button>
            <div
                ref="pageList"
                class="pages"
                role="listbox"
                tabindex="0"
                :aria-label="t('IDS_EM_PREFS')"
                :aria-activedescendant="current ? `settings-page-${current.id}` : undefined"
                @keydown="listKey"
            >
                <div
                    v-for="p in shownPages"
                    :id="`settings-page-${p.id}`"
                    :key="p.id"
                    class="page-item"
                    role="option"
                    :aria-selected="p.id === current?.id"
                    :aria-label="pageName(p)"
                    @click="pageId = p.id"
                >
                    <Icon :name="p.icon" /><span class="page-name">{{ t(p.title) }}</span
                    ><Icon v-if="pageChanged(p)" class="dot filled" name="DOT" />
                </div>
            </div>
        </aside>
        <div class="page-column">
            <div ref="pageView" class="page">
                <template v-if="current">
                    <h2 class="page-title display">{{ t(current.title) }}</h2>
                    <p class="page-description">{{ t(current.summary) }}</p>
                    <!-- The rules keep their form and their choice while another page shows. -->
                    <KeepAlive><SchedulerPage v-if="current.id === 'scheduler'" /></KeepAlive>
                    <WebPage v-if="current.id === 'web'" />
                    <template v-for="(section, i) in current.sections" :key="i">
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
                                                {{ t(FAMILY_NAMES[f]) }}
                                            </option>
                                        </select>
                                        <select
                                            class="field choice"
                                            :value="mode"
                                            :aria-label="t('nj.settings.theme')"
                                            @change="setTheme(family, chosen($event) as Mode)"
                                        >
                                            <option v-for="m in MODES" :key="m" :value="m">
                                                {{ t(MODE_NAMES[m]) }}
                                            </option>
                                        </select>
                                    </template>
                                    <Switch
                                        v-else-if="isDesktopOption(row.local)"
                                        :id="`option-${row.local}`"
                                        :model-value="desktopValue(row.local)"
                                        :label="rowTitle(row, t)"
                                        :disabled="!usable(row.local, desktopSettings, trayAvailable)"
                                        @update:model-value="
                                            (on: boolean) => setDesktop(row.local as DesktopOption, on)
                                        "
                                    />
                                    <PortTestPane v-else-if="row.portTest" :apply="applyPorts" :ports="portChoice" />
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
                <span class="pending-text">{{ pending }}</span>
                <button class="button flat" @click="discard">
                    <Icon name="CLOSE" />{{ t('nj.settings.discard') }}
                </button>
                <button class="button accent" :disabled="firstWrongOption !== undefined" @click="apply">
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
    background: transparent;
    outline: none;
}

.pages:focus-visible {
    border-color: var(--color-accent-emphasis);
    box-shadow: 0 0 0 1px var(--color-accent-emphasis);
}

.page-item {
    display: flex;
    align-items: center;
    gap: 10px;
    flex: none;
    height: 2.4em;
    padding: 0 10px;
    border-radius: 6px;
    cursor: pointer;
    --nj-icon-color: var(--color-fg-muted);
    --nj-icon-size: 18px;
}

.page-item:hover {
    background: var(--color-bg-subtle);
}

.page-item[aria-selected='true'] {
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

/* A page with options changed from their defaults; the chip, the rows and the page's name say it in words. */
.dot {
    --nj-icon-size: 10px;
    --nj-icon-color: var(--color-accent-emphasis);
}

.page-column {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-width: 0;
    min-height: 0;
}

/* The page scrolls up and down only. */
.page {
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 1;
    min-height: 0;
    overflow-x: hidden;
    overflow-y: auto;
    padding: 2px 8px 16px 0;
    padding-inline: 0 8px;
}

.page-title {
    margin: 0;
    font-size: 1.4em;
}

.page-description {
    margin: 0;
    padding-bottom: 8px;
    color: var(--color-fg-muted);
}

.settings-card {
    display: flex;
    flex-direction: column;
    flex: none;
    padding: 4px 16px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
}

.card-title {
    padding: 10px 0 2px;
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
    padding: 10px 12px;
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
