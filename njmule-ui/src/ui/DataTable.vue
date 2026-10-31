<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts" generic="T extends Record<string, any>">
import {
    columnOrderingFeature,
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
    type ColumnOrderState,
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
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { language, t } from '../i18n';
import { type ColumnSpan, dragColumn, fitColumns, MAX_WIDTH } from './column-widths';
import Icon from './Icon.vue';

// A table as the desktop app's: rows of 32 px drawn only where they show, sorted by a click on a header (ascending
// first), columns moved by dragging their header, resized by their edge (a double click fits them to their content)
// and shown or hidden from the menu at the header's end, all kept in this window for the next time. A click chooses a
// row, Ctrl or Cmd adds one, Shift a range; the keys move and extend the choice; the context menu acts on the rows
// chosen, from the rows as from the header.

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
    /** Classes and styles of a row besides the table's own, such as the colour of what a search result is to us. */
    rowClass?: (row: T) => string | string[];
    rowStyle?: (row: T) => Record<string, string>;
    /** The columns keep their own widths and the list scrolls sideways, rather than filling its width. */
    fixedWidths?: boolean;
    /** Enter does not act on the row chosen, as in the search's results. */
    noEnterAction?: boolean;
}>();
const emit = defineEmits<{ activate: [row: T]; keydown: [event: KeyboardEvent] }>();
// The classes a screen gives go to the table, inside the context menu's root.
defineOptions({ inheritAttrs: false });
const selected = defineModel<string[]>('selected', { required: true });

const ROW = 32;
const layoutKey = `njmule.table.${props.name}`;

interface Layout {
    sorting?: SortingState;
    visibility?: ColumnVisibilityState;
    sizing?: ColumnSizingState;
    order?: ColumnOrderState;
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
const order = ref<ColumnOrderState>(saved.order ?? []);

watch([sorting, visibility, sizing, order], () => {
    try {
        localStorage.setItem(
            layoutKey,
            JSON.stringify({
                sorting: sorting.value,
                visibility: visibility.value,
                sizing: sizing.value,
                order: order.value,
            }),
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
    columnOrderingFeature,
    sortedRowModel: createSortedRowModel(),
    sortFns,
});

const table = useTable({
    features,
    columns: props.columns,
    // A computed, not a getter: the adapter unwraps refs only.
    data: computed(() => props.rows),
    getRowId: (row: T) => props.rowId(row),
    state: computed(() => ({
        sorting: sorting.value,
        columnVisibility: visibility.value,
        columnSizing: sizing.value,
        columnOrder: order.value,
    })),
    onSortingChange: update(sorting),
    onColumnVisibilityChange: update(visibility),
    onColumnSizingChange: sized,
    onColumnOrderChange: update(order),
    // Every column sorts ascending first, figures too, then descending, then not at all.
    sortDescFirst: false,
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
// The vertical bar's room, which the column menu takes above it as the desktop app's corner button does.
const gutter = ref(0);
const MENU_ROOM = 16;
const room = ref(0);
// JavaFX's width for a column given none: such a column starts fitted to its header, as the desktop app's.
const DEFAULT_WIDTH = 80;

function visibleColumns(): any[] {
    return (table as any).getVisibleLeafColumns() as any[];
}

// A column's width as the user left it (or as preferred), the one it prefers, and its limits.
function spanOf(c: any): ColumnSpan {
    const min = (c.columnDef.minSize as number | undefined) ?? 24;
    const max = (c.columnDef.maxSize as number | undefined) ?? MAX_WIDTH;
    const declared = (c.columnDef.size as number | undefined) ?? DEFAULT_WIDTH;
    const wanted = declared === DEFAULT_WIDTH ? Math.max(declared, headerNeeds.value[c.id] ?? 0) : declared;
    const pref = Math.min(max, Math.max(min, wanted));
    const kept = sizing.value[c.id];
    return { size: kept === undefined ? pref : Math.min(max, Math.max(min, kept)), pref, min, max };
}

// As the desktop app's tables, the columns fill the list's width, a change of it shared among them all; the list
// scrolls sideways only when even their least widths do not fit.
const widths = computed(() => {
    const columns = visibleColumns();
    const spans = columns.map(spanOf);
    const out = props.fixedWidths || room.value <= 0 ? spans.map((s) => s.size) : fitColumns(spans, room.value);
    return new Map<string, number>(columns.map((c, i) => [c.id as string, out[i]!]));
});

// The widths shown kept as the user's, so that dragging an edge starts from what is seen.
function holdWidths(): void {
    if (!props.fixedWidths) {
        sizing.value = Object.fromEntries(widths.value);
    }
}

// An edge dragged, or a column fitted: the columns after it give or take the difference, as in the desktop app.
function sized(next: Updater<ColumnSizingState>): void {
    const wanted = typeof next === 'function' ? next(sizing.value) : next;
    const columns = visibleColumns();
    const index = columns.findIndex((c) => wanted[c.id] !== undefined && wanted[c.id] !== sizing.value[c.id]);
    if (props.fixedWidths || index < 0) {
        sizing.value = wanted;
        return;
    }
    const spans = columns.map((c) => ({ ...spanOf(c), size: widths.value.get(c.id as string)! }));
    const out = dragColumn(spans, index, wanted[columns[index].id]!);
    sizing.value = { ...wanted, ...Object.fromEntries(columns.map((c, i) => [c.id as string, out[i]!])) };
}
const width = computed(() => [...widths.value.values()].reduce((sum, w) => sum + w, 0));
const header = ref<HTMLElement | null>(null);
// What each header's words take, with the cell's padding and the room of the sort mark.
const headerNeeds = ref<Record<string, number>>({});
const HEADER_ROOM = 16 + 14;

function measureHeaders(): void {
    const needs: Record<string, number> = {};
    for (const words of header.value?.querySelectorAll<HTMLElement>('[data-column] .header-words') ?? []) {
        const id = words.closest<HTMLElement>('[data-column]')?.dataset.column;
        if (id) {
            needs[id] = Math.ceil(words.scrollWidth) + HEADER_ROOM;
        }
    }
    headerNeeds.value = needs;
}

// Other words, other widths.
watch(language, () => void nextTick(measureHeaders));

function columnWidth(column: any): number {
    return widths.value.get(column.id as string) ?? (column.getSize() as number);
}

function measure(): void {
    const s = scroller.value;
    if (s) {
        gutter.value = s.offsetWidth - s.clientWidth;
        room.value = s.clientWidth - Math.max(0, MENU_ROOM - gutter.value);
    }
    measureHeaders();
}

const observer = new ResizeObserver(measure);

onMounted(() => window.addEventListener('mouseup', resized));

// The list comes inside the context menu's trigger: it is measured from the moment it is there.
function holdScroller(element: unknown): void {
    const now = element instanceof HTMLElement ? element : null;
    if (now === scroller.value) {
        return;
    }
    if (scroller.value) {
        observer.unobserve(scroller.value);
    }
    scroller.value = now;
    if (now) {
        observer.observe(now);
    }
}

onUnmounted(() => {
    observer.disconnect();
    window.removeEventListener('mouseup', resized);
});

// A column's edge being dragged is no header to move.
let resizing = false;

function resizeFrom(handler: (e: Event) => void, e: Event): void {
    resizing = true;
    holdWidths();
    // The drag starts from the widths held, once the table has taken them.
    void nextTick(() => handler(e));
}

function resized(): void {
    resizing = false;
}

// A double click on a column's edge fits it to the widest of its header and of its rows in sight.
function fit(column: any): void {
    const id = CSS.escape(column.id as string);
    const contents = [
        ...(header.value?.querySelectorAll<HTMLElement>(`[data-column="${id}"] .header-words`) ?? []),
        ...(scroller.value?.querySelectorAll<HTMLElement>(`[data-column="${id}"] .cell-content`) ?? []),
    ];
    // The cells' padding, and the header's room for its sort mark.
    const need = Math.max(0, ...contents.map((c) => c.scrollWidth)) + HEADER_ROOM;
    holdWidths();
    sized((old) => ({ ...old, [column.id]: Math.max(column.columnDef.minSize ?? 24, Math.ceil(need)) }));
}

// A header dragged onto another moves its column before or after it, as the side of the pointer says.
const dragged = ref<string | null>(null);
const dropAt = ref<{ id: string; after: boolean } | null>(null);

function dragStart(column: any, e: DragEvent): void {
    if (resizing) {
        e.preventDefault();
        return;
    }
    dragged.value = column.id as string;
    if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', column.id as string);
    }
}

function dragOver(column: any, e: DragEvent): void {
    if (dragged.value === null || dragged.value === column.id) {
        return;
    }
    e.preventDefault();
    const cell = e.currentTarget as HTMLElement;
    const r = cell.getBoundingClientRect();
    const second = e.clientX > r.left + r.width / 2;
    dropAt.value = { id: column.id as string, after: getComputedStyle(cell).direction === 'rtl' ? !second : second };
}

function drop(): void {
    const from = dragged.value;
    const to = dropAt.value;
    if (from !== null && to !== null) {
        const all = ((table as any).getAllLeafColumns() as any[]).map((c) => c.id as string);
        const now = [...order.value.filter((id) => all.includes(id)), ...all.filter((id) => !order.value.includes(id))];
        const moved = now.filter((id) => id !== from);
        moved.splice(moved.indexOf(to.id) + (to.after ? 1 : 0), 0, from);
        order.value = moved;
    }
    dragEnd();
}

function dragEnd(): void {
    dragged.value = null;
    dropAt.value = null;
}

function dropSide(id: string): string {
    const at = dropAt.value;
    return at === null || at.id !== id ? '' : at.after ? 'drop-after' : 'drop-before';
}

// The header follows the rows sideways.
function scrolled(): void {
    if (header.value && scroller.value) {
        header.value.scrollLeft = scroller.value.scrollLeft;
    }
}

// The row a Shift+click or Shift+key extends from, and the row the keys are on.
let anchor: string | null = null;
const lead = ref<string | null>(null);

function between(from: string, to: string): string[] {
    const ids = tableRows.value.map((r) => r.id as string);
    const [a, b] = [ids.indexOf(from), ids.indexOf(to)].sort((x, y) => x - y);
    return a! < 0 ? [to] : ids.slice(a, b! + 1);
}

function toggle(id: string): void {
    selected.value = selected.value.includes(id) ? selected.value.filter((s) => s !== id) : [...selected.value, id];
}

function choose(row: any, e: MouseEvent): void {
    const id = row.id as string;
    const ctrl = e.ctrlKey || e.metaKey;
    if (e.shiftKey && anchor !== null) {
        // With Ctrl as well, the range joins the rows already chosen.
        const range = between(anchor, id);
        selected.value = ctrl ? [...new Set([...selected.value, ...range])] : range;
    } else {
        if (ctrl) {
            toggle(id);
        } else {
            selected.value = [id];
        }
        anchor = id;
    }
    lead.value = id;
}

// A right click on a row not chosen chooses it alone, as the context menu acts on the rows chosen.
function menuOn(row: any): void {
    if (!selected.value.includes(row.id)) {
        selected.value = [row.id];
        anchor = row.id;
        lead.value = row.id;
    }
}

// The keys of the desktop app's tables: arrows, pages, Home and End move the choice, with Shift they extend it, with
// Ctrl they move only the row they are on, which Space chooses (with Ctrl, adds or takes); Ctrl+A chooses all.
function key(e: KeyboardEvent): void {
    const ids = tableRows.value.map((r) => r.id as string);
    const ctrl = e.ctrlKey || e.metaKey;
    const page = Math.max(1, Math.floor((scroller.value?.clientHeight ?? ROW) / ROW) - 1);
    const moves: Record<string, number> = {
        ArrowDown: 1,
        ArrowUp: -1,
        PageDown: page,
        PageUp: -page,
        Home: -ids.length,
        End: ids.length,
    };
    const move = moves[e.key];
    if (move !== undefined && !e.altKey) {
        if (ids.length > 0) {
            const at = ids.indexOf(lead.value ?? selected.value.at(-1) ?? '');
            const from = at >= 0 ? at : move > 0 ? -1 : ids.length;
            const next = ids[Math.max(0, Math.min(ids.length - 1, from + move))]!;
            lead.value = next;
            if (e.shiftKey) {
                selected.value = between(anchor ?? next, next);
            } else if (!ctrl) {
                selected.value = [next];
                anchor = next;
            }
            virtualizer.value.scrollToIndex(ids.indexOf(next));
        }
        e.preventDefault();
    } else if (e.key === ' ' && !e.altKey) {
        if (lead.value !== null && ids.includes(lead.value)) {
            if (ctrl) {
                toggle(lead.value);
            } else {
                selected.value = [lead.value];
            }
            anchor = lead.value;
        }
        e.preventDefault();
    } else if (ctrl && !e.altKey && e.key.toLowerCase() === 'a') {
        selected.value = ids;
        e.preventDefault();
    } else if (e.key === 'Enter' && !e.altKey && !props.noEnterAction && selected.value.length === 1) {
        const row = tableRows.value.find((r) => r.id === selected.value[0]);
        if (row) {
            emit('activate', row.original as T);
        }
        e.preventDefault();
    } else {
        emit('keydown', e);
    }
}

// The direction of a sorted column, with its rank when several sort the rows.
function sortIcon(column: any): string {
    const sorted = column.getIsSorted();
    const arrow = sorted === 'asc' ? '▲' : sorted === 'desc' ? '▼' : '';
    return arrow !== '' && sorting.value.length > 1 ? `${arrow}${(column.getSortIndex() as number) + 1}` : arrow;
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
    <ContextMenuRoot>
        <!-- Without a menu of its own, a list has none, as the desktop app's. -->
        <ContextMenuTrigger as-child :disabled="!$slots.menu">
            <div class="data-table" v-bind="$attrs" role="grid" :aria-label="label" :aria-rowcount="tableRows.length">
                <div class="header">
                    <div
                        ref="header"
                        class="header-scroll"
                        :style="{ marginInlineEnd: `${Math.max(gutter, MENU_ROOM)}px` }"
                    >
                        <div class="header-row" :style="{ width: `${width}px` }" role="row">
                            <div
                                v-for="header in (table as any).getHeaderGroups()[0].headers"
                                :key="header.id"
                                class="header-cell"
                                :class="[{ moving: dragged === header.column.id }, dropSide(header.column.id)]"
                                role="columnheader"
                                draggable="true"
                                :data-column="header.column.id"
                                :aria-sort="
                                    header.column.getIsSorted() === 'asc'
                                        ? 'ascending'
                                        : header.column.getIsSorted() === 'desc'
                                          ? 'descending'
                                          : undefined
                                "
                                :style="{ width: `${columnWidth(header.column)}px` }"
                                @click="header.column.getToggleSortingHandler()?.($event)"
                                @dragstart="dragStart(header.column, $event)"
                                @dragover="dragOver(header.column, $event)"
                                @drop.prevent="drop"
                                @dragend="dragEnd"
                            >
                                <span class="ellipsis header-words"><FlexRender :header="header" /></span>
                                <span class="sort">{{ sortIcon(header.column) }}</span>
                                <span
                                    class="resizer"
                                    @mousedown.stop="resizeFrom(header.getResizeHandler(), $event)"
                                    @touchstart.stop="resizeFrom(header.getResizeHandler(), $event)"
                                    @click.stop
                                    @dblclick.stop="fit(header.column)"
                                />
                            </div>
                        </div>
                    </div>
                    <DropdownMenuRoot>
                        <DropdownMenuTrigger
                            class="column-menu"
                            :style="{ width: `${Math.max(gutter, MENU_ROOM)}px` }"
                            :aria-label="t('nj.table.columns')"
                            >⋮</DropdownMenuTrigger
                        >
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
                <div :ref="holdScroller" class="body-scroll focusable" tabindex="0" @keydown="key" @scroll="scrolled">
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
                                    lead: lead === tableRows[item.index].id,
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
                                    :data-column="cell.column.id"
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
            </div>
        </ContextMenuTrigger>
        <ContextMenuPortal>
            <ContextMenuContent class="menu">
                <slot name="menu" />
            </ContextMenuContent>
        </ContextMenuPortal>
    </ContextMenuRoot>
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

/* A header being moved, and the edge of the one it would land beside. */
.header-cell.moving {
    opacity: 0.5;
}

.header-cell.drop-before {
    box-shadow: inset 2px 0 0 var(--color-accent-emphasis);
}

.header-cell.drop-after {
    box-shadow: inset -2px 0 0 var(--color-accent-emphasis);
}

.header-cell.drop-before:dir(rtl) {
    box-shadow: inset -2px 0 0 var(--color-accent-emphasis);
}

.header-cell.drop-after:dir(rtl) {
    box-shadow: inset 2px 0 0 var(--color-accent-emphasis);
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

/* Above the vertical bar, clear of the last header and its edge. */
.column-menu {
    position: absolute;
    inset-inline-end: 0;
    top: 0;
    height: 34px;
    padding: 0;
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

/* Rows are chosen, not their words selected, also by Shift and double clicks. */
.row {
    position: absolute;
    top: 0;
    left: 0;
    min-width: 100%;
    border-bottom: 1px solid var(--color-border-muted);
    user-select: none;
}

/* The row the keys are on, while the list has the focus. */
.body-scroll:focus .row.lead > .cells {
    box-shadow: inset 0 0 0 1px var(--color-accent-emphasis);
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

/* What an empty list shows, in the middle of the part in sight however far its columns scroll. */
.placeholder {
    position: sticky;
    top: 0;
    inset-inline-start: 0;
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
}
</style>
