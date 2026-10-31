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
import { computed, h, nextTick, onMounted, onUnmounted, ref, watch, type VNode } from 'vue';
import { answer, api, ApiError } from '../api/client';
import { count, size } from '../format';
import { language, t } from '../i18n';
import { htmlLinks } from '../links';
import type { IconName } from '../icons';
import { downloads, preferences, readDownloads, shared as sharedFiles, status } from '../model/engine';
import { searchRequest } from '../places';
import {
    completePercent,
    completeness,
    expression,
    filterCount,
    hasNames,
    KNOWN,
    kindOrder,
    history,
    isMedia,
    knownOf,
    link,
    merged,
    narrowed,
    playingTime,
    remember,
    resultRows,
    rowOrder,
    SortMemory,
    searches,
    shade,
    tone,
    words,
    type FileType,
    type ColumnOrder,
    type ColumnSort,
    type Filters,
    type Known,
    type Ours,
    type Result,
    type ResultRow,
    type Scope,
    type Search,
} from '../search';
import { problem } from '../toasts';
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
// Each search keeps its own table, with what is chosen in it and how far it scrolled, as the desktop app's tabs do.
const selectedByTab = ref<Record<string, string[]>>({});
// For each tab, the files found under several names that are open to show them.
const openByTab = ref<Record<string, string[]>>({});
const columnTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const tabsTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const tabList = ref<HTMLElement | null>(null);
const tabElements = new Map<string, HTMLElement>();
// The tabs do not all fit: a menu at their end lists them all.
const overflowing = ref(false);
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
// The files of ours now: what a result is to us follows them as they come and go.
const ours = computed<Ours>(() => ({
    downloading: new Set(downloads.value.map((d) => d.hash.toUpperCase())),
    shared: new Set(sharedFiles.value.map((f) => f.hash.toUpperCase())),
}));

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
    complete: { key: 'IDS_COMPLETE', text: (r) => completeness(r, t('IDS_YES')) },
    known: { key: 'nj.search.known', text: (r) => knownText(r) },
    length: { key: 'IDS_LENGTH', text: (r) => lengthText(r.media?.length ?? 0) },
    bitrate: { key: 'IDS_BITRATE', text: (r) => bitrateText(r.media?.bitrate ?? 0) },
};
// The columns the filter can look in: those the tab shown shows, in their order, when its menu opens.
const filterColumns = ref<{ id: string; title: string }[]>([]);

const rowsByTab = computed<Record<string, ResultRow[]>>(() =>
    Object.fromEntries(searches.value.map((s) => [s.key, resultRows(s.results, new Set(openByTab.value[s.key]))])),
);
// The filter beside the tabs narrows every list, by the text of the file each row shows.
const visibleByTab = computed<Record<string, ResultRow[]>>(() => {
    const text = TEXTS[filterColumn.value]?.text ?? ((r: Result) => r.name);
    return Object.fromEntries(
        Object.entries(rowsByTab.value).map(([key, rows]) => [key, narrowed(rows, filter.value, (r) => text(r.file))]),
    );
});
const counted = computed(() => {
    const all = shown.value?.results.length ?? 0;
    const visible = (visibleByTab.value[shownKey.value] ?? []).filter((r) => !r.isName).length;
    return visible === all ? String(all) : `${visible}/${all}`;
});
const chosen = computed(() => {
    const rows = new Map((rowsByTab.value[shownKey.value] ?? []).map((r) => [r.id, r]));
    return selection(shownKey.value).flatMap((id) => {
        const row = rows.get(id);
        return row === undefined ? [] : [row.result];
    });
});
const running = computed(() => searches.value.filter((s) => s.running).length);

// What a result is to us in words, on a chip of its tone with an icon of its own: the colour is never the only cue.
const STATES: Record<Known, { key: string; icon: IconName; tone: string }> = {
    downloading: { key: 'IDS_DOWNLOADING', icon: 'DOWNLOAD', tone: 'accent' },
    shared: { key: 'IDS_SHARED', icon: 'SHARED', tone: 'success' },
    downloaded: { key: 'IDS_DOWNLOADED', icon: 'CHECK_CIRCLE', tone: 'neutral' },
    cancelled: { key: 'IDS_CANCELLED', icon: 'BLOCK', tone: 'neutral' },
    spam: { key: 'IDS_SPAM', icon: 'SPAM', tone: 'warning' },
};

function known(r: Result): Known | undefined {
    return knownOf(r, ours.value);
}

function knownText(r: Result): string {
    const k = known(r);
    return k === undefined ? '' : t(STATES[k].key);
}

function lengthText(seconds: number): string {
    return seconds === 0 ? '' : playingTime(seconds);
}

function bitrateText(kbits: number): string {
    return kbits === 0 ? '' : `${count(kbits)} ${t('IDS_KBITSSEC')}`;
}

// The rules of the desktop app's menu: Download while a file chosen is not ours yet, Search Related Files where the
// server finds them, and each spam mark where it changes something.
const canDownload = computed(() => chosen.value.some((r) => known(r) !== 'downloading' && known(r) !== 'shared'));
const canRelate = computed(() => chosen.value.length > 0 && (status.value?.relatedSearch ?? false));
const canMarkSpam = computed(() => chosen.value.some((r) => !r.spam) && shown.value?.id !== null);
const canMarkNotSpam = computed(() => chosen.value.some((r) => r.spam) && shown.value?.id !== null);

function listFilterColumns(open: boolean): void {
    if (open) {
        filterColumns.value = (tables.get(shownKey.value)?.visibleColumns() ?? []).map((c) => ({
            id: c.id as string,
            title: String(c.columnDef.header),
        }));
    }
}

// Names and media texts may be in any language: each reads in the direction of its first letter. Figures, hashes and
// codecs read left to right in every language; both keep to the side where the layout starts.
function ownDirection(text: string): VNode {
    return h('span', { dir: 'auto' }, text);
}

function leftToRight(text: string): VNode {
    return h('span', { dir: 'ltr' }, text);
}

// The columns of the songs and films, shown while a list holds some until its column menu shows or hides one.
const MEDIA = ['artist', 'album', 'title', 'length', 'bitrate', 'codec'];
const media: ColumnMeta = { hidden: true };

// The name of a file; one found under several names opens to show them, and closes, on its arrow. Its names show
// under it, one step further in.
function nameCell(s: Search, row: ResultRow): VNode {
    const opens = !row.isName && hasNames(row.file);
    const open = opens && (openByTab.value[s.key] ?? []).includes(row.file.hash);
    const sign = h(
        'span',
        {
            class: ['names-sign', { 'name-row': row.isName, opens }],
            onClick: opens ? () => openNames(s, [row.file.hash], !open) : undefined,
            // A double click on the arrow opens and closes, never downloads.
            onDblclick: (e: MouseEvent) => e.stopPropagation(),
        },
        opens ? [h(Icon, { name: open ? 'CHEVRON_DOWN' : 'CHEVRON_RIGHT' })] : [],
    );
    return h('span', { class: 'name-cell' }, [sign, h('span', { class: 'ellipsis', dir: 'auto' }, row.result.name)]);
}

function openNames(s: Search, hashes: string[], open: boolean): void {
    const now = new Set(openByTab.value[s.key]);
    for (const hash of hashes) {
        if (open) {
            now.add(hash);
        } else {
            now.delete(hash);
        }
    }
    openByTab.value = { ...openByTab.value, [s.key]: [...now] };
}

// + and - open and close the files chosen that were found under several names.
function namesKeys(s: Search, e: KeyboardEvent): void {
    if (e.key !== '+' && e.key !== '-') {
        return;
    }
    const rows = new Map((rowsByTab.value[s.key] ?? []).map((r) => [r.id, r]));
    const files = selection(s.key).flatMap((id) => rows.get(id)?.file.hash ?? []);
    openNames(s, files, e.key === '+');
    e.preventDefault();
}

function columns(s: Search): ColumnDef<any, ResultRow, any>[] {
    const all: ColumnDef<any, ResultRow, any>[] = [
        {
            id: 'name',
            header: t('IDS_DL_FILENAME'),
            accessorFn: (r: ResultRow) => r.result.name,
            size: 380,
            minSize: 100,
            cell: ({ row }: any) => nameCell(s, row.original as ResultRow),
        },
        {
            id: 'size',
            header: t('IDS_DL_SIZE'),
            accessorFn: (r: ResultRow) => r.result.size,
            size: 90,
            cell: ({ getValue }: any) => leftToRight(size(getValue())),
        },
        {
            id: 'sources',
            header: t('IDS_SEARCHAVAIL'),
            accessorFn: (r: ResultRow) => r.result.sources,
            size: 90,
            cell: ({ getValue }: any) => leftToRight(String(getValue())),
        },
        {
            id: 'complete',
            header: t('IDS_COMPLETE'),
            accessorFn: (r: ResultRow) => completePercent(r.result),
            size: 90,
            cell: ({ row }: any) => leftToRight(completeness((row.original as ResultRow).result, t('IDS_YES'))),
        },
        {
            id: 'type',
            header: t('nj.search.type'),
            accessorFn: (r: ResultRow) => r.result.type ?? '',
            size: 90,
            cell: ({ getValue }: any) => leftToRight(getValue()),
        },
        {
            id: 'known',
            header: t('nj.search.known'),
            accessorFn: (r: ResultRow) => known(r.result) ?? '',
            size: 150,
            cell: ({ getValue }: any) => {
                const state = STATES[getValue() as Known];
                return state === undefined
                    ? ''
                    : h('span', { class: ['state-chip', state.tone] }, [
                          h(Icon, { name: state.icon }),
                          h('span', { class: 'ellipsis' }, t(state.key)),
                      ]);
            },
        },
        {
            id: 'hash',
            header: t('IDS_FILEID'),
            accessorFn: (r: ResultRow) => r.result.hash,
            size: 220,
            meta: { hidden: true } satisfies ColumnMeta,
            cell: ({ getValue }: any) => leftToRight(getValue()),
        },
        {
            id: 'artist',
            header: t('IDS_ARTIST'),
            accessorFn: (r: ResultRow) => r.result.media?.artist ?? '',
            size: 100,
            meta: media,
            cell: ({ getValue }: any) => ownDirection(getValue()),
        },
        {
            id: 'album',
            header: t('IDS_ALBUM'),
            accessorFn: (r: ResultRow) => r.result.media?.album ?? '',
            size: 100,
            meta: media,
            cell: ({ getValue }: any) => ownDirection(getValue()),
        },
        {
            id: 'title',
            header: t('IDS_TITLE'),
            accessorFn: (r: ResultRow) => r.result.media?.title ?? '',
            size: 100,
            meta: media,
            cell: ({ getValue }: any) => ownDirection(getValue()),
        },
        {
            id: 'length',
            header: t('IDS_LENGTH'),
            accessorFn: (r: ResultRow) => r.result.media?.length ?? 0,
            size: 60,
            meta: media,
            cell: ({ getValue }: any) => leftToRight(lengthText(getValue())),
        },
        {
            id: 'bitrate',
            header: t('IDS_BITRATE'),
            accessorFn: (r: ResultRow) => r.result.media?.bitrate ?? 0,
            size: 80,
            meta: media,
            cell: ({ getValue }: any) => leftToRight(bitrateText(getValue())),
        },
        {
            id: 'codec',
            header: t('IDS_CODEC'),
            accessorFn: (r: ResultRow) => r.result.media?.codec ?? '',
            size: 60,
            meta: media,
            cell: ({ getValue }: any) => leftToRight(getValue()),
        },
    ];
    // Every column sorts the rows as the desktop app does, the least first at the first click.
    return all.map((c) => ({ ...c, sortFn: sortRows, sortDescFirst: false }));
}

// Names and texts in the order of the interface's language, case aside; values a result lacks go last.
const collator = computed(() => new Intl.Collator(language.value, { sensitivity: 'accent' }));
const text = (a: string, b: string) => collator.value.compare(a, b);
const ORDERS: Record<string, ColumnOrder> = {
    name: { compare: (a, b) => text(a.name, b.name) },
    size: { compare: (a, b) => a.size - b.size },
    sources: { compare: (a, b) => a.sources - b.sources },
    complete: { compare: (a, b) => completePercent(a) - completePercent(b) },
    type: { compare: kindOrder },
    known: { compare: (a, b) => KNOWN.indexOf(known(a)!) - KNOWN.indexOf(known(b)!) },
    hash: { compare: (a, b) => (a.hash < b.hash ? -1 : a.hash > b.hash ? 1 : 0) },
    artist: { compare: (a, b) => text(a.media?.artist ?? '', b.media?.artist ?? ''), missing: (r) => !r.media?.artist },
    album: { compare: (a, b) => text(a.media?.album ?? '', b.media?.album ?? ''), missing: (r) => !r.media?.album },
    title: { compare: (a, b) => text(a.media?.title ?? '', b.media?.title ?? ''), missing: (r) => !r.media?.title },
    length: { compare: (a, b) => (a.media?.length ?? 0) - (b.media?.length ?? 0), missing: (r) => !r.media?.length },
    bitrate: {
        compare: (a, b) => (a.media?.bitrate ?? 0) - (b.media?.bitrate ?? 0),
        missing: (r) => !r.media?.bitrate,
    },
    codec: { compare: (a, b) => text(a.media?.codec ?? '', b.media?.codec ?? ''), missing: (r) => !r.media?.codec },
};

// What each table was sorted by, and the order of its rows for the sorting it has now.
const sorted = new WeakMap<
    object,
    { memory: SortMemory; sorting?: unknown; order?: (a: ResultRow, b: ResultRow) => number }
>();

// The table asks the first column it sorts by; the order covers them all, turned for that column's way, which the
// table turns back.
function sortRows(a: any, b: any, columnId: string): number {
    const table = a.table;
    const sorting: ColumnSort[] = table.atoms.sorting?.get() ?? [];
    const state = sorted.get(table) ?? { memory: new SortMemory() };
    sorted.set(table, state);
    if (state.sorting !== sorting || state.order === undefined) {
        state.sorting = sorting;
        state.order = rowOrder(sorting, state.memory.earlier(sorting), ORDERS);
    }
    const order = state.order(a.original as ResultRow, b.original as ResultRow);
    return sorting.find((s) => s.id === columnId)?.desc ? -order : order;
}

/** What the screen reaches of a tab's table: its columns, through which the media columns are shown or hidden. */
interface TableHandle {
    visibleColumns: () => any[];
}

const tables = new Map<string, TableHandle>();
const followed = new WeakSet<TableHandle>();
// For each tab: the media columns' visibility set last by the results, until the column menu chose otherwise.
const mediaShown = new Map<string, { set: boolean | null; chosen: boolean }>();

function keepTable(s: Search, table: unknown): void {
    if (table !== null && typeof (table as TableHandle).visibleColumns === 'function') {
        tables.set(s.key, table as TableHandle);
        // A function ref comes again at each update: a table follows its results from the first time only.
        if (!followed.has(table as TableHandle)) {
            followed.add(table as TableHandle);
            void nextTick(() => followMedia(s));
        }
    } else {
        tables.delete(s.key);
    }
}

// The media columns of a list show while it holds songs or films, until its column menu shows or hides one of them.
function followMedia(s: Search): void {
    const state = mediaShown.get(s.key) ?? { set: null, chosen: false };
    mediaShown.set(s.key, state);
    const table = tables.get(s.key)?.visibleColumns()[0]?.table;
    if (state.chosen || table === undefined) {
        return;
    }
    const all = MEDIA.flatMap((id) => table.getColumn(id) ?? []);
    if (state.set !== null && all.some((c: any) => c.getIsVisible() !== state.set)) {
        state.chosen = true;
        return;
    }
    const wanted = s.results.some(isMedia);
    for (const c of all) {
        if (c.getIsVisible() !== wanted) {
            c.toggleVisibility(wanted);
        }
    }
    state.set = wanted;
}

watch(
    () => searches.value.map((s) => s.results),
    () => searches.value.forEach(followMedia),
);

// Ours already in red, known from before in green, spam muted; a new result from the text's colour towards the
// accent's as it has more sources. A file being downloaded stands out in bold.
function rowClass(row: ResultRow): string[] {
    const k = known(row.result);
    return [`result-${tone(k)}`, k === 'downloading' ? 'result-downloading' : ''];
}

function rowStyle(row: ResultRow): Record<string, string> {
    return { '--nj-shade': `${shade(row.result.sources)}%` };
}

function selection(key: string): string[] {
    return selectedByTab.value[key] ?? [];
}

function choose(key: string, ids: string[]): void {
    selectedByTab.value = { ...selectedByTab.value, [key]: ids };
}

function keepTab(key: string, element: unknown): void {
    if (element instanceof HTMLElement) {
        tabElements.set(key, element);
    } else {
        tabElements.delete(key);
    }
}

function shownAt(): number {
    return searches.value.findIndex((s) => s.key === shownKey.value);
}

// Shows the tab at {@code index}, going round past either end, and gives it the focus as the desktop app's tabs do.
function showTab(index: number): void {
    const all = searches.value.length;
    const s = searches.value[((index % all) + all) % all];
    if (s !== undefined) {
        shownKey.value = s.key;
        void nextTick(() => tabElements.get(s.key)?.focus());
    }
}

// Ctrl+Tab and Ctrl+Page Down show the next search, Ctrl+Shift+Tab and Ctrl+Page Up the one before.
function tabKeys(e: KeyboardEvent): boolean {
    if (!e.ctrlKey || e.altKey || e.metaKey || searches.value.length === 0) {
        return false;
    }
    const step = e.key === 'Tab' ? (e.shiftKey ? -1 : 1) : e.key === 'PageDown' ? 1 : e.key === 'PageUp' ? -1 : 0;
    if (step === 0) {
        return false;
    }
    e.preventDefault();
    showTab(shownAt() + step);
    return true;
}

// On the tabs the arrows go to the next or the one before, in the reading direction; Home and End to either end.
function tabListKeys(e: KeyboardEvent): void {
    if (tabKeys(e) || e.ctrlKey || e.altKey || e.metaKey) {
        return;
    }
    const rtl = getComputedStyle(e.currentTarget as Element).direction === 'rtl';
    const steps: Record<string, number> = {
        ArrowLeft: rtl ? 1 : -1,
        ArrowRight: rtl ? -1 : 1,
        ArrowUp: -1,
        ArrowDown: 1,
    };
    if (steps[e.key] !== undefined) {
        showTab(shownAt() + steps[e.key]!);
    } else if (e.key === 'Home' || e.key === 'End') {
        showTab(e.key === 'Home' ? 0 : searches.value.length - 1);
    } else {
        return;
    }
    e.preventDefault();
}

function measureTabs(): void {
    const list = tabList.value;
    overflowing.value = list !== null && list.scrollWidth > list.clientWidth + 1;
}

const tabsObserver = new ResizeObserver(measureTabs);

watch(tabList, (now, before) => {
    if (before) {
        tabsObserver.unobserve(before);
    }
    if (now) {
        tabsObserver.observe(now);
    }
});

watch(
    () => searches.value.map((s) => title(s)).join('\n'),
    () => void nextTick(measureTabs),
);

// The tab shown is scrolled into sight, as the desktop app's tab row does.
watch(
    shownKey,
    (key) => void nextTick(() => tabElements.get(key)?.scrollIntoView({ block: 'nearest', inline: 'nearest' })),
);

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
    const { [s.key]: _chosen, ...othersChosen } = selectedByTab.value;
    selectedByTab.value = othersChosen;
    const { [s.key]: _open, ...othersOpen } = openByTab.value;
    openByTab.value = othersOpen;
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

// A search the engine cannot widen, or no longer knows, stays as it is without a word.
async function more(): Promise<void> {
    const s = shown.value;
    if (s?.id === null || s === undefined) {
        return;
    }
    try {
        await answer(api().POST('/searches/{id}/more', { params: { path: { id: s.id } } }));
        s.running = true;
    } catch (err) {
        if (!(err instanceof ApiError && (err.status === 404 || err.status === 409))) {
            problem((err as Error).message);
        }
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

// A file chosen with its names downloads once, under the first name chosen, without a word as in the desktop app.
async function download(results: Result[]): Promise<void> {
    const files = new Map<string, Result>();
    for (const r of results) {
        if (!files.has(r.hash)) {
            files.set(r.hash, r);
        }
    }
    for (const r of files.values()) {
        try {
            await addDownload(shown.value, r);
        } catch (err) {
            problem((err as Error).message);
        }
    }
    await readDownloads();
}

// Through the search while the engine still has it, which adds the file's AICH hash; under another name, or once the
// engine forgot the search, from the file's link.
async function addDownload(s: Search | undefined, r: Result): Promise<void> {
    const file = s?.results.find((f) => f.hash === r.hash);
    if (s !== undefined && s.id !== null && file?.name === r.name) {
        try {
            await answer(
                api().POST('/searches/{id}/download', { params: { path: { id: s.id } }, body: { hash: r.hash } }),
            );
            return;
        } catch (err) {
            if (!(err instanceof ApiError && err.status === 404)) {
                throw err;
            }
        }
    }
    await answer(api().POST('/downloads', { body: { links: [link(r)] } }));
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
        // The engine lists them by sources; the desktop app keeps the order they came in until a column sorts them.
        s.results = merged(s.results, got.results);
        s.running = got.running;
    } catch (err) {
        // A search the engine forgot keeps the results it showed, as one brought back from the last exit.
        s.running = false;
        if (err instanceof ApiError && err.status === 404) {
            s.id = null;
        }
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
onUnmounted(() => {
    window.clearInterval(timer);
    tabsObserver.disconnect();
});
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
                <div ref="tabList" class="search-tabs" role="tablist" @keydown="tabListKeys">
                    <Tip v-for="s in searches" :key="s.key" :text="s.expression">
                        <div
                            :ref="(el) => keepTab(s.key, el)"
                            class="search-tab"
                            role="tab"
                            :aria-selected="s.key === shownKey"
                            :tabindex="s.key === shownKey ? 0 : -1"
                            @click="shownKey = s.key"
                        >
                            <span v-if="s.running" class="tab-spinner" aria-hidden="true" />
                            <span class="ellipsis" dir="auto">{{ title(s) }}</span>
                            <button
                                class="tab-close"
                                tabindex="-1"
                                :aria-label="t('IDS_FD_CLOSE')"
                                @click.stop="close(s)"
                            >
                                <Icon name="CLOSE" />
                            </button>
                        </div>
                    </Tip>
                </div>
                <DropdownMenuRoot v-if="overflowing">
                    <DropdownMenuTrigger
                        ref="tabsTrigger"
                        class="button flat small tabs-menu"
                        :aria-label="t('IDS_MORE')"
                    >
                        <Icon name="CHEVRON_DOWN" />
                    </DropdownMenuTrigger>
                    <DropdownMenuPortal>
                        <DropdownMenuContent class="menu" align="end" :reference="tabsTrigger?.$el">
                            <DropdownMenuRadioGroup v-model="shownKey">
                                <DropdownMenuRadioItem
                                    v-for="s in searches"
                                    :key="s.key"
                                    class="menu-item"
                                    :value="s.key"
                                >
                                    <span class="menu-check"><Icon v-if="s.key === shownKey" name="CHECK" /></span
                                    ><span class="ellipsis" dir="auto">{{ title(s) }}</span>
                                </DropdownMenuRadioItem>
                            </DropdownMenuRadioGroup>
                        </DropdownMenuContent>
                    </DropdownMenuPortal>
                </DropdownMenuRoot>
                <button class="button flat small" @click="more"><Icon name="ADD" />{{ t('nj.search.more') }}</button>
                <button class="button flat small stop-search" :disabled="!shown?.running" @click="stop">
                    <Icon name="STOP" />{{ t('IDS_DL_STOP') }}
                </button>
                <div class="field results-filter">
                    <DropdownMenuRoot @update:open="listFilterColumns">
                        <Tip :text="t('nj.search.filterColumn')">
                            <DropdownMenuTrigger
                                ref="columnTrigger"
                                class="column-choice"
                                :aria-label="t('nj.search.filterColumn')"
                            >
                                <Icon name="SEARCH" />
                            </DropdownMenuTrigger>
                        </Tip>
                        <DropdownMenuPortal>
                            <DropdownMenuContent class="menu" align="start" :reference="columnTrigger?.$el">
                                <DropdownMenuRadioGroup v-model="filterColumn">
                                    <DropdownMenuRadioItem
                                        v-for="c in filterColumns"
                                        :key="c.id"
                                        class="menu-item"
                                        :value="c.id"
                                    >
                                        <span class="menu-check"
                                            ><Icon v-if="filterColumn === c.id" name="CHECK"
                                        /></span>
                                        {{ c.title }}
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
                    <button
                        v-if="filter !== ''"
                        type="button"
                        class="filter-clear"
                        :aria-label="t('IDS_PW_RESET')"
                        @click="filter = ''"
                    >
                        <Icon name="CLOSE" />
                    </button>
                </div>
            </div>
            <strong class="results-count">{{ t('nj.search.count', counted) }}</strong>
            <div class="tab-tables" @keydown="tabKeys">
                <div
                    v-for="s in searches"
                    :key="s.key"
                    class="tab-table"
                    :class="{ shown: s.key === shownKey }"
                    role="tabpanel"
                >
                    <DataTable
                        :ref="(table) => keepTable(s, table)"
                        :selected="selection(s.key)"
                        class="search-results"
                        name="search"
                        :rows="visibleByTab[s.key] ?? []"
                        :columns="columns(s)"
                        :row-id="(r: ResultRow) => r.id"
                        :row-class="rowClass"
                        :row-style="rowStyle"
                        :label="title(s)"
                        :columns-label="t('IDS_DL_FILENAME')"
                        @update:selected="(ids: string[]) => choose(s.key, ids)"
                        @keydown="(e: KeyboardEvent) => namesKeys(s, e)"
                        @activate="(r: ResultRow) => download([r.result])"
                    >
                        <template #empty>
                            <span class="muted">{{ t('nj.search.waiting') }}</span>
                        </template>
                        <template #menu>
                            <ContextMenuItem class="menu-item" :disabled="!canDownload" @select="download(chosen)">
                                <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                            </ContextMenuItem>
                            <ContextMenuItem
                                class="menu-item"
                                :disabled="chosen.length === 0"
                                @select="copyLinks(false)"
                            >
                                <Icon name="COPY" />{{ t('nj.copyLink') }}
                            </ContextMenuItem>
                            <ContextMenuItem
                                class="menu-item"
                                :disabled="chosen.length === 0"
                                @select="copyLinks(true)"
                            >
                                <Icon name="CODE" />{{ t('IDS_DL_LINK2') }}
                            </ContextMenuItem>
                            <ContextMenuItem class="menu-item" :disabled="!canRelate" @select="searchRelated">
                                <Icon name="SEARCH" />{{ t('IDS_SEARCHRELATED') }}
                            </ContextMenuItem>
                            <ContextMenuSeparator class="menu-separator" />
                            <ContextMenuItem class="menu-item" :disabled="!canMarkSpam" @select="markSpam(true)">
                                <Icon name="BLOCK" />{{ t('IDS_MARKSPAM') }}
                            </ContextMenuItem>
                            <ContextMenuItem class="menu-item" :disabled="!canMarkNotSpam" @select="markSpam(false)">
                                <Icon name="CHECK" />{{ t('IDS_MARKNOTSPAM') }}
                            </ContextMenuItem>
                        </template>
                    </DataTable>
                </div>
            </div>
            <div v-if="chosen.length > 0" id="selection-bar" class="selection-bar">
                <span class="chosen-name ellipsis" dir="auto">{{
                    chosen.length === 1 ? chosen[0]!.name : t('nj.search.chosen', chosen.length)
                }}</span>
                <span v-if="chosen.length === 1" class="muted"
                    >· {{ size(chosen[0]!.size) }} · {{ t('IDS_DL_SOURCES') }}: {{ chosen[0]!.sources }}</span
                >
                <span class="spacer" />
                <button class="button" @click="download(chosen)">
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
                        <DropdownMenuTrigger ref="moreTrigger" class="button icon-only" :aria-label="t('IDS_MORE')"
                            ><Icon name="MORE"
                        /></DropdownMenuTrigger>
                    </Tip>
                    <DropdownMenuPortal>
                        <DropdownMenuContent class="menu" align="end" :reference="moreTrigger?.$el">
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

:deep(.search-parameters) {
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
    scrollbar-width: none;
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
    outline: none;
}

.search-tab:hover {
    background: var(--color-bg-default);
}

.search-tab:focus-visible {
    box-shadow: inset 0 0 0 2px var(--color-accent-emphasis);
}

.search-tab[aria-selected='true'] {
    background: var(--color-bg-default);
    box-shadow: inset 0 2px 0 var(--nj-accent-strong);
    margin-bottom: -1px;
    padding-bottom: 5px;
}

.search-tab[aria-selected='true']:focus-visible {
    box-shadow:
        inset 0 2px 0 var(--nj-accent-strong),
        inset 0 0 0 2px var(--color-accent-emphasis);
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

.tabs-menu {
    flex: none;
    margin-bottom: 2px;
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

.filter-clear {
    display: inline-flex;
    padding: 2px;
    border: 0;
    border-radius: 4px;
    background: transparent;
    cursor: pointer;
    --nj-icon-size: 14px;
}

.filter-clear:hover {
    background: var(--color-bg-inset);
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

/* The tables of all the searches lie on each other, the shown one on top: the others keep their scroll. */
.tab-tables {
    display: grid;
    flex: 1;
    min-height: 0;
}

.tab-table {
    display: flex;
    grid-area: 1 / 1;
    min-height: 0;
    min-width: 0;
}

.tab-table:not(.shown) {
    visibility: hidden;
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

/* The arrow of a result found under several names; its names show one step further in. */
.search-results :deep(.name-cell) {
    display: flex;
    align-items: center;
    min-width: 0;
}

.search-results :deep(.names-sign) {
    display: inline-flex;
    flex: none;
    justify-content: center;
    width: 16px;
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-fg-muted);
}

.search-results :deep(.names-sign.name-row) {
    width: 32px;
}

.search-results :deep(.names-sign.opens) {
    cursor: pointer;
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
