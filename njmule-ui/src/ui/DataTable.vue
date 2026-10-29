<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts" generic="T extends Record<string, any>">
import {
    columnResizingFeature,
    columnSizingFeature,
    columnVisibilityFeature,
    createSortedRowModel,
    FlexRender,
    rowSortingFeature,
    sortFns,
    tableFeatures,
    useTable,
    type ColumnDef,
    type ColumnSizingState,
    type ColumnVisibilityState,
    type SortingState,
    type Updater,
} from '@tanstack/vue-table';
import { useVirtualizer } from '@tanstack/vue-virtual';
import {
    ContextMenuContent,
    ContextMenuPortal,
    ContextMenuRoot,
    ContextMenuTrigger,
    DropdownMenuCheckboxItem,
    DropdownMenuContent,
    DropdownMenuItemIndicator,
    DropdownMenuPortal,
    DropdownMenuRoot,
    DropdownMenuTrigger,
} from 'reka-ui';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import Icon from './Icon.vue';

// A table as the desktop app's: rows of 32 px drawn only where they show, sorted by a click on a header, columns
// resized by their edge and shown or hidden from the menu at the header's end, all kept in this window for the next
// time. A click chooses a row, Ctrl or Cmd adds one, Shift a range; the context menu acts on the rows chosen.

export interface ColumnMeta {
    /** Figures align to the end, in the order of their digits whatever the language. */
    numeric?: boolean;
    /** Hidden until the user shows it from the column menu. */
    hidden?: boolean;
}

const props = defineProps<{
    rows: T[];
    columns: ColumnDef<any, T, any>[];
    rowId: (row: T) => string;
    /** The name the layout is kept under. */
    name: string;
    /** Lines shown under a row, such as the sources of an opened download. */
    below?: (row: T) => boolean;
    label: string;
    /** The name of the menu that shows and hides the columns. */
    columnsLabel: string;
    /** Classes and styles of a row besides the table's own, such as the colour of what a search result is to us. */
    rowClass?: (row: T) => string | string[];
    rowStyle?: (row: T) => Record<string, string>;
}>();
const emit = defineEmits<{ activate: [row: T]; keydown: [event: KeyboardEvent] }>();
const selected = defineModel<string[]>('selected', { required: true });

const ROW = 32;
const layoutKey = `njmule.table.${props.name}`;

interface Layout {
    sorting?: SortingState;
    visibility?: ColumnVisibilityState;
    sizing?: ColumnSizingState;
}

function read(): Layout {
    try {
        return JSON.parse(localStorage.getItem(layoutKey) ?? '{}') as Layout;
    } catch {
        return {};
    }
}

const saved = read();
const sorting = ref<SortingState>(saved.sorting ?? []);
const visibility = ref<ColumnVisibilityState>(
    saved.visibility ??
        Object.fromEntries(
            props.columns.filter((c) => (c.meta as ColumnMeta | undefined)?.hidden).map((c) => [c.id as string, false]),
        ),
);
const sizing = ref<ColumnSizingState>(saved.sizing ?? {});

watch([sorting, visibility, sizing], () => {
    try {
        localStorage.setItem(
            layoutKey,
            JSON.stringify({ sorting: sorting.value, visibility: visibility.value, sizing: sizing.value }),
        );
    } catch {
        // Kept until the window closes.
    }
});

function update<S>(target: { value: S }) {
    return (next: Updater<S>) => {
        target.value = typeof next === 'function' ? (next as (old: S) => S)(target.value) : next;
    };
}

const features = tableFeatures({
    rowSortingFeature,
    columnVisibilityFeature,
    columnSizingFeature,
    columnResizingFeature,
    sortedRowModel: createSortedRowModel(),
    sortFns,
});

const table = useTable({
    features,
    columns: props.columns,
    // A computed, not a getter: the adapter unwraps refs only.
    data: computed(() => props.rows),
    getRowId: (row: T) => props.rowId(row),
    state: computed(() => ({ sorting: sorting.value, columnVisibility: visibility.value, columnSizing: sizing.value })),
    onSortingChange: update(sorting),
    onColumnVisibilityChange: update(visibility),
    onColumnSizingChange: update(sizing),
    columnResizeMode: 'onChange',
    enableColumnResizing: true,
} as any);

const tableRows = computed(() => (table as any).getRowModel().rows as any[]);
const scroller = ref<HTMLElement | null>(null);
const virtualizer = useVirtualizer(
    computed(() => ({
        count: tableRows.value.length,
        getScrollElement: () => scroller.value,
        estimateSize: () => ROW,
        getItemKey: (index: number) => tableRows.value[index]!.id as string,
        overscan: 8,
    })),
);
const total = computed(() => (table as any).getTotalSize() as number);
// As the desktop app's tables, the columns fill the list's width, wider or narrower in proportion to their own, never
// narrower than their least; the list scrolls sideways only when even that does not fit.
const room = ref(0);
const stretch = computed(() => (total.value > 0 && room.value > 0 ? room.value / total.value : 1));
const width = computed(() =>
    (table as any).getVisibleLeafColumns().reduce((sum: number, c: any) => sum + columnWidth(c), 0),
);
const header = ref<HTMLElement | null>(null);
let observer: ResizeObserver | null = null;

function columnWidth(column: any): number {
    return Math.max(column.columnDef.minSize ?? 24, Math.floor((column.getSize() as number) * stretch.value));
}

onMounted(() => {
    observer = new ResizeObserver(() => (room.value = scroller.value?.clientWidth ?? 0));
    if (scroller.value) {
        observer.observe(scroller.value);
    }
});

onUnmounted(() => observer?.disconnect());

// The header follows the rows sideways.
function scrolled(): void {
    if (header.value && scroller.value) {
        header.value.scrollLeft = scroller.value.scrollLeft;
    }
}

// The row the next Shift+click goes from.
let anchor: string | null = null;

function choose(row: any, e: MouseEvent): void {
    const id = row.id as string;
    if (e.shiftKey && anchor !== null) {
        const ids = tableRows.value.map((r) => r.id as string);
        const [from, to] = [ids.indexOf(anchor), ids.indexOf(id)].sort((a, b) => a - b);
        selected.value = ids.slice(from, (to ?? 0) + 1);
        return;
    }
    if (e.ctrlKey || e.metaKey) {
        selected.value = selected.value.includes(id) ? selected.value.filter((s) => s !== id) : [...selected.value, id];
    } else {
        selected.value = [id];
    }
    anchor = id;
}

// A right click on a row not chosen chooses it alone, as the context menu acts on the rows chosen.
function menuOn(row: any): void {
    if (!selected.value.includes(row.id)) {
        selected.value = [row.id];
        anchor = row.id;
    }
}

function key(e: KeyboardEvent): void {
    const ids = tableRows.value.map((r) => r.id as string);
    const at = ids.indexOf(selected.value.at(-1) ?? '');
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        const next = ids[Math.max(0, Math.min(ids.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)))];
        if (next !== undefined) {
            selected.value = [next];
            anchor = next;
            virtualizer.value.scrollToIndex(ids.indexOf(next));
        }
        e.preventDefault();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
        selected.value = ids;
        e.preventDefault();
    } else if (e.key === 'Enter' && selected.value.length === 1) {
        const row = tableRows.value.find((r) => r.id === selected.value[0]);
        if (row) {
            emit('activate', row.original as T);
        }
        e.preventDefault();
    } else {
        emit('keydown', e);
    }
}

function sortIcon(column: any): string {
    const sorted = column.getIsSorted();
    return sorted === 'asc' ? '▲' : sorted === 'desc' ? '▼' : '';
}

function numeric(column: any): boolean {
    return (column.columnDef.meta as ColumnMeta | undefined)?.numeric === true;
}

// Rows that went away leave the choice.
watch(tableRows, (now) => {
    const present = new Set(now.map((r) => r.id as string));
    if (selected.value.some((id) => !present.has(id))) {
        selected.value = selected.value.filter((id) => present.has(id));
    }
});

defineExpose({ visibleColumns: () => (table as any).getVisibleLeafColumns() as any[] });
</script>

<template>
    <div class="data-table" role="grid" :aria-label="label" :aria-rowcount="tableRows.length">
        <div class="header">
            <div ref="header" class="header-scroll">
                <div class="header-row" :style="{ width: `${width}px` }" role="row">
                    <div
                        v-for="header in (table as any).getHeaderGroups()[0].headers"
                        :key="header.id"
                        class="header-cell"
                        role="columnheader"
                        :aria-sort="
                            header.column.getIsSorted() === 'asc'
                                ? 'ascending'
                                : header.column.getIsSorted() === 'desc'
                                  ? 'descending'
                                  : undefined
                        "
                        :style="{ width: `${columnWidth(header.column)}px` }"
                        @click="header.column.getToggleSortingHandler()?.($event)"
                    >
                        <span class="ellipsis"><FlexRender :header="header" /></span>
                        <span class="sort">{{ sortIcon(header.column) }}</span>
                        <span
                            class="resizer"
                            @mousedown.stop="header.getResizeHandler()($event)"
                            @touchstart.stop="header.getResizeHandler()($event)"
                            @click.stop
                        />
                    </div>
                </div>
            </div>
            <DropdownMenuRoot>
                <DropdownMenuTrigger class="column-menu" :aria-label="columnsLabel">⋮</DropdownMenuTrigger>
                <DropdownMenuPortal>
                    <DropdownMenuContent class="menu" align="end">
                        <DropdownMenuCheckboxItem
                            v-for="column in (table as any).getAllLeafColumns()"
                            :key="column.id"
                            class="menu-item"
                            :model-value="column.getIsVisible()"
                            @update:model-value="column.toggleVisibility($event)"
                        >
                            <span class="menu-check"
                                ><DropdownMenuItemIndicator><Icon name="CHECK" /></DropdownMenuItemIndicator
                            ></span>
                            {{ column.columnDef.header }}
                        </DropdownMenuCheckboxItem>
                    </DropdownMenuContent>
                </DropdownMenuPortal>
            </DropdownMenuRoot>
        </div>
        <ContextMenuRoot>
            <ContextMenuTrigger as-child>
                <div ref="scroller" class="body-scroll focusable" tabindex="0" @keydown="key" @scroll="scrolled">
                    <div v-if="tableRows.length === 0" class="placeholder"><slot name="empty" /></div>
                    <div class="sheet" :style="{ height: `${virtualizer.getTotalSize()}px`, width: `${width}px` }">
                        <div
                            v-for="item in virtualizer.getVirtualItems()"
                            :key="String(item.key)"
                            :ref="(el) => el && virtualizer.measureElement(el as Element)"
                            :data-index="item.index"
                            class="row"
                            :class="[
                                rowClass?.(tableRows[item.index].original),
                                {
                                    selected: selected.includes(tableRows[item.index].id),
                                    opened: below?.(tableRows[item.index].original),
                                },
                            ]"
                            role="row"
                            :aria-selected="selected.includes(tableRows[item.index].id)"
                            :style="{
                                ...rowStyle?.(tableRows[item.index].original),
                                transform: `translateY(${item.start}px)`,
                            }"
                            @click="choose(tableRows[item.index], $event)"
                            @dblclick="emit('activate', tableRows[item.index].original)"
                            @contextmenu="menuOn(tableRows[item.index])"
                        >
                            <div class="cells">
                                <div
                                    v-for="cell in tableRows[item.index].getVisibleCells()"
                                    :key="cell.id"
                                    class="cell"
                                    :class="{ numeric: numeric(cell.column) }"
                                    role="gridcell"
                                    :style="{ width: `${columnWidth(cell.column)}px` }"
                                >
                                    <span class="cell-content"><FlexRender :cell="cell" /></span>
                                </div>
                            </div>
                            <slot
                                v-if="below?.(tableRows[item.index].original)"
                                name="below"
                                :row="tableRows[item.index].original"
                                :columns="
                                    tableRows[item.index]
                                        .getVisibleCells()
                                        .map((c: any) => ({ id: c.column.id as string, width: columnWidth(c.column) }))
                                "
                            />
                        </div>
                    </div>
                </div>
            </ContextMenuTrigger>
            <ContextMenuPortal>
                <ContextMenuContent class="menu">
                    <slot name="menu" />
                </ContextMenuContent>
            </ContextMenuPortal>
        </ContextMenuRoot>
    </div>
</template>

<style scoped>
.data-table {
    position: relative;
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
    min-width: 0;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
    overflow: hidden;
}

.header {
    position: relative;
    flex: none;
    background: var(--color-bg-subtle);
    border-bottom: 1px solid var(--color-border-default);
}

.header-scroll {
    display: flex;
    overflow: hidden;
}

.header-row {
    display: flex;
    flex: none;
}

.header-cell {
    position: relative;
    display: flex;
    align-items: center;
    gap: 4px;
    flex: none;
    height: 34px;
    padding: 0 8px;
    font-weight: bold;
    border-inline-end: 1px solid var(--color-border-muted);
    cursor: default;
    user-select: none;
}

.header-cell .sort {
    font-size: 0.7em;
    color: var(--color-fg-muted);
}

.resizer {
    position: absolute;
    top: 0;
    inset-inline-end: -3px;
    width: 6px;
    height: 100%;
    cursor: col-resize;
    z-index: 1;
}

.column-menu {
    position: absolute;
    inset-inline-end: 0;
    top: 0;
    height: 34px;
    background: var(--color-bg-subtle);
    width: 16px;
    border: 0;
    background: transparent;
    cursor: pointer;
    --nj-icon-size: 14px;
}

.body-scroll {
    position: relative;
    flex: 1;
    min-height: 0;
    overflow: auto;
    /* The room for the vertical bar is kept: the columns' share does not change as it comes and goes. */
    scrollbar-gutter: stable;
    outline: none;
}

.sheet {
    position: relative;
    min-width: 100%;
}

.row {
    position: absolute;
    top: 0;
    left: 0;
    min-width: 100%;
    border-bottom: 1px solid var(--color-border-muted);
}

/* A chosen row stands out also once its list has lost the focus. */
.row.selected {
    background: var(--color-accent-muted);
}

.row.selected :deep(.state-chip) {
    border-color: var(--color-accent-emphasis);
}

/* A download opened onto its sources highlights its own line only. */
.row.opened.selected {
    background: transparent;
}

.row.opened.selected > .cells {
    background: var(--color-accent-muted);
}

.cells {
    display: flex;
    height: 32px;
}

.cell {
    display: flex;
    align-items: center;
    flex: none;
    min-width: 0;
    padding: 0 8px;
    overflow: hidden;
    white-space: nowrap;
}

.numeric {
    justify-content: flex-end;
    direction: ltr;
}

/* What a cell cannot show whole ends in an ellipsis, as in the desktop app. */
.cell-content {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.numeric .cell-content {
    text-align: end;
}

.placeholder {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
}
</style>
