<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import {
    ContextMenuItem,
    ContextMenuSeparator,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuRadioGroup,
    DropdownMenuRadioItem,
    DropdownMenuRoot,
    DropdownMenuTrigger,
    PopoverClose,
    PopoverContent,
    PopoverPortal,
    PopoverRoot,
    PopoverTrigger,
} from 'reka-ui';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { answer, api } from '../api/client';
import { count, size } from '../format';
import { t } from '../i18n';
import { htmlLinks } from '../links';
import { downloads, preferences, readDownloads } from '../model/engine';
import { searchRequest } from '../places';
import {
    completeness,
    expression,
    filterCount,
    history,
    link,
    narrowed,
    playingTime,
    remember,
    searches,
    shade,
    tone,
    words,
    type FileType,
    type Filters,
    type Result,
    type Scope,
    type Search,
} from '../search';
import { message, problem } from '../toasts';
import DataTable, { type ColumnMeta } from '../ui/DataTable.vue';
import Icon from '../ui/Icon.vue';
import Segments from '../ui/Segments.vue';
import Tip from '../ui/Tip.vue';

// The searches: what to look for, where and with which filters in one row; a tab of results for each search, narrowed
// by the words of a column; what is chosen and what to do with it under them.

const query = ref('');
const scope = ref<Scope>('automatic');
const filters = ref<Filters>({ type: '', extension: '' });
const shownKey = ref(searches.value.at(-1)?.key ?? '');
const filter = ref('');
const filterColumn = ref('name');
const selected = ref<string[]>([]);
const past = ref(history());
let next = 0;

const SCOPES = computed(() => [
    { value: 'automatic' as const, title: t('IDS_AUTOMATIC') },
    { value: 'server' as const, title: t('IDS_SERVER') },
    { value: 'global' as const, title: t('IDS_GLOBALSEARCH') },
    { value: 'kad' as const, title: t('IDS_KADEMLIA') },
]);

const TYPES: { value: FileType; key: string }[] = [
    { value: '', key: 'IDS_SEARCH_ANY' },
    { value: 'archive', key: 'IDS_SEARCH_ARC' },
    { value: 'audio', key: 'IDS_SEARCH_AUDIO' },
    { value: 'cd', key: 'IDS_SEARCH_CDIMG' },
    { value: 'image', key: 'IDS_SEARCH_PICS' },
    { value: 'program', key: 'IDS_SEARCH_PRG' },
    { value: 'video', key: 'IDS_SEARCH_VIDEO' },
    { value: 'document', key: 'IDS_SEARCH_DOC' },
];

const shown = computed(() => searches.value.find((s) => s.key === shownKey.value));
const downloading = computed(() => new Set(downloads.value.map((d) => d.hash.toUpperCase())));
const hasMedia = computed(() => (shown.value?.results ?? []).some((r) => r.media !== undefined));

// The text of each column the filter can look in.
const TEXTS: Record<string, { key: string; text: (r: Result) => string }> = {
    name: { key: 'IDS_DL_FILENAME', text: (r) => r.name },
    size: { key: 'IDS_DL_SIZE', text: (r) => size(r.size) },
    sources: { key: 'IDS_SEARCHAVAIL', text: (r) => String(r.sources) },
    type: { key: 'nj.search.type', text: (r) => r.type ?? '' },
    hash: { key: 'IDS_FILEID', text: (r) => r.hash },
    artist: { key: 'IDS_ARTIST', text: (r) => r.media?.artist ?? '' },
    album: { key: 'IDS_ALBUM', text: (r) => r.media?.album ?? '' },
    title: { key: 'IDS_TITLE', text: (r) => r.media?.title ?? '' },
    codec: { key: 'IDS_CODEC', text: (r) => r.media?.codec ?? '' },
};

const visible = computed(() =>
    narrowed(shown.value?.results ?? [], filter.value, TEXTS[filterColumn.value]?.text ?? ((r) => r.name)),
);
const chosen = computed(() => (shown.value?.results ?? []).filter((r) => selected.value.includes(r.hash)));
const running = computed(() => searches.value.filter((s) => s.running).length);

const KNOWN: Record<NonNullable<Result['known']> | 'spam', string> = {
    downloading: 'IDS_DOWNLOADING',
    shared: 'IDS_SHARED',
    downloaded: 'IDS_DOWNLOADED',
    cancelled: 'IDS_CANCELLED',
    spam: 'IDS_SPAM',
};

function knownText(r: Result): string {
    if (downloading.value.has(r.hash.toUpperCase())) {
        return t(KNOWN.downloading);
    }
    return r.known !== undefined ? t(KNOWN[r.known]) : r.spam ? t(KNOWN.spam) : '';
}

function columns(media: boolean): ColumnDef<any, Result, any>[] {
    const all: ColumnDef<any, Result, any>[] = [
        { id: 'name', header: t('IDS_DL_FILENAME'), accessorFn: (r: Result) => r.name, size: 380, minSize: 100 },
        {
            id: 'size',
            header: t('IDS_DL_SIZE'),
            accessorFn: (r: Result) => r.size,
            size: 90,
            cell: ({ getValue }: any) => size(getValue()),
        },
        { id: 'sources', header: t('IDS_SEARCHAVAIL'), accessorFn: (r: Result) => r.sources, size: 90 },
        {
            id: 'complete',
            header: t('IDS_COMPLETE'),
            accessorFn: (r: Result) => (r.completeSources ?? -1) / Math.max(1, r.sources),
            size: 90,
            cell: ({ row }: any) => completeness(row.original as Result),
        },
        { id: 'type', header: t('nj.search.type'), accessorFn: (r: Result) => r.type ?? '', size: 90 },
        { id: 'known', header: t('nj.search.known'), accessorFn: (r: Result) => knownText(r), size: 150 },
        {
            id: 'hash',
            header: t('IDS_FILEID'),
            accessorFn: (r: Result) => r.hash,
            size: 220,
            meta: { hidden: true } satisfies ColumnMeta,
        },
    ];
    if (!media) {
        return all;
    }
    return [
        ...all,
        { id: 'artist', header: t('IDS_ARTIST'), accessorFn: (r: Result) => r.media?.artist ?? '', size: 100 },
        { id: 'album', header: t('IDS_ALBUM'), accessorFn: (r: Result) => r.media?.album ?? '', size: 100 },
        { id: 'title', header: t('IDS_TITLE'), accessorFn: (r: Result) => r.media?.title ?? '', size: 100 },
        {
            id: 'length',
            header: t('IDS_LENGTH'),
            accessorFn: (r: Result) => r.media?.length ?? 0,
            size: 60,
            cell: ({ getValue }: any) => ((getValue() as number) === 0 ? '' : playingTime(getValue())),
        },
        {
            id: 'bitrate',
            header: t('IDS_BITRATE'),
            accessorFn: (r: Result) => r.media?.bitrate ?? 0,
            size: 80,
            cell: ({ getValue }: any) =>
                (getValue() as number) === 0 ? '' : `${count(getValue())} ${t('IDS_KBITSSEC')}`,
        },
        { id: 'codec', header: t('IDS_CODEC'), accessorFn: (r: Result) => r.media?.codec ?? '', size: 60 },
    ];
}

// Ours already in red, known from before in green, spam muted; a new result from the text's colour towards the
// accent's as it has more sources. A file being downloaded stands out in bold.
function rowClass(r: Result): string[] {
    const ours = downloading.value.has(r.hash.toUpperCase());
    return [`result-${tone(r, ours)}`, ours || r.known === 'downloading' ? 'result-downloading' : ''];
}

function rowStyle(r: Result): Record<string, string> {
    return { '--nj-shade': `${shade(r.sources)}%` };
}

function open(search: Search): void {
    searches.value = [...searches.value, search];
    shownKey.value = search.key;
}

async function start(): Promise<void> {
    const words = query.value.trim();
    if (words === '') {
        return;
    }
    remember(words);
    past.value = history();
    await search(expression(words, filters.value), scope.value);
}

async function search(e: string, where: Scope): Promise<void> {
    try {
        const { id } = await answer(api().POST('/searches', { body: { query: e, scope: where } }));
        open({ id, key: `s${++next}`, title: '', expression: e, scope: where, results: [], running: true });
    } catch (err) {
        problem((err as Error).message);
    }
}

async function close(s: Search): Promise<void> {
    const at = searches.value.indexOf(s);
    searches.value = searches.value.filter((other) => other !== s);
    if (shownKey.value === s.key) {
        shownKey.value = searches.value[Math.min(at, searches.value.length - 1)]?.key ?? '';
    }
    if (s.id !== null && s.running) {
        try {
            await answer(api().DELETE('/searches/{id}', { params: { path: { id: s.id } } }));
        } catch {
            // Gone already: the engine keeps the last searches only.
        }
    }
}

async function more(): Promise<void> {
    const s = shown.value;
    if (s?.id === null || s === undefined) {
        return;
    }
    try {
        await answer(api().POST('/searches/{id}/more', { params: { path: { id: s.id } } }));
        s.running = true;
    } catch (err) {
        problem((err as Error).message);
    }
}

async function stop(): Promise<void> {
    const s = shown.value;
    if (s?.id === null || s === undefined) {
        return;
    }
    try {
        await answer(api().DELETE('/searches/{id}', { params: { path: { id: s.id } } }));
        s.running = false;
    } catch (err) {
        problem((err as Error).message);
    }
}

async function download(results: Result[]): Promise<void> {
    for (const r of results) {
        try {
            const d = await answer(api().POST('/downloads', { body: { links: [link(r)] } }));
            for (const added of d) {
                message(t('nj.transfers.added', added.name));
            }
        } catch (err) {
            problem((err as Error).message);
        }
    }
    await readDownloads();
}

// The files related to those chosen, which only some servers find.
function searchRelated(): void {
    const hashes = [...new Set(chosen.value.map((r) => r.hash))];
    if (hashes.length > 0) {
        void search(`related::${hashes.join('::')}`, 'server');
    }
}

async function markSpam(spam: boolean): Promise<void> {
    const s = shown.value;
    if (s?.id === null || s === undefined) {
        return;
    }
    try {
        await answer(
            api().POST('/searches/{id}/spam', {
                params: { path: { id: s.id } },
                body: { hashes: chosen.value.map((r) => r.hash), spam },
            }),
        );
        await refresh(s);
    } catch (err) {
        problem((err as Error).message);
    }
}

async function copyLinks(html: boolean): Promise<void> {
    const links = chosen.value.map((r) => ({ link: link(r), name: r.name }));
    try {
        await navigator.clipboard.writeText(html ? htmlLinks(links) : links.map((l) => l.link).join('\n'));
    } catch (err) {
        problem((err as Error).message);
    }
}

async function refresh(s: Search): Promise<void> {
    if (s.id === null) {
        return;
    }
    try {
        const got = await answer(api().GET('/searches/{id}', { params: { path: { id: s.id } } }));
        s.results = got.results;
        s.running = got.running;
    } catch {
        // A search the engine forgot keeps the results it showed.
        s.running = false;
    }
}

// The tabs left open at the last exit come back with the results they had.
async function restore(): Promise<void> {
    if (searches.value.length > 0 || preferences.value['search.store'] === 'false') {
        return;
    }
    try {
        const saved = await answer(api().GET('/searches/saved'));
        for (const s of saved) {
            open({
                id: null,
                key: `s${++next}`,
                title: s.title,
                expression: s.query,
                scope: s.scope,
                results: s.results,
                running: false,
            });
        }
    } catch {
        // Nothing to bring back.
    }
}

function title(s: Search): string {
    return `${s.title || words(s.expression)} (${s.results.length})`;
}

// A search asked for elsewhere, such as from the palette.
watch(
    searchRequest,
    (words) => {
        if (words !== '') {
            query.value = words;
            searchRequest.value = '';
            void start();
        }
    },
    { immediate: true },
);

watch(shownKey, () => {
    selected.value = [];
});

let timer = 0;
onMounted(() => {
    void restore();
    timer = window.setInterval(() => {
        for (const s of searches.value) {
            if (s.running || s === shown.value) {
                void refresh(s);
            }
        }
    }, 1000);
});
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <div class="screen">
        <form class="tool-row" @submit.prevent="start">
            <div class="field query">
                <input
                    id="search-query"
                    v-model="query"
                    list="search-history"
                    :placeholder="t('IDS_SEARCH_TEXT')"
                    :aria-label="t('IDS_SEARCH_TEXT')"
                />
                <datalist id="search-history">
                    <option v-for="h in past" :key="h" :value="h" />
                </datalist>
            </div>
            <Segments v-model="scope" :items="SCOPES" :label="t('IDS_SEARCH_TEXT')" />
            <PopoverRoot>
                <PopoverTrigger id="search-parameters" type="button" class="button">
                    <Icon name="FILTER" />{{ t('nj.search.filters')
                    }}<span v-if="filterCount(filters) > 0" class="segment-count">{{ filterCount(filters) }}</span>
                </PopoverTrigger>
                <PopoverPortal>
                    <PopoverContent class="menu search-parameters" align="end" :side-offset="8">
                        <div class="popover-head">
                            <strong>{{ t('nj.search.filters') }}</strong>
                            <button
                                type="button"
                                class="button flat small"
                                @click="filters = { type: '', extension: '' }"
                            >
                                <Icon name="REFRESH" />{{ t('IDS_PW_RESET') }}
                            </button>
                        </div>
                        <span class="field-label">{{ t('IDS_TYPE') }}</span>
                        <div class="filter-chips">
                            <button
                                v-for="ty in TYPES"
                                :key="ty.value"
                                type="button"
                                class="filter-chip"
                                :aria-checked="filters.type === ty.value"
                                role="radio"
                                @click="filters.type = ty.value"
                            >
                                {{ t(ty.key) }}
                            </button>
                        </div>
                        <div class="numbers">
                            <label
                                ><span class="field-label">{{ t('IDS_SEARCHMINSIZE') }}</span
                                ><input v-model.number="filters.minMb" class="field" type="number" min="0"
                            /></label>
                            <label
                                ><span class="field-label">{{ t('IDS_SEARCHMAXSIZE') }}</span
                                ><input v-model.number="filters.maxMb" class="field" type="number" min="0"
                            /></label>
                            <label
                                ><span class="field-label">{{ t('IDS_SEARCHAVAIL') }}</span
                                ><input v-model.number="filters.availability" class="field" type="number" min="1"
                            /></label>
                            <label
                                ><span class="field-label">{{ t('IDS_COMPLSOURCES') }}</span
                                ><input v-model.number="filters.complete" class="field" type="number" min="1"
                            /></label>
                            <label class="wide"
                                ><span class="field-label">{{ t('IDS_SEARCHEXTENTION') }}</span
                                ><input v-model="filters.extension" class="field" maxlength="10"
                            /></label>
                        </div>
                        <div class="popover-foot">
                            <span class="muted">{{ t('nj.search.filtersNote') }}</span>
                            <PopoverClose class="button"><Icon name="CHECK" />{{ t('IDS_FD_CLOSE') }}</PopoverClose>
                        </div>
                    </PopoverContent>
                </PopoverPortal>
            </PopoverRoot>
            <button type="submit" class="button accent" :disabled="query.trim() === ''">
                <Icon name="SEARCH" />{{ t('nj.search.start') }}
            </button>
        </form>

        <div v-if="searches.length === 0" id="search-empty" class="empty-state">
            <Icon class="empty-icon" name="SEARCH" />
            <h2 class="display">{{ t('nj.search.emptyTitle') }}</h2>
            <p>{{ t('nj.search.emptyText') }}</p>
            <div class="tips">
                <div class="search-tip">
                    <Icon name="LABEL" />
                    <div>
                        <strong>{{ t('nj.search.tipExact') }}</strong
                        ><code>"night of the living dead"</code>
                    </div>
                </div>
                <div class="search-tip">
                    <Icon name="REMOVE_CIRCLE" />
                    <div>
                        <strong>{{ t('nj.search.tipWithout') }}</strong
                        ><code>debian -netinst</code>
                    </div>
                </div>
                <div class="search-tip">
                    <Icon name="LINK" />
                    <div>
                        <strong>{{ t('nj.search.tipLink') }}</strong
                        ><code>ed2k://|file|…|/</code>
                    </div>
                </div>
            </div>
        </div>

        <template v-else>
            <div class="tab-row">
                <div class="search-tabs" role="tablist">
                    <div
                        v-for="s in searches"
                        :key="s.key"
                        class="search-tab"
                        role="tab"
                        :aria-selected="s.key === shownKey"
                        tabindex="0"
                        @click="shownKey = s.key"
                        @keydown.enter="shownKey = s.key"
                    >
                        <span v-if="s.running" class="tab-spinner" aria-hidden="true" />
                        <span class="ellipsis" dir="auto">{{ title(s) }}</span>
                        <button class="tab-close" :aria-label="t('IDS_FD_CLOSE')" @click.stop="close(s)">
                            <Icon name="CLOSE" />
                        </button>
                    </div>
                </div>
                <button class="button flat small" :disabled="shown?.id === null" @click="more">
                    <Icon name="ADD" />{{ t('nj.search.more') }}
                </button>
                <button class="button flat small stop-search" :disabled="!shown?.running" @click="stop">
                    <Icon name="STOP" />{{ t('IDS_DL_STOP') }}
                </button>
                <div class="field results-filter">
                    <DropdownMenuRoot>
                        <Tip :text="t('nj.search.filterColumn')">
                            <DropdownMenuTrigger class="column-choice" :aria-label="t('nj.search.filterColumn')">
                                <Icon name="SEARCH" />
                            </DropdownMenuTrigger>
                        </Tip>
                        <DropdownMenuPortal>
                            <DropdownMenuContent class="menu" align="start">
                                <DropdownMenuRadioGroup v-model="filterColumn">
                                    <DropdownMenuRadioItem
                                        v-for="(c, id) in TEXTS"
                                        :key="id"
                                        class="menu-item"
                                        :value="id"
                                    >
                                        <span class="menu-check"><Icon v-if="filterColumn === id" name="CHECK" /></span
                                        >{{ t(c.key) }}
                                    </DropdownMenuRadioItem>
                                </DropdownMenuRadioGroup>
                            </DropdownMenuContent>
                        </DropdownMenuPortal>
                    </DropdownMenuRoot>
                    <Tip :text="t('nj.search.filterHint')">
                        <input
                            v-model="filter"
                            :placeholder="t(TEXTS[filterColumn]?.key ?? 'IDS_DL_FILENAME')"
                            @keydown.esc="filter = ''"
                        />
                    </Tip>
                </div>
            </div>
            <strong class="results-count">{{
                t(
                    'nj.search.count',
                    visible.length === (shown?.results.length ?? 0)
                        ? String(visible.length)
                        : `${visible.length}/${shown?.results.length ?? 0}`,
                )
            }}</strong>
            <DataTable
                v-if="shown"
                :key="`${shown.key}-${hasMedia}`"
                v-model:selected="selected"
                class="search-results"
                name="search"
                :rows="visible"
                :columns="columns(hasMedia)"
                :row-id="(r: Result) => r.hash"
                :row-class="rowClass"
                :row-style="rowStyle"
                :label="title(shown)"
                :columns-label="t('IDS_DL_FILENAME')"
                @activate="(r: Result) => download([r])"
            >
                <template #empty>
                    <span class="muted">{{ t('nj.search.waiting') }}</span>
                </template>
                <template #menu>
                    <ContextMenuItem
                        class="menu-item default-item"
                        :disabled="chosen.length === 0"
                        @select="download(chosen)"
                    >
                        <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                    </ContextMenuItem>
                    <ContextMenuSeparator class="menu-separator" />
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks(false)">
                        <Icon name="COPY" />{{ t('nj.copyLink') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks(true)">
                        <Icon name="CODE" />{{ t('IDS_DL_LINK2') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="searchRelated">
                        <Icon name="SEARCH" />{{ t('IDS_SEARCHRELATED') }}
                    </ContextMenuItem>
                    <ContextMenuSeparator class="menu-separator" />
                    <ContextMenuItem
                        class="menu-item"
                        :disabled="chosen.length === 0 || shown.id === null"
                        @select="markSpam(true)"
                    >
                        <Icon name="BLOCK" />{{ t('IDS_MARKSPAM') }}
                    </ContextMenuItem>
                    <ContextMenuItem
                        class="menu-item"
                        :disabled="chosen.length === 0 || shown.id === null"
                        @select="markSpam(false)"
                    >
                        <Icon name="CHECK" />{{ t('IDS_MARKNOTSPAM') }}
                    </ContextMenuItem>
                </template>
            </DataTable>
            <div v-if="chosen.length > 0" id="selection-bar" class="selection-bar">
                <span class="chosen-name ellipsis" dir="auto">{{
                    chosen.length === 1 ? chosen[0]!.name : t('nj.search.chosen', chosen.length)
                }}</span>
                <span v-if="chosen.length === 1" class="muted"
                    >· {{ size(chosen[0]!.size) }} · {{ t('IDS_DL_SOURCES') }}: {{ chosen[0]!.sources }}</span
                >
                <span class="spacer" />
                <button class="button accent" @click="download(chosen)">
                    <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                </button>
                <Tip :text="t('nj.copyLink')">
                    <button class="button icon-only" :aria-label="t('nj.copyLink')" @click="copyLinks(false)">
                        <Icon name="COPY" />
                    </button>
                </Tip>
                <Tip :text="t('IDS_SEARCHRELATED')">
                    <button class="button icon-only" :aria-label="t('IDS_SEARCHRELATED')" @click="searchRelated">
                        <Icon name="SEARCH" />
                    </button>
                </Tip>
                <DropdownMenuRoot>
                    <Tip :text="t('IDS_MORE')">
                        <DropdownMenuTrigger class="button icon-only" :aria-label="t('IDS_MORE')"
                            ><Icon name="MORE"
                        /></DropdownMenuTrigger>
                    </Tip>
                    <DropdownMenuPortal>
                        <DropdownMenuContent class="menu" align="end">
                            <DropdownMenuItem class="menu-item" @select="copyLinks(true)"
                                ><Icon name="CODE" />{{ t('IDS_DL_LINK2') }}</DropdownMenuItem
                            >
                            <DropdownMenuItem class="menu-item" :disabled="shown?.id === null" @select="markSpam(true)"
                                ><Icon name="BLOCK" />{{ t('IDS_MARKSPAM') }}</DropdownMenuItem
                            >
                            <DropdownMenuItem class="menu-item" :disabled="shown?.id === null" @select="markSpam(false)"
                                ><Icon name="CHECK" />{{ t('IDS_MARKNOTSPAM') }}</DropdownMenuItem
                            >
                        </DropdownMenuContent>
                    </DropdownMenuPortal>
                </DropdownMenuRoot>
            </div>
        </template>
        <span class="visually-hidden" aria-live="polite">{{
            running > 0 ? t('nj.summary.search', searches.length, running) : ''
        }}</span>
    </div>
</template>

<style scoped>
.screen {
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
}

.tool-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 4px;
}

.query {
    flex: 1 1 320px;
    min-width: 120px;
}

.segment-count {
    margin-inline-start: 4px;
    color: var(--color-fg-muted);
}

.search-parameters {
    display: flex;
    flex-direction: column;
    gap: 10px;
    width: 400px;
    padding: 16px;
}

.popover-head,
.popover-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
}

.field-label {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.filter-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 8px;
}

.filter-chip {
    padding: 2px 9px;
    border: 1px solid var(--color-border-default);
    border-radius: 14px;
    background: var(--color-bg-default);
    font-size: 0.92em;
    cursor: pointer;
}

.filter-chip[aria-checked='true'] {
    border-color: transparent;
    background: var(--color-accent-muted);
}

.numbers {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px 16px;
}

.numbers label {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.numbers .wide {
    grid-column: 1 / 2;
}

.stop-search {
    --nj-icon-color: var(--nj-accent-strong);
}

.stop-search:disabled {
    --nj-icon-color: var(--color-fg-muted);
}

.tab-row {
    display: flex;
    align-items: flex-end;
    gap: 8px;
    border-bottom: 1px solid var(--color-border-default);
}

.search-tabs {
    display: flex;
    flex: 1;
    min-width: 0;
    gap: 2px;
    overflow-x: auto;
}

/* The tabs as the desktop app draws them: the chosen one raised, with the strong accent along its top. */
.search-tab {
    display: flex;
    align-items: center;
    gap: 6px;
    max-width: 260px;
    padding: 4px 6px 4px 10px;
    border: 1px solid var(--color-border-default);
    border-bottom: 0;
    border-radius: 6px 6px 0 0;
    background: var(--color-bg-subtle);
    cursor: pointer;
    white-space: nowrap;
}

.search-tab:hover {
    background: var(--color-bg-default);
}

.search-tab[aria-selected='true'] {
    background: var(--color-bg-default);
    box-shadow: inset 0 2px 0 var(--nj-accent-strong);
    margin-bottom: -1px;
    padding-bottom: 5px;
}

.tab-close {
    display: inline-flex;
    padding: 2px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    cursor: pointer;
    --nj-icon-size: 14px;
}

.tab-close:hover {
    background: var(--color-bg-inset);
}

/* A search running, in its tab: a turning ring in the strong accent. */
.tab-spinner {
    width: 14px;
    height: 14px;
    border: 2.5px solid var(--color-border-muted);
    border-top-color: var(--nj-accent-strong);
    border-radius: 50%;
    animation: turn 0.8s linear infinite;
}

@keyframes turn {
    to {
        transform: rotate(360deg);
    }
}

.results-filter {
    width: 260px;
    flex: none;
    margin-bottom: 4px;
}

.results-filter input {
    flex: 1;
    min-width: 0;
    border: 0;
    outline: 0;
    background: transparent;
}

.column-choice {
    display: inline-flex;
    border: 0;
    background: transparent;
    cursor: pointer;
}

.results-count {
    margin-top: 2px;
}

/* The colours of the search results: ours already in red, known from before in green, spam muted; a new result from
   the text's colour towards the accent's as it has more sources. */
.search-results :deep(.result-fresh) {
    color: color-mix(in srgb, var(--color-accent-fg) var(--nj-shade, 0%), var(--color-fg-default));
}

.search-results :deep(.result-ours) {
    color: var(--color-danger-fg);
}

.search-results :deep(.result-known) {
    color: var(--color-success-fg);
}

.search-results :deep(.result-spam) {
    color: var(--color-fg-muted);
}

.search-results :deep(.result-downloading) {
    font-weight: bold;
}

/* What is chosen in the results and what to do with it, under them. */
.selection-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px 6px 12px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-subtle);
}

.chosen-name {
    font-weight: bold;
    min-width: 0;
}

.spacer {
    flex: 1;
}

.empty-state {
    display: flex;
    flex: 1;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 12px;
    max-width: 720px;
    margin: 0 auto;
    text-align: center;
}

.empty-icon {
    --nj-icon-size: 48px;
    --nj-icon-color: var(--color-fg-muted);
}

.empty-state h2 {
    margin: 0;
    font-size: 1.55em;
}

.empty-state p {
    margin: 0;
    color: var(--color-fg-muted);
}

.tips {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 12px;
}

.search-tip {
    display: flex;
    gap: 10px;
    width: 220px;
    padding: 10px 12px;
    border-radius: 8px;
    background: var(--color-bg-subtle);
    text-align: start;
    --nj-icon-color: var(--color-accent-fg);
}

.search-tip div {
    display: flex;
    flex-direction: column;
    gap: 2px;
}

.search-tip code {
    color: var(--color-fg-muted);
    font-size: 0.92em;
    direction: ltr;
}

.visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
}
</style>
