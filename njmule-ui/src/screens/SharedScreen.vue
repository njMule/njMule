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
import { computed, h, onMounted, ref, shallowRef, watch } from 'vue';
import { ApiError, answer, api, type Schemas } from '../api/client';
import { ask } from '../ask';
import { count, size } from '../format';
import { t } from '../i18n';
import { htmlLinks } from '../links';
import { preferences, readShared, shared, type SharedDirectory, type SharedFile } from '../model/engine';
import type { Platform } from '../platform';
import { holds, nameOf, parentOf, places, type Place } from '../shared';
import { problem } from '../toasts';
import { kindIcon } from '../transfers';
import DataTable, { type ColumnMeta } from '../ui/DataTable.vue';
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

const incoming = computed(() => preferences.value['dirs.incoming'] ?? '');
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
                    h(Icon, { name: kindIcon(kindOf((row.original as SharedFile).name)), class: 'file-kind' }),
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
        id: 'priority',
        header: t('IDS_PRIORITY'),
        accessorFn: (f: SharedFile) => t(PRIORITIES.find((p) => p.value === f.priority)?.key ?? 'IDS_PRIONORMAL'),
        size: 95,
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
    },
];

// What a file holds, as its extension tells; the API reports it for downloads only.
function kindOf(
    name: string,
): 'video' | 'audio' | 'image' | 'document' | 'archive' | 'disc_image' | 'program' | 'other' {
    const ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
    const kinds: Record<string, string[]> = {
        video: ['avi', 'mkv', 'mp4', 'mov', 'wmv', 'mpg', 'mpeg', 'webm', 'm4v', 'ogm', 'divx', 'flv'],
        audio: ['mp3', 'flac', 'ogg', 'wav', 'm4a', 'm4b', 'aac', 'wma', 'opus', 'ape'],
        image: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'tif', 'tiff', 'svg'],
        document: ['pdf', 'epub', 'txt', 'doc', 'docx', 'odt', 'rtf', 'mobi', 'djvu', 'cbr', 'cbz'],
        archive: ['zip', 'rar', '7z', 'gz', 'tar', 'bz2', 'xz', 'tgz'],
        disc_image: ['iso', 'img', 'bin', 'cue', 'nrg', 'mdf', 'dmg'],
        program: ['exe', 'msi', 'apk', 'deb', 'rpm', 'appimage', 'jar'],
    };
    const found = Object.entries(kinds).find(([, exts]) => exts.includes(ext));
    return (found?.[0] ?? 'other') as ReturnType<typeof kindOf>;
}

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
    let path = await props.platform.chooseFolder(t('IDS_SHAREDIR'));
    // The desktop app's dialog gives none when it is cancelled.
    if (path === null && props.platform.desktop) {
        return;
    }
    let subdirectories = true;
    // A page reaches no folder: the path of the engine's computer is typed, in the same question.
    const typed = path === null;
    const first = await ask({
        title: t('nj.shared.shareTitle'),
        text: t('nj.shared.shareText'),
        names: typed ? [] : [path!],
        field: typed ? { label: t('IDS_SHAREDIR'), value: '' } : undefined,
        check: { label: t('IDS_SHAREDIRSUB'), value: true },
        yes: t('nj.shared.share'),
        no: t('IDS_CANCEL'),
    });
    if (first === null) {
        return;
    }
    path = typed ? first.field : path!;
    subdirectories = first.check;
    if (!(await share(path, subdirectories, false))) {
        // A whole drive or a personal folder: shared only after a second question, whose default is not to.
        const again = await ask({
            title: t('nj.shared.shareAllTitle'),
            text: t('nj.shared.sensitiveText'),
            names: [path],
            note: t('nj.shared.asksAlso'),
            warning: true,
            yes: t('nj.shared.shareAll'),
            no: t('nj.shared.chooseOther'),
            risky: true,
        });
        if (again === null) {
            await chooseDirectory();
            return;
        }
        await share(path, subdirectories, true);
    }
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

async function openPlace(): Promise<void> {
    if (place.value.kind !== 'all' && !(await props.platform.openFolder(place.value.path))) {
        problem(place.value.path);
    }
}

async function openFolder(): Promise<void> {
    const f = chosen.value[0];
    if (f !== undefined && !(await props.platform.openFolder(parentOf(f.path)))) {
        problem(parentOf(f.path));
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
    if (f === undefined) {
        return;
    }
    const a = await ask({
        title: t('nj.shared.renameTitle'),
        field: { label: t('nj.shared.newName'), value: f.name, hint: t('nj.shared.renameHint') },
        yes: t('IDS_RENAME'),
        no: t('IDS_CANCEL'),
    });
    if (a !== null && a.field !== f.name) {
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

// Ctrl+F finds among the files, as in the transfers.
function screenKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
        filterBox.value?.focus();
        e.preventDefault();
    }
}

onMounted(() => void readDirectories());
</script>

<template>
    <div class="screen" @keydown="screenKey">
        <aside class="shared-side">
            <button id="share-folder" class="button accent wide" @click="chooseDirectory">
                <Icon name="FOLDER_ADD" />{{ t('IDS_SHAREDIR') }}
            </button>
            <ul id="shared-places" class="shared-places" role="tree">
                <template v-for="p in tree" :key="p.kind + p.path">
                    <li
                        class="place"
                        role="treeitem"
                        :aria-selected="place === p"
                        tabindex="0"
                        @click="chosenPlace = { kind: p.kind, path: p.path }"
                    >
                        <Icon v-if="p.children.length > 0" class="tree-arrow" name="CHEVRON_DOWN" />
                        <span v-else class="tree-arrow" />
                        <Icon
                            :name="p.kind === 'all' ? 'SHARED' : p.kind === 'incoming' ? 'DOWNLOAD' : 'FOLDER_OPEN'"
                        />
                        <Tip :text="p.kind === 'all' ? '' : p.path">
                            <span class="place-name ellipsis" dir="ltr">{{ placeName(p) }}</span>
                        </Tip>
                        <Icon v-if="p.kind === 'incoming'" class="place-lock" name="LOCK" />
                        <span class="spacer" />
                        <span class="muted">{{ placeCount(p) }}</span>
                    </li>
                    <li
                        v-for="c in p.children"
                        :key="c.path"
                        class="place child"
                        role="treeitem"
                        :aria-selected="place === c"
                        tabindex="0"
                        @click="chosenPlace = { kind: c.kind, path: c.path }"
                    >
                        <span class="tree-arrow" />
                        <Icon name="FOLDER_OPEN" />
                        <Tip :text="c.path">
                            <span class="place-name ellipsis" dir="ltr">{{ placeName(c) }}</span>
                        </Tip>
                        <span class="spacer" />
                        <span class="muted">{{ placeCount(c) }}</span>
                    </li>
                </template>
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
                    <button
                        v-if="place.kind !== 'all'"
                        class="button small"
                        :disabled="!platform.desktop"
                        @click="openPlace"
                    >
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
        <section class="files">
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
                    <DropdownMenuTrigger class="button" :disabled="chosen.length === 0">
                        <Icon name="PRIORITY" />{{ t('IDS_PRIORITY') }}<Icon name="CHEVRON_DOWN" />
                    </DropdownMenuTrigger>
                    <DropdownMenuPortal>
                        <DropdownMenuContent class="menu" align="end">
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
                            <DropdownMenuItem
                                class="menu-item"
                                :disabled="chosen.length !== 1 || !platform.desktop"
                                @select="openFolder"
                            >
                                <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENFOLDER') }}
                            </DropdownMenuItem>
                            <DropdownMenuItem class="menu-item" :disabled="chosen.length !== 1" @select="rename">
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
                    <div class="empty-state">
                        <Icon name="SHARED" />
                        <p>{{ t('nj.shared.none') }}</p>
                        <button class="button" @click="chooseDirectory">
                            <Icon name="FOLDER_ADD" />{{ t('IDS_SHAREDIR') }}
                        </button>
                    </div>
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

:deep(.name-cell) {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
}

:deep(.file-kind) {
    --nj-icon-color: var(--color-fg-muted);
}

.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 8px;
    color: var(--color-fg-muted);
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
