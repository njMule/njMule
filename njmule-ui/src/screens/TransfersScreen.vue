<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import {
    ContextMenuItem,
    ContextMenuSeparator,
    ContextMenuSub,
    ContextMenuSubContent,
    ContextMenuSubTrigger,
    ContextMenuPortal,
} from 'reka-ui';
import { computed, h, ref, watch } from 'vue';
import AddLinksDialog from '../dialogs/AddLinksDialog.vue';
import CategoriesDialog from '../dialogs/CategoriesDialog.vue';
import CommentDialog from '../dialogs/CommentDialog.vue';
import { amount, duration, percent, rate, size } from '../format';
import { t } from '../i18n';
import { bans, categories, downloads, known, sources, uploads, type Download, type Source } from '../model/engine';
import { current, withShortcutKey } from '../places';
import type { Platform } from '../platform';
import {
    byCategory,
    byName,
    byPriority,
    byState,
    keeps,
    kindIcon,
    left,
    named,
    pausable,
    priorityKey,
    remaining,
    resumable,
    shown,
    sourceDot,
    sourceShown,
    SOURCE_STATES,
    STATE_FILTERS,
    STATES,
    stoppable,
    type StateFilter,
} from '../transfers';
import {
    askCancel,
    categorize,
    clearCompleted,
    clearCompletedOf,
    copyLinks,
    each,
    prioritize,
} from '../transfers-actions';
import DataTable, { type ColumnMeta } from '../ui/DataTable.vue';
import EmptyState from '../ui/EmptyState.vue';
import FilterChips from '../ui/FilterChips.vue';
import Icon from '../ui/Icon.vue';
import Segments from '../ui/Segments.vue';
import Tip from '../ui/Tip.vue';
import DownloadDetails from './transfers/DownloadDetails.vue';
import ClientLists from './transfers/ClientLists.vue';
import DotSelect from './transfers/DotSelect.vue';

// The transfers: the downloads, narrowed by state, category and name, with the details of the chosen one under them;
// and, one list at a time, the clients we upload to, those waiting, the banned and the clients met.

defineProps<{ platform: Platform }>();

type Shelf = 'downloads' | 'uploads' | 'queue' | 'banned' | 'known';
const SHOW_ALL = -1;
const DETAILS_KEY = 'njmule.transfers.details';

const shelf = ref<Shelf>('downloads');
const stateFilter = ref<StateFilter>('all');
const category = ref(SHOW_ALL);
const filter = ref('');
const selected = ref<string[]>([]);
const opened = ref(new Set<string>());
const detailsShown = ref(readDetails());
const adding = ref(false);
const commenting = ref(false);
const categorizing = ref(false);
const commented = ref<Download | null>(null);
const filterBox = ref<HTMLInputElement | null>(null);

function readDetails(): boolean {
    try {
        return localStorage.getItem(DETAILS_KEY) === 'true';
    } catch {
        return false;
    }
}

// All categories, then each one with its dot; a category gone shows them all again.
const categoryChoices = computed(() => [
    { value: SHOW_ALL, label: t('nj.transfers.allCategories') },
    ...categories.value.map((c) => ({ value: c.id, label: c.name, color: c.color ?? 0 })),
]);

watch(categories, (now) => {
    if (category.value !== SHOW_ALL && !now.some((c) => c.id === category.value)) {
        category.value = SHOW_ALL;
    }
});

watch(detailsShown, (now) => {
    try {
        localStorage.setItem(DETAILS_KEY, String(now));
    } catch {
        // As the user left it until the window closes.
    }
});

const shelves = computed(() => [
    { value: 'downloads' as const, title: t('IDS_TW_DOWNLOADS'), count: downloads.value.length },
    {
        value: 'uploads' as const,
        title: t('IDS_TW_UPLOADS'),
        count: uploads.value.filter((u) => u.state === 'uploading').length,
    },
    {
        value: 'queue' as const,
        title: t('IDS_ONQUEUE'),
        count: uploads.value.filter((u) => u.state === 'queued').length,
    },
    { value: 'banned' as const, title: t('IDS_BANNED'), count: bans.value.length },
    { value: 'known' as const, title: t('IDS_CLIENTLIST'), count: known.value.length },
]);

// How many downloads each chip keeps, whatever the category and the filter.
const chips = computed(() =>
    STATE_FILTERS.map((f) => ({
        value: f.value,
        title: t(f.key),
        count: downloads.value.filter((d) => keeps(f.value, d)).length,
    })),
);

const shownDownloads = computed(() => {
    const byName = named(filter.value);
    return downloads.value.filter(
        (d) =>
            (category.value === SHOW_ALL || d.category === category.value) && keeps(stateFilter.value, d) && byName(d),
    );
});

const chosen = computed(() => downloads.value.filter((d) => selected.value.includes(d.hash)));

// What each source had sent at the last reading, and its speed since, by file and address.
const sourceSeen = new Map<string, { received: number; at: number }>();
const sourceRates = ref(new Map<string, number>());
watch(sources, (now) => {
    const at = performance.now();
    const rates = new Map<string, number>();
    for (const s of now) {
        const key = `${s.file}/${s.address}`;
        const before = sourceSeen.get(key);
        if (before !== undefined && at > before.at) {
            rates.set(key, Math.max(0, ((s.received - before.received) * 1000) / (at - before.at)));
        }
        sourceSeen.set(key, { received: s.received, at });
    }
    sourceRates.value = rates;
});

function sourcesOf(hash: string): Source[] {
    return sources.value.filter((s) => s.file === hash);
}

function categoryName(id: number): string {
    return categories.value.find((c) => c.id === id)?.name ?? '';
}

function categoryColor(id: number): number {
    return categories.value.find((c) => c.id === id)?.color ?? 0;
}

/** A double click or Enter: a download opens onto its sources, or closes; a complete one has none. */
function activate(d: Download): void {
    if (d.status === 'complete') {
        return;
    }
    const next = new Set(opened.value);
    if (!next.delete(d.hash)) {
        next.add(d.hash);
    }
    opened.value = next;
}

function isOpened(d: Download): boolean {
    return d.status !== 'complete' && opened.value.has(d.hash);
}

// Delete cancels the chosen downloads after the question and takes the completed ones off the list; Ctrl+C copies
// their links; the arrows open and close their sources.
function tableKey(e: KeyboardEvent): void {
    if (e.key === 'Delete') {
        void clearCompletedOf(chosen.value);
        void askCancel(chosen.value);
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c') {
        void copyLinks(chosen.value);
    } else if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        const next = new Set(opened.value);
        for (const d of chosen.value) {
            if (e.key === 'ArrowRight') {
                next.add(d.hash);
            } else {
                next.delete(d.hash);
            }
        }
        opened.value = next;
    } else {
        return;
    }
    e.preventDefault();
}

// Ctrl+F narrows the downloads by name; Alt+Enter shows the details of the chosen one.
function screenKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        shelf.value = 'downloads';
        filterBox.value?.focus();
        e.preventDefault();
    } else if (e.altKey && e.key === 'Enter') {
        detailsShown.value = true;
        e.preventDefault();
    }
}

// Our comment and rating of the one download chosen, and what others said of it.
function comment(list: Download[]): void {
    if (list.length === 1) {
        commented.value = list[0]!;
        commenting.value = true;
    }
}

// A cancel deletes what was downloaded: asked first. Delete also takes the completed ones off the list, files kept.
const cancellable = computed(() => chosen.value.filter((d) => d.status !== 'complete'));

const anyComplete = computed(() => downloads.value.some((d) => d.status === 'complete'));

const columns: ColumnDef<any, Download, any>[] = [
    {
        id: 'name',
        header: t('IDS_DL_FILENAME'),
        accessorFn: (d: Download) => d.name,
        size: 220,
        minSize: 100,
        sortFn: (a: any, b: any) => byName(a.original.name, b.original.name),
        cell: ({ row }: any) => {
            const d = row.original as Download;
            const color = categoryColor(d.category);
            return h(Tip, { text: d.category === 0 ? d.name : `${d.name}\n${categoryName(d.category)}` }, () =>
                h('span', { class: 'name-cell' }, [
                    h(Icon, { name: kindIcon(d.kind), class: 'file-kind' }),
                    d.category !== 0 ? h('span', { class: ['category-dot', color > 0 ? `cat-${color}` : ''] }) : null,
                    h('span', { class: 'ellipsis', dir: 'auto' }, d.name),
                ]),
            );
        },
    },
    {
        id: 'size',
        header: t('IDS_DL_SIZE'),
        accessorFn: (d: Download) => d.size,
        size: 80,
        minSize: 76,
        meta: { numeric: true } satisfies ColumnMeta,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'progress',
        header: t('IDS_DL_PROGRESS'),
        accessorFn: (d: Download) => (d.size <= 0 ? 0 : d.completed / d.size),
        size: 120,
        minSize: 96,
        cell: ({ row, getValue }: any) => {
            const d = row.original as Download;
            return h('span', { class: 'progress-cell' }, [
                h('span', { class: 'thin-bar' }, [
                    h('span', {
                        class: ['thin-bar-fill', STATES[shown(d)].tone],
                        style: { width: `${(getValue() as number) * 100}%`, display: 'block' },
                    }),
                ]),
                h('span', { class: 'progress-percent' }, percent(d.completed, d.size)),
            ]);
        },
    },
    {
        id: 'speed',
        header: t('IDS_DL_SPEED'),
        accessorFn: (d: Download) => d.bytesPerSecond,
        size: 90,
        minSize: 88,
        meta: { numeric: true } satisfies ColumnMeta,
        cell: ({ getValue }: any) => ((getValue() as number) <= 0 ? '—' : rate(getValue())),
    },
    {
        id: 'sources',
        header: t('IDS_DL_SOURCES'),
        accessorFn: (d: Download) => d.sources,
        size: 70,
        minSize: 52,
        meta: { numeric: true } satisfies ColumnMeta,
        // The sources that send now out of those known; a finished download has none to tell.
        cell: ({ row }: any) => {
            const d = row.original as Download;
            return d.status === 'complete' ? '—' : `${d.transferring}/${d.sources}`;
        },
    },
    {
        id: 'status',
        header: t('nj.transfers.status'),
        accessorFn: (d: Download) => shown(d),
        size: 128,
        minSize: 104,
        sortFn: (a: any, b: any) => byState(a.original, b.original),
        // The state in words on a chip of its tone, with an icon of its own: the colour is never the only cue.
        cell: ({ getValue }: any) => {
            const state = STATES[getValue() as keyof typeof STATES];
            return h('span', { class: ['state-chip', state.tone] }, [
                h(Icon, { name: state.icon }),
                h('span', { class: 'ellipsis' }, t(state.key)),
            ]);
        },
    },
    {
        id: 'left',
        header: t('IDS_DL_REMAINS'),
        accessorFn: (d: Download) => left(d),
        size: 130,
        minSize: 120,
        meta: { numeric: true } satisfies ColumnMeta,
        sortFn: (a: any, b: any) => {
            const x = left(a.original as Download);
            const y = left(b.original as Download);
            return x.seconds - y.seconds || x.bytes - y.bytes;
        },
        // The time, then the bytes in the units of the speed, as "9:13 min (151.9 MB)"; nothing once complete.
        cell: ({ row }: any) => {
            const d = row.original as Download;
            const l = left(d);
            return l.bytes === 0 ? '' : `${remaining(d) < 0 ? '—' : duration(l.seconds)} (${amount(l.bytes)})`;
        },
    },
    {
        id: 'completed',
        header: t('IDS_DL_TRANSFCOMPL'),
        accessorFn: (d: Download) => d.completed,
        size: 90,
        minSize: 76,
        meta: { numeric: true, hidden: true } satisfies ColumnMeta,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'priority',
        header: t('IDS_PRIORITY'),
        accessorFn: (d: Download) => t(priorityKey(d)),
        size: 85,
        minSize: 72,
        sortFn: (a: any, b: any) => byPriority(a.original, b.original),
        meta: { hidden: true } satisfies ColumnMeta,
    },
    {
        id: 'category',
        header: t('IDS_CAT'),
        accessorFn: (d: Download) => categoryName(d.category),
        size: 95,
        minSize: 72,
        sortFn: (a: any, b: any) => byCategory(a.original, b.original),
        meta: { hidden: true } satisfies ColumnMeta,
    },
];

// What a source shows under a column of its download.
function sourceCell(column: string, s: Source): { text?: string; bar?: number; dot?: boolean } {
    switch (column) {
        case 'name':
            return { text: s.nick ?? s.address, dot: true };
        case 'size':
            return { text: s.received > 0 ? size(s.received) : '' };
        case 'progress':
            return { bar: s.partCount === 0 ? 0 : s.parts / s.partCount };
        case 'speed': {
            const r = sourceRates.value.get(`${s.file}/${s.address}`) ?? 0;
            return { text: s.state === 'downloading' && r > 0 ? rate(r) : '' };
        }
        case 'sources':
            return { text: s.software };
        case 'status':
            return { text: t(SOURCE_STATES[sourceShown(s)]) };
        default:
            return {};
    }
}

const PRIORITIES: Download['priority'][] = ['low', 'normal', 'high', 'auto'];
const PRIORITY_KEYS: Record<Download['priority'], string> = {
    low: 'IDS_PRIOLOW',
    normal: 'IDS_PRIONORMAL',
    high: 'IDS_PRIOHIGH',
    auto: 'IDS_PRIOAUTO',
};
</script>

<template>
    <div class="screen" @keydown="screenKey">
        <div class="tool-row">
            <Segments v-model="shelf" :items="shelves" :label="t('IDS_EM_TRANS')" />
            <div class="spacer" />
            <template v-if="shelf === 'downloads'">
                <Tip :text="`${t('IDS_DETAILS')}  Alt+Enter`">
                    <button
                        id="details"
                        class="button toggle"
                        :aria-pressed="detailsShown"
                        @click="detailsShown = !detailsShown"
                    >
                        <Icon name="INFO" />{{ t('IDS_DETAILS') }}
                    </button>
                </Tip>
                <button id="clear-completed" class="button flat" :disabled="!anyComplete" @click="clearCompleted()">
                    <Icon name="CLEAR_ALL" />{{ t('IDS_DL_CLEAR') }}
                </button>
            </template>
            <button id="add-links" class="button accent" @click="adding = true">
                <Icon name="ADD" />{{ t('nj.transfers.addLinks') }}
            </button>
        </div>
        <template v-if="shelf === 'downloads'">
            <div class="narrowing">
                <FilterChips v-model="stateFilter" :items="chips" :label="t('nj.transfers.status')" />
                <DotSelect
                    id="category"
                    v-model="category"
                    class="category"
                    :items="categoryChoices"
                    :label="t('IDS_CAT')"
                />
                <Tip :text="t('nj.categories.title')">
                    <button
                        class="button flat icon-only"
                        :aria-label="t('nj.categories.title')"
                        @click="categorizing = true"
                    >
                        <Icon name="CATEGORY" />
                    </button>
                </Tip>
                <label class="field filter">
                    <Icon name="FILTER" />
                    <input
                        id="filter"
                        ref="filterBox"
                        v-model="filter"
                        :placeholder="t('nj.transfers.filter')"
                        :aria-label="t('nj.transfers.filter')"
                        @keydown.esc="filter = ''"
                    />
                    <span class="shortcut-key">{{ withShortcutKey('F') }}</span>
                </label>
            </div>
            <div class="split">
                <DataTable
                    v-model:selected="selected"
                    name="downloads"
                    :rows="shownDownloads"
                    :columns="columns"
                    :row-id="(d: Download) => d.hash"
                    :below="isOpened"
                    :label="t('IDS_TW_DOWNLOADS')"
                    :columns-label="t('IDS_DL_FILENAME')"
                    @activate="activate"
                    @keydown="tableKey"
                >
                    <template #empty>
                        <EmptyState icon="DOWNLOAD" :text="t('nj.transfers.none')">
                            <button class="button" @click="current = 'search'">
                                <Icon name="SEARCH" />{{ t('IDS_EM_SEARCH') }}
                            </button>
                            <button class="button" @click="adding = true">
                                <Icon name="PASTE" />{{ t('IDS_SW_DIRECTDOWNLOAD') }}
                            </button>
                        </EmptyState>
                    </template>
                    <template #below="{ row, columns: shownColumns }">
                        <div class="source-lines">
                            <div v-if="sourcesOf(row.hash).length === 0" class="source-none">
                                {{ t('nj.transfers.noSources') }}
                            </div>
                            <div v-for="s in sourcesOf(row.hash)" :key="s.address" class="source-line">
                                <div
                                    v-for="c in shownColumns"
                                    :key="c.id"
                                    class="source-cell"
                                    :class="{ 'source-name': c.id === 'name' }"
                                    :style="{ width: `${c.width}px` }"
                                >
                                    <template v-if="sourceCell(c.id, s).dot">
                                        <span class="source-dot" :class="sourceDot(sourceShown(s))">{{
                                            sourceDot(sourceShown(s)) === 'unknown' ? '?' : ''
                                        }}</span>
                                    </template>
                                    <span v-if="sourceCell(c.id, s).bar !== undefined" class="thin-bar">
                                        <span
                                            class="thin-bar-fill accent"
                                            :style="{ width: `${sourceCell(c.id, s).bar! * 100}%`, display: 'block' }"
                                        />
                                    </span>
                                    <Tip v-if="c.id === 'name'" :text="s.address">
                                        <span class="ellipsis">{{ sourceCell(c.id, s).text }}</span>
                                    </Tip>
                                    <span v-else class="ellipsis">{{ sourceCell(c.id, s).text }}</span>
                                </div>
                            </div>
                        </div>
                    </template>
                    <!-- What one does to the chosen downloads, the common first; the rarer under More. Clearing the
                         completed ones is of the whole list, so it comes last. Items that do not apply are off. -->
                    <template #menu>
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="!chosen.some(pausable)"
                            @select="each('/downloads/{hash}/pause', chosen)"
                        >
                            <Icon name="PAUSE" />{{ t('IDS_DL_PAUSE') }}
                        </ContextMenuItem>
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="!chosen.some(resumable)"
                            @select="each('/downloads/{hash}/resume', chosen)"
                        >
                            <Icon name="PLAY" />{{ t('IDS_DL_RESUME') }}
                        </ContextMenuItem>
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="!chosen.some(stoppable)"
                            @select="each('/downloads/{hash}/stop', chosen)"
                        >
                            <Icon name="STOP" />{{ t('IDS_DL_STOP') }}
                        </ContextMenuItem>
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="cancellable.length === 0"
                            @select="askCancel(chosen)"
                        >
                            <Icon name="DELETE" />{{ t('nj.transfers.cancelOne') }}
                        </ContextMenuItem>
                        <ContextMenuSeparator class="menu-separator" />
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="chosen.length === 0"
                            @select="detailsShown = true"
                        >
                            <Icon name="INFO" />{{ t('IDS_DETAILS') }}
                        </ContextMenuItem>
                        <ContextMenuItem class="menu-item" disabled
                            ><Icon name="PREVIEW" />{{ t('IDS_DL_PREVIEW') }}</ContextMenuItem
                        >
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="!platform.desktop || chosen.length !== 1 || chosen[0]!.status !== 'complete'"
                        >
                            <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENFOLDER') }}
                        </ContextMenuItem>
                        <ContextMenuItem class="menu-item" :disabled="chosen.length !== 1" @select="comment(chosen)">
                            <Icon name="COMMENT" />{{ t('IDS_CMT_ADD') }}
                        </ContextMenuItem>
                        <ContextMenuSub>
                            <ContextMenuSubTrigger class="menu-item" :disabled="cancellable.length === 0">
                                <Icon name="PRIORITY" />{{ t('IDS_PRIORITY')
                                }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                            </ContextMenuSubTrigger>
                            <ContextMenuPortal>
                                <ContextMenuSubContent class="menu">
                                    <ContextMenuItem
                                        v-for="p in PRIORITIES"
                                        :key="p"
                                        class="menu-item"
                                        @select="prioritize(chosen, p)"
                                    >
                                        {{ t(PRIORITY_KEYS[p]) }}
                                    </ContextMenuItem>
                                </ContextMenuSubContent>
                            </ContextMenuPortal>
                        </ContextMenuSub>
                        <ContextMenuSub>
                            <ContextMenuSubTrigger class="menu-item" :disabled="cancellable.length === 0">
                                <Icon name="LABEL" />{{ t('IDS_CAT') }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                            </ContextMenuSubTrigger>
                            <ContextMenuPortal>
                                <ContextMenuSubContent class="menu">
                                    <ContextMenuItem class="menu-item" @select="categorize(chosen, 0)">{{
                                        t('IDS_CAT_UNASSIGN')
                                    }}</ContextMenuItem>
                                    <ContextMenuItem
                                        v-for="c in categories"
                                        :key="c.id"
                                        class="menu-item"
                                        @select="categorize(chosen, c.id)"
                                    >
                                        <span class="category-dot" :class="c.color ? `cat-${c.color}` : ''" />{{
                                            c.name
                                        }}
                                    </ContextMenuItem>
                                </ContextMenuSubContent>
                            </ContextMenuPortal>
                        </ContextMenuSub>
                        <ContextMenuItem class="menu-item" disabled
                            ><Icon name="MORE" />{{ t('IDS_MORE') }}</ContextMenuItem
                        >
                        <ContextMenuSeparator class="menu-separator" />
                        <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks(chosen)">
                            <Icon name="COPY" />{{ t('nj.copyLink') }}
                        </ContextMenuItem>
                        <ContextMenuItem class="menu-item" disabled
                            ><Icon name="CODE" />{{ t('IDS_DL_LINK2') }}</ContextMenuItem
                        >
                        <ContextMenuSeparator class="menu-separator" />
                        <ContextMenuItem class="menu-item" :disabled="!anyComplete" @select="clearCompleted()">
                            <Icon name="CLEAR_ALL" />{{ t('IDS_DL_CLEAR') }}
                        </ContextMenuItem>
                    </template>
                </DataTable>
                <DownloadDetails v-if="detailsShown" :chosen="chosen" @close="detailsShown = false" />
            </div>
        </template>
        <ClientLists v-else :shelf="shelf" />
        <CategoriesDialog v-model:open="categorizing" :platform="platform" />
        <CommentDialog v-model:open="commenting" :file="commented" download />
        <AddLinksDialog v-model:open="adding" :category="category === SHOW_ALL ? undefined : category" />
    </div>
</template>

<style scoped>
.screen {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
}

.tool-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.spacer {
    flex: 1;
}

.narrowing {
    display: flex;
    align-items: center;
    gap: 8px;
}

.narrowing > :first-child {
    flex: 1;
}

.category {
    width: 200px;
    flex: none;
}

.filter {
    width: 210px;
    flex: none;
}

.split {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-height: 0;
    min-width: 0;
}

:deep(.name-cell) {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
}

:deep(.file-kind) {
    --nj-icon-color: var(--color-fg-muted);
}

:deep(.progress-cell) {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
}

:deep(.progress-percent) {
    min-width: 3.4em;
    color: var(--color-fg-muted);
    font-size: 0.92em;
    text-align: end;
    direction: ltr;
}

.source-lines {
    padding-bottom: 4px;
}

.source-line {
    display: flex;
    min-height: 22px;
}

.source-cell {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: none;
    min-width: 0;
    padding: 0 8px;
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.source-name {
    padding-inline-start: 32px;
    color: var(--color-fg-default);
}

.source-none {
    padding: 2px 4px 2px 36px;
    color: var(--color-fg-muted);
    font-size: 0.92em;
}
</style>
