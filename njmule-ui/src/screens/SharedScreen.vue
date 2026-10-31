<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import {
    ContextMenuItem,
    ContextMenuPortal,
    ContextMenuSeparator,
    ContextMenuSub,
    ContextMenuSubContent,
    ContextMenuSubTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuRoot,
    DropdownMenuTrigger,
} from 'reka-ui';
import { computed, h, nextTick, onMounted, ref, shallowRef, watch } from 'vue';
import { ApiError, answer, api, type Schemas } from '../api/client';
import { ask } from '../ask';
import { count, size } from '../format';
import { t } from '../i18n';
import { htmlLinks } from '../links';
import { folders, readShared, shared, type SharedDirectory, type SharedFile } from '../model/engine';
import type { Platform } from '../platform';
import {
    fileKind,
    holds,
    mayHoldPersonalFiles,
    nameOf,
    parentOf,
    placeKey,
    places,
    shownPlaces,
    treeMove,
    type Place,
} from '../shared';
import { message, problem } from '../toasts';
import { kindIcon } from '../transfers';
import DataTable, { type ColumnMeta } from '../ui/DataTable.vue';
import EmptyState from '../ui/EmptyState.vue';
import Icon from '../ui/Icon.vue';
import Segments from '../ui/Segments.vue';
import Tip from '../ui/Tip.vue';

// The shared files: the only way to share a folder and the tree of the places beside them, what to do with the place
// chosen under it; the files of that place, narrowed by name, with this run's counters or every run's.

const props = defineProps<{ platform: Platform }>();

type Counter = 'session' | 'total';
type Priority = Schemas['UploadPriority'];

const directories = shallowRef<SharedDirectory[]>([]);
const chosenPlace = ref<{ kind: Place['kind']; path: string }>({ kind: 'all', path: '' });
const counter = ref<Counter>('session');
const filter = ref('');
const selected = ref<string[]>([]);
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const priorityTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const filterBox = ref<HTMLInputElement | null>(null);

const PRIORITIES: { value: Priority; key: string }[] = [
    { value: 'very_low', key: 'IDS_PRIOVERYLOW' },
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'release', key: 'IDS_PRIORELEASE' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];

const COUNTERS = computed(() => [
    { value: 'session' as const, title: t('IDS_STATS_SESSION') },
    { value: 'total' as const, title: t('IDS_STATS_CUMULATIVE') },
]);

// The folder the engine uses, the default one included, as the preference is empty while at its default.
const incoming = computed(() => folders.value.incoming);
const tree = computed(() =>
    places(
        directories.value,
        incoming.value,
        shared.value.map((f) => f.path),
    ),
);
const flat = computed(() => tree.value.flatMap((p) => [p, ...p.children]));
const place = computed(
    () =>
        flat.value.find((p) => p.kind === chosenPlace.value.kind && p.path === chosenPlace.value.path) ??
        tree.value[0]!,
);

const inPlace = computed(() => shared.value.filter((f) => holds(place.value, f.path)));
const shown = computed(() => {
    const words = filter.value
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return inPlace.value.filter((f) => words.every((w) => f.name.toLowerCase().includes(w)));
});
const chosen = computed(() => shared.value.filter((f) => selected.value.includes(f.hash)));

function placeName(p: Place): string {
    switch (p.kind) {
        case 'all':
            return t('nj.shared.all');
        case 'incoming':
            return t('IDS_PW_INCOMING');
        case 'folder':
            return p.path;
        default:
            return nameOf(p.path);
    }
}

function placeCount(p: Place): string {
    return count(shared.value.filter((f) => holds(p, f.path)).length);
}

// The folders closed in the tree; they open with the arrow beside them, a double click or the keyboard.
const closed = ref<string[]>([]);
const shownTree = computed(() => shownPlaces(tree.value, closed.value));
const placeIndex = computed(() => shownTree.value.findIndex((p) => placeKey(p) === placeKey(place.value)));

function choosePlace(p: Place): void {
    chosenPlace.value = { kind: p.kind, path: p.path };
}

function isOpen(p: Place): boolean {
    return !closed.value.includes(placeKey(p));
}

// Closing the folder of the subfolder chosen chooses the folder.
function toggle(p: Place): void {
    if (isOpen(p)) {
        closed.value = [...closed.value, placeKey(p)];
        if (p.children.some((c) => placeKey(c) === placeKey(place.value))) {
            choosePlace(p);
        }
    } else {
        closed.value = closed.value.filter((k) => k !== placeKey(p));
    }
}

async function treeKey(e: KeyboardEvent): Promise<void> {
    const move = treeMove(e.key, tree.value, closed.value, place.value, document.documentElement.dir === 'rtl');
    if (move === null) {
        return;
    }
    e.preventDefault();
    if (move.toggle) {
        toggle(move.toggle);
    }
    if (move.choose) {
        choosePlace(move.choose);
        await nextTick();
        document.getElementById(`shared-place-${placeIndex.value}`)?.scrollIntoView({ block: 'nearest' });
    }
}

function stat(f: SharedFile, session: keyof SharedFile['statistics'], total: keyof SharedFile['statistics']): number {
    return counter.value === 'session' ? f.statistics[session] : f.statistics[total];
}

const numeric: ColumnMeta = { numeric: true };

const columns: ColumnDef<any, SharedFile, any>[] = [
    {
        id: 'name',
        header: t('IDS_DL_FILENAME'),
        accessorFn: (f: SharedFile) => f.name,
        size: 340,
        minSize: 100,
        cell: ({ row }: any) =>
            h(Tip, { text: (row.original as SharedFile).name }, () =>
                h('span', { class: 'name-cell' }, [
                    h(Icon, { name: kindIcon(fileKind((row.original as SharedFile).name)), class: 'file-kind' }),
                    h('span', { class: 'ellipsis', dir: 'auto' }, (row.original as SharedFile).name),
                ]),
            ),
    },
    {
        id: 'size',
        header: t('IDS_DL_SIZE'),
        accessorFn: (f: SharedFile) => f.size,
        size: 85,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        // Sorted by level, from Very low to Release, then Auto.
        id: 'priority',
        header: t('IDS_PRIORITY'),
        accessorFn: (f: SharedFile) =>
            Math.max(
                0,
                PRIORITIES.findIndex((p) => p.value === f.priority),
            ),
        size: 95,
        cell: ({ getValue }: any) => t(PRIORITIES[getValue() as number]!.key),
    },
    {
        id: 'requests',
        header: t('IDS_SF_REQUESTS'),
        accessorFn: (f: SharedFile) => stat(f, 'requests', 'totalRequests'),
        size: 85,
        meta: numeric,
    },
    {
        id: 'accepted',
        header: t('IDS_SF_ACCEPTS'),
        accessorFn: (f: SharedFile) => stat(f, 'accepted', 'totalAccepted'),
        size: 100,
        meta: { numeric: true, hidden: true } satisfies ColumnMeta,
    },
    {
        id: 'transferred',
        header: t('IDS_SF_TRANSFERRED'),
        accessorFn: (f: SharedFile) => stat(f, 'transferred', 'totalTransferred'),
        size: 110,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'folder',
        header: t('nj.shared.folder'),
        accessorFn: (f: SharedFile) => parentOf(f.path),
        size: 260,
        meta: { hidden: true } satisfies ColumnMeta,
        // A path reads left to right in every language.
        cell: ({ getValue }: any) => h('span', { dir: 'ltr' }, getValue() as string),
    },
];

async function readDirectories(): Promise<void> {
    try {
        directories.value = await answer(api().GET('/shared/directories'));
    } catch {
        // Kept as they were.
    }
}

// Sharing or unsharing changes the files shared, wherever it was done: the folders are read again then.
watch(
    () => shared.value.length,
    () => void readDirectories(),
);

async function chooseDirectory(): Promise<void> {
    const chosenFolder = await props.platform.chooseFolder(t('nj.shared.shareFolder'));
    // The desktop app's dialog gives none when it is cancelled.
    if (chosenFolder === null && props.platform.desktop) {
        return;
    }
    // A page reaches no folder: the path of the engine's computer is typed, in the question.
    const typed = chosenFolder === null;
    if (!typed && mayHoldPersonalFiles(chosenFolder)) {
        await shareEverything(chosenFolder, true);
        return;
    }
    const first = await ask({
        title: t('nj.shared.shareTitle'),
        text: t('nj.shared.shareText'),
        names: typed ? [] : [chosenFolder],
        namesFirst: true,
        field: typed ? { label: t('nj.shared.shareFolder'), value: '' } : undefined,
        check: { label: t('IDS_SHAREDIRSUB'), value: true },
        yes: t('nj.shared.share'),
        no: t('IDS_CANCEL'),
    });
    if (first === null || (typed && first.field === '')) {
        return;
    }
    const path = typed ? first.field : chosenFolder;
    // A typed path is known only now; a folder the engine knows to be personal comes back as a conflict.
    if ((typed && mayHoldPersonalFiles(path)) || !(await share(path, first.check, false))) {
        await shareEverything(path, first.check);
    }
}

// A whole drive or a personal folder, shared only when the user insists; the default is choosing another one.
async function shareEverything(path: string, subdirectories: boolean): Promise<void> {
    const a = await ask({
        title: t('nj.shared.shareAllTitle'),
        text: t('nj.shared.sensitiveText'),
        names: [path],
        namesFirst: true,
        check: { label: t('IDS_SHAREDIRSUB'), value: subdirectories },
        note: t('nj.shared.asksAlso'),
        warning: true,
        yes: t('nj.shared.shareAll'),
        no: t('nj.shared.chooseOther'),
        risky: true,
    });
    if (a === null) {
        await chooseDirectory();
        return;
    }
    await share(path, a.check, true);
}

/** Shares {@code path}; false when the engine asks first whether to share a folder that may hold personal files. */
async function share(path: string, subdirectories: boolean, everything: boolean): Promise<boolean> {
    try {
        await answer(api().POST('/shared/directories', { body: { path, subdirectories, everything } }));
        await Promise.all([readDirectories(), readShared()]);
        return true;
    } catch (e) {
        if (e instanceof ApiError && e.status === 409 && !everything) {
            return false;
        }
        problem((e as Error).message);
        return true;
    }
}

async function unshare(): Promise<void> {
    if (place.value.kind !== 'folder') {
        return;
    }
    try {
        await answer(api().DELETE('/shared/directories', { params: { query: { path: place.value.path } } }));
        chosenPlace.value = { kind: 'all', path: '' };
        await Promise.all([readDirectories(), readShared()]);
    } catch (e) {
        problem((e as Error).message);
    }
}

async function subfolders(on: boolean): Promise<void> {
    if (place.value.kind === 'folder' && on !== place.value.subdirectories) {
        await share(place.value.path, on, true);
    }
}

// A page cannot open the folders of the computer njMule runs on: it says so.
async function openHere(folder: string): Promise<void> {
    if (!props.platform.desktop) {
        message(t('nj.remote.elsewhere'));
    } else if (!(await props.platform.openFolder(folder))) {
        problem(folder);
    }
}

async function openPlace(): Promise<void> {
    if (place.value.kind !== 'all') {
        await openHere(place.value.path);
    }
}

// One file at a time, as renaming.
async function openFolder(): Promise<void> {
    if (chosen.value.length === 1) {
        await openHere(parentOf(chosen.value[0]!.path));
    }
}

async function prioritize(priority: Priority): Promise<void> {
    for (const f of chosen.value) {
        try {
            await answer(
                api().PUT('/shared/{hash}/priority', { params: { path: { hash: f.hash } }, body: { priority } }),
            );
        } catch (e) {
            problem((e as Error).message);
        }
    }
    await readShared();
}

async function rename(): Promise<void> {
    const f = chosen.value[0];
    if (f === undefined || chosen.value.length !== 1) {
        return;
    }
    const a = await ask({
        title: t('nj.shared.renameTitle'),
        field: { label: t('nj.shared.newName'), value: f.name, hint: t('nj.shared.renameHint') },
        yes: t('IDS_RENAME'),
        no: t('IDS_CANCEL'),
    });
    if (a !== null && a.field !== '' && a.field !== f.name) {
        try {
            await answer(
                api().PUT('/shared/{hash}/name', { params: { path: { hash: f.hash } }, body: { name: a.field } }),
            );
            await readShared();
        } catch (e) {
            problem((e as Error).message);
        }
    }
}

async function copyLinks(html: boolean): Promise<void> {
    const links = chosen.value.map((f) => ({ link: f.link, name: f.name }));
    try {
        await navigator.clipboard.writeText(html ? htmlLinks(links) : links.map((l) => l.link).join('\n'));
    } catch (e) {
        problem((e as Error).message);
    }
}

// Ctrl+F finds among the files, as in the transfers, while the focus is with them.
function filesKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        filterBox.value?.focus();
        e.preventDefault();
    }
}

onMounted(() => void readDirectories());
</script>

<template>
    <div class="screen">
        <aside class="shared-side">
            <button id="share-folder" class="button accent wide" @click="chooseDirectory">
                <Icon name="FOLDER_ADD" />{{ t('nj.shared.shareFolder') }}
            </button>
            <ul
                id="shared-places"
                class="shared-places focusable"
                role="tree"
                tabindex="0"
                :aria-activedescendant="placeIndex < 0 ? undefined : `shared-place-${placeIndex}`"
                @keydown="treeKey"
            >
                <Tip v-for="(p, i) in shownTree" :key="placeKey(p)" :text="p.kind === 'all' ? '' : p.path">
                    <li
                        :id="`shared-place-${i}`"
                        class="place"
                        :class="{ child: p.kind === 'subfolder' }"
                        role="treeitem"
                        :aria-selected="placeKey(p) === placeKey(place)"
                        :aria-expanded="p.children.length > 0 ? isOpen(p) : undefined"
                        @click="choosePlace(p)"
                        @dblclick="p.children.length > 0 && toggle(p)"
                    >
                        <span v-if="p.children.length > 0" class="tree-arrow" @click.stop="toggle(p)" @dblclick.stop>
                            <Icon :name="isOpen(p) ? 'CHEVRON_DOWN' : 'CHEVRON_RIGHT'" />
                        </span>
                        <span v-else class="tree-arrow" />
                        <Icon
                            :name="p.kind === 'all' ? 'SHARED' : p.kind === 'incoming' ? 'DOWNLOAD' : 'FOLDER_OPEN'"
                        />
                        <span class="place-name ellipsis" dir="ltr">{{ placeName(p) }}</span>
                        <Icon v-if="p.kind === 'incoming'" class="place-lock" name="LOCK" />
                        <span class="spacer" />
                        <span class="muted">{{ placeCount(p) }}</span>
                    </li>
                </Tip>
            </ul>
            <div class="shared-place">
                <div>
                    <strong class="place-title" dir="ltr">{{
                        place.kind === 'all' ? t('nj.shared.all') : place.path
                    }}</strong>
                    <div class="muted">
                        {{ t('nj.shared.files', inPlace.length, size(inPlace.reduce((s, f) => s + f.size, 0))) }}
                    </div>
                </div>
                <label v-if="place.kind === 'folder'" class="sub-row">
                    <span>{{ t('IDS_SHAREDIRSUB') }}</span>
                    <input
                        type="checkbox"
                        role="switch"
                        class="switch"
                        :checked="place.subdirectories"
                        @change="subfolders(($event.target as HTMLInputElement).checked)"
                    />
                </label>
                <div class="place-buttons">
                    <button v-if="place.kind !== 'all'" class="button small" @click="openPlace">
                        <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENFOLDER') }}
                    </button>
                    <button
                        v-if="place.kind === 'folder'"
                        id="unshare-folder"
                        class="button flat small"
                        @click="unshare"
                    >
                        <Icon name="REMOVE_CIRCLE" />{{ t('IDS_UNSHAREDIR') }}
                    </button>
                </div>
                <p v-if="place.kind === 'incoming'" class="muted note">{{ t('nj.shared.incomingNote') }}</p>
            </div>
        </aside>
        <section class="files" @keydown="filesKey">
            <div class="tool-row">
                <label class="field filter">
                    <Icon name="FILTER" />
                    <input
                        id="shared-filter"
                        ref="filterBox"
                        v-model="filter"
                        :placeholder="t('nj.shared.filter')"
                        :aria-label="t('nj.shared.filter')"
                        @keydown.esc="filter = ''"
                    />
                </label>
                <Segments v-model="counter" :items="COUNTERS" :label="t('IDS_SF_TRANSFERRED')" />
                <span class="spacer" />
                <DropdownMenuRoot>
                    <Tip :text="t('IDS_PRIORITY')">
                        <DropdownMenuTrigger
                            ref="priorityTrigger"
                            class="button"
                            :disabled="chosen.length === 0"
                            :aria-label="t('IDS_PRIORITY')"
                        >
                            <Icon name="PRIORITY" /><span class="tool-text">{{ t('IDS_PRIORITY') }}</span
                            ><Icon name="CHEVRON_DOWN" />
                        </DropdownMenuTrigger>
                    </Tip>
                    <DropdownMenuPortal>
                        <DropdownMenuContent class="menu" align="end" :reference="priorityTrigger?.$el">
                            <DropdownMenuItem
                                v-for="p in PRIORITIES"
                                :key="p.value"
                                class="menu-item"
                                @select="prioritize(p.value)"
                            >
                                {{ t(p.key) }}
                            </DropdownMenuItem>
                        </DropdownMenuContent>
                    </DropdownMenuPortal>
                </DropdownMenuRoot>
                <Tip :text="t('nj.copyLink')">
                    <button
                        class="button icon-only"
                        :disabled="chosen.length === 0"
                        :aria-label="t('nj.copyLink')"
                        @click="copyLinks(false)"
                    >
                        <Icon name="COPY" />
                    </button>
                </Tip>
                <DropdownMenuRoot>
                    <Tip :text="t('IDS_MORE')">
                        <DropdownMenuTrigger
                            ref="moreTrigger"
                            class="button icon-only"
                            :disabled="chosen.length === 0"
                            :aria-label="t('IDS_MORE')"
                        >
                            <Icon name="MORE" />
                        </DropdownMenuTrigger>
                    </Tip>
                    <DropdownMenuPortal>
                        <DropdownMenuContent class="menu" align="end" :reference="moreTrigger?.$el">
                            <!-- As the desktop app's: the button needs a choice, its items act on one file only. -->
                            <DropdownMenuItem class="menu-item" @select="openFolder">
                                <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENFOLDER') }}
                            </DropdownMenuItem>
                            <DropdownMenuItem class="menu-item" @select="rename">
                                <Icon name="EDIT" />{{ t('IDS_RENAME') }}
                            </DropdownMenuItem>
                            <DropdownMenuItem class="menu-item" disabled
                                ><Icon name="COMMENT" />{{ t('IDS_CMT_ADD') }}</DropdownMenuItem
                            >
                            <DropdownMenuItem class="menu-item" @select="copyLinks(true)"
                                ><Icon name="CODE" />{{ t('IDS_DL_LINK2') }}</DropdownMenuItem
                            >
                        </DropdownMenuContent>
                    </DropdownMenuPortal>
                </DropdownMenuRoot>
            </div>
            <DataTable
                v-model:selected="selected"
                name="shared"
                :rows="shown"
                :columns="columns"
                :row-id="(f: SharedFile) => f.hash"
                :label="t('IDS_EM_FILES')"
                :columns-label="t('IDS_DL_FILENAME')"
            >
                <template #empty>
                    <EmptyState icon="SHARED" :text="t('nj.shared.none')">
                        <button class="button" @click="chooseDirectory">
                            <Icon name="FOLDER_ADD" />{{ t('nj.shared.shareFolder') }}
                        </button>
                    </EmptyState>
                </template>
                <template #menu>
                    <ContextMenuItem
                        class="menu-item"
                        :disabled="chosen.length !== 1 || !platform.desktop"
                        @select="openFolder"
                    >
                        <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENFOLDER') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length !== 1" @select="rename">
                        <Icon name="EDIT" />{{ t('IDS_RENAME') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" disabled
                        ><Icon name="COMMENT" />{{ t('IDS_CMT_ADD') }}</ContextMenuItem
                    >
                    <ContextMenuSeparator class="menu-separator" />
                    <ContextMenuSub>
                        <ContextMenuSubTrigger class="menu-item" :disabled="chosen.length === 0">
                            <Icon name="PRIORITY" />{{ t('IDS_PRIORITY')
                            }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                        </ContextMenuSubTrigger>
                        <ContextMenuPortal>
                            <ContextMenuSubContent class="menu">
                                <ContextMenuItem
                                    v-for="p in PRIORITIES"
                                    :key="p.value"
                                    class="menu-item"
                                    @select="prioritize(p.value)"
                                >
                                    {{ t(p.key) }}
                                </ContextMenuItem>
                            </ContextMenuSubContent>
                        </ContextMenuPortal>
                    </ContextMenuSub>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks(false)">
                        <Icon name="COPY" />{{ t('nj.copyLink') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks(true)">
                        <Icon name="CODE" />{{ t('IDS_DL_LINK2') }}
                    </ContextMenuItem>
                </template>
            </DataTable>
        </section>
    </div>
</template>

<style scoped>
.screen {
    display: flex;
    gap: 16px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
}

.shared-side {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 0 1 270px;
    min-width: 220px;
    max-width: 300px;
}

.wide {
    width: 100%;
    justify-content: center;
}

.shared-places {
    flex: 1;
    min-height: 0;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
}

.place {
    display: flex;
    align-items: center;
    gap: 8px;
    height: 34px;
    padding: 0 8px;
    cursor: default;
    --nj-icon-color: var(--color-fg-muted);
}

.place.child {
    padding-inline-start: 36px;
}

.place[aria-selected='true'] {
    background: var(--color-accent-muted);
}

.tree-arrow {
    display: inline-flex;
    width: 12px;
    flex: none;
    --nj-icon-size: 12px;
}

.place-name {
    min-width: 0;
}

.place-lock {
    --nj-icon-size: 13px;
}

.spacer {
    flex: 1;
}

.shared-place {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px;
    border-radius: 8px;
    background: var(--color-bg-subtle);
}

.place-title {
    overflow-wrap: anywhere;
}

.sub-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
}

.place-buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}

.note {
    margin: 0;
}

.files {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-width: 0;
    min-height: 0;
}

.tool-row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.filter {
    flex: 0 1 260px;
    min-width: 140px;
}

/* In a narrow window the Priority button shows only its icons, its name as tooltip, as the desktop app's tool row. */
.njmule.narrow .tool-text {
    display: none;
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

/* A switch as the desktop app's: a pill whose knob moves to the end when on. */
.switch {
    appearance: none;
    width: 36px;
    height: 20px;
    flex: none;
    margin: 0;
    border-radius: 10px;
    background: var(--color-border-default);
    position: relative;
    cursor: pointer;
}

.switch::after {
    content: '';
    position: absolute;
    top: 2px;
    inset-inline-start: 2px;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    background: var(--color-bg-default);
    transition: inset-inline-start 0.15s;
}

.switch:checked {
    background: var(--color-accent-emphasis);
}

.switch:checked::after {
    inset-inline-start: 18px;
}
</style>
