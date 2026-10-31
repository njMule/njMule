<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    ContextMenuContent,
    ContextMenuItem,
    ContextMenuPortal,
    ContextMenuRoot,
    ContextMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuRoot,
    DropdownMenuSeparator,
    DropdownMenuSub,
    DropdownMenuSubContent,
    DropdownMenuSubTrigger,
    DropdownMenuTrigger,
} from 'reka-ui';
import { computed, ref, shallowRef, watch } from 'vue';
import { answer, api, type Schemas } from '../../api/client';
import ClientFilesDialog from '../../dialogs/ClientFilesDialog.vue';
import { filesHere, preview } from '../../files';
import { duration, percent, rate, size } from '../../format';
import { t } from '../../i18n';
import { categories, folders, sources, type Download, type Source } from '../../model/engine';
import { withShortcutKey } from '../../places';
import type { Platform } from '../../platform';
import { problem } from '../../toasts';
import {
    headActions,
    kindIcon,
    partCount,
    partsHad,
    priorityKey,
    rating,
    remaining,
    shown,
    sourceDot,
    sourceNames,
    sourceShown,
    SOURCE_STATES,
    STATES,
    stateNote,
    withoutColon,
    type HeadAction,
} from '../../transfers';
import {
    askCancel,
    askSource,
    categorize,
    cleanName,
    copyLinks,
    each,
    prioritize,
    rename,
    swapSource,
} from '../../transfers-actions';
import EmptyState from '../../ui/EmptyState.vue';
import FileIcon from '../../ui/FileIcon.vue';
import Icon from '../../ui/Icon.vue';
import PartBar from '../../ui/PartBar.vue';
import Tip from '../../ui/Tip.vue';
import Country from './Country.vue';

// The details of the download chosen, under the list: what it is, how far it got and its parts drawn large with what
// each colour means, what to do now; beside them, in tabs, its sources, the facts the columns leave out, the names its
// sources give it, to rename it by, and what others said of it.

const props = defineProps<{ chosen: Download[]; platform: Platform }>();
const emit = defineEmits<{ close: []; comment: [download: Download] }>();

type Tab = 'sources' | 'facts' | 'names' | 'comments';
type Comment = Schemas['Comment'];

const TABS: { tab: Tab; key: string }[] = [
    { tab: 'sources', key: 'IDS_DL_SOURCES' },
    { tab: 'facts', key: 'IDS_DETAILS' },
    { tab: 'names', key: 'IDS_SOURCENAMES' },
    { tab: 'comments', key: 'IDS_COMMENT' },
];
const ACTIONS: Record<HeadAction, { key: string; icon: 'PAUSE' | 'PLAY' | 'STOP' | 'PERSON_ADD' | 'FOLDER_OPEN' }> = {
    pause: { key: 'IDS_DL_PAUSE', icon: 'PAUSE' },
    resume: { key: 'IDS_DL_RESUME', icon: 'PLAY' },
    stop: { key: 'IDS_DL_STOP', icon: 'STOP' },
    addSource: { key: 'IDS_ADDSRCMANUALLY', icon: 'PERSON_ADD' },
    openIncoming: { key: 'IDS_OPENINC', icon: 'FOLDER_OPEN' },
};
const PRIORITIES: { value: Download['priority']; key: string }[] = [
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];
const NO_PARTS: Schemas['PartMap'] = { size: 0, missing: [], requested: [], sources: [] };
// The characters of a long name kept whole at its end, its extension among them: the name is cut in its middle.
const NAME_END = 12;

const tab = ref<Tab>('sources');
const parts = shallowRef<Schemas['PartMap'] | null>(null);
const comments = shallowRef<Comment[] | null>(null);
const pickedSource = ref<string | null>(null);
const pickedName = ref<string | null>(null);
const newName = ref('');
const head = ref<HTMLElement | null>(null);
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const browsing = ref(false);
const browsed = ref<{ address: string; name: string } | null>(null);
// The download the new name is for: it is reset only when another one is shown, not as the figures move.
let renaming = '';

const one = computed(() => (props.chosen.length === 1 ? props.chosen[0]! : null));
const state = computed(() => (one.value === null ? null : shown(one.value)));
const note = computed(() => (state.value === null ? null : stateNote(state.value)));
const itsSources = computed(() => (one.value === null ? [] : sources.value.filter((s) => s.file === one.value!.hash)));
const names = computed(() => (one.value === null ? [] : sourceNames(sources.value, one.value.hash)));
// A finished download has its file already.
const renamable = computed(() => one.value !== null && one.value.status !== 'complete');

function sourceKey(s: Source): string {
    return `${s.file}/${s.address}`;
}

const picked = computed(() => itsSources.value.find((s) => sourceKey(s) === pickedSource.value) ?? null);

function categoryName(id: number): string {
    return categories.value.find((c) => c.id === id)?.name ?? '';
}

function remainingText(d: Download): string {
    return remaining(d) < 0 ? '—' : duration(remaining(d));
}

const nameEnd = computed(() => {
    const name = one.value?.name ?? '';
    return name.slice(name.length - Math.min(NAME_END, Math.floor(name.length / 2)));
});
const nameStart = computed(() =>
    (one.value?.name ?? '').slice(0, (one.value?.name.length ?? 0) - nameEnd.value.length),
);

const meta = computed(() => {
    const d = one.value!;
    const category = categoryName(d.category);
    return [size(d.size), ...(category === '' ? [] : [category]), t(priorityKey(d))].join(' · ');
});

const rateLine = computed(() => {
    const d = one.value!;
    return state.value === 'downloading'
        ? `↓ ${rate(d.bytesPerSecond)} · ${remainingText(d)}`
        : t(STATES[state.value!].key);
});

const had = computed(() =>
    one.value === null ? 0 : partsHad(one.value, parts.value?.size ? parts.value.missing : null),
);

const facts = computed(() => {
    const d = one.value!;
    const share = percent(d.completed, d.size);
    return [
        [withoutColon(t('nj.transfers.status')), t(STATES[state.value!].key)],
        [withoutColon(t('IDS_FD_SIZE')), size(d.size)],
        [withoutColon(t('IDS_FD_COMPSIZE')), `${size(d.completed)} (${share})`],
        [withoutColon(t('IDS_FD_PARTS')), `${had.value} / ${partCount(d.size)}`],
        [withoutColon(t('IDS_FD_DATARATE')), rate(d.bytesPerSecond)],
        [withoutColon(t('IDS_DL_SOURCES')), String(d.sources)],
        [withoutColon(t('IDS_TRANSFERRING')), String(d.transferring)],
        [withoutColon(t('IDS_PRIORITY')), t(priorityKey(d))],
        [withoutColon(t('IDS_CAT')), categoryName(d.category)],
        [withoutColon(t('IDS_DL_REMAINS')), remainingText(d)],
    ];
});

function count(tab: Tab): number | null {
    switch (tab) {
        case 'sources':
            return itsSources.value.length;
        case 'names':
            return names.value.length;
        case 'comments':
            return comments.value?.length ?? null;
        default:
            return null;
    }
}

// The map of parts, read again with each second's news of the download.
watch(
    () => [one.value?.hash, one.value?.completed],
    async () => {
        const d = one.value;
        if (d === null) {
            parts.value = null;
            return;
        }
        try {
            parts.value = (
                await answer(api().GET('/downloads/{hash}/parts', { params: { path: { hash: d.hash } } }))
            ).parts;
        } catch {
            parts.value = null;
        }
    },
    { immediate: true },
);

// Another download: its comments are asked for once, its name is the new name to start from.
watch(
    () => one.value?.hash,
    async (hash) => {
        pickedSource.value = null;
        pickedName.value = null;
        comments.value = null;
        if (hash === undefined) {
            return;
        }
        if (hash !== renaming) {
            renaming = hash;
            newName.value = one.value!.name;
        }
        try {
            const found = await answer(api().GET('/downloads/{hash}/comments', { params: { path: { hash } } }));
            if (one.value?.hash === hash) {
                comments.value = found;
            }
        } catch {
            // None to show.
        }
    },
    { immediate: true },
);

async function act(action: HeadAction): Promise<void> {
    const d = one.value;
    if (d === null) {
        return;
    }
    switch (action) {
        case 'pause':
        case 'resume':
        case 'stop':
            await each(`/downloads/{hash}/${action}`, [d]);
            break;
        case 'addSource':
            await askSource(d);
            break;
        case 'openIncoming':
            await openIncoming();
            break;
    }
}

// Where finished downloads go, in the system's file manager.
async function openIncoming(): Promise<void> {
    const folder = folders.value.incoming;
    if (folder && !(await props.platform.openFolder(folder))) {
        problem(folder);
    }
}

async function copyHash(hash: string): Promise<void> {
    try {
        await navigator.clipboard.writeText(hash);
    } catch (e) {
        problem((e as Error).message);
    }
}

// The arrows go through the sources as through a list.
function sourceKeys(e: KeyboardEvent): void {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') {
        return;
    }
    const keys = itsSources.value.map(sourceKey);
    const at = keys.indexOf(pickedSource.value ?? '');
    const next = keys[Math.max(0, Math.min(keys.length - 1, at + (e.key === 'ArrowDown' ? 1 : -1)))];
    if (next !== undefined) {
        pickedSource.value = next;
    }
    e.preventDefault();
}

function viewFiles(): void {
    const s = picked.value;
    if (s !== null) {
        browsed.value = { address: s.address, name: s.nick ?? s.address };
        browsing.value = true;
    }
}

// What a source sent while it sends, else its place in a queue.
function sourceFigure(s: Source): string {
    if (s.state === 'downloading') {
        return size(s.received);
    }
    return s.queueRank > 0 ? `${t('nj.transfers.queueRank')} ${s.queueRank}` : '';
}

function takeOver(name: string | null): void {
    if (name !== null && renamable.value) {
        newName.value = name;
    }
}

async function cleanUp(): Promise<void> {
    newName.value = await cleanName(newName.value);
}

async function renameShown(): Promise<void> {
    const name = newName.value.trim();
    if (one.value !== null && name !== '') {
        await rename(one.value, name);
    }
}

// The padding of the head and the borders of the details, around what the head holds.
const HEAD_FRAME = 2 * 12 + 2;

/** How tall the details must be to show the download's head, its buttons included. */
function neededHeight(): number {
    return head.value === null ? 0 : head.value.offsetHeight + HEAD_FRAME;
}

defineExpose({ neededHeight });
</script>

<template>
    <section class="download-details">
        <p v-if="chosen.length === 0" class="details-none">{{ t('nj.details.none') }}</p>
        <div v-else-if="one === null" class="details-many">
            <strong class="details-name">{{ t('nj.details.chosen', chosen.length) }}</strong>
            <div class="buttons">
                <button class="button" @click="each('/downloads/{hash}/pause', chosen)">
                    <Icon name="PAUSE" />{{ t('IDS_DL_PAUSE') }}
                </button>
                <button class="button" @click="each('/downloads/{hash}/resume', chosen)">
                    <Icon name="PLAY" />{{ t('IDS_DL_RESUME') }}
                </button>
                <button class="button" @click="each('/downloads/{hash}/stop', chosen)">
                    <Icon name="STOP" />{{ t('IDS_DL_STOP') }}
                </button>
                <button class="button" @click="askCancel(chosen)">
                    <Icon name="DELETE" />{{ t('nj.transfers.cancelMany') }}
                </button>
                <button class="button" @click="copyLinks(chosen)"><Icon name="COPY" />{{ t('nj.copyLink') }}</button>
            </div>
        </div>
        <template v-else>
            <div class="details-head">
                <div ref="head" class="head-inner">
                    <div class="top">
                        <span class="kind-box"><Icon :name="kindIcon(one.kind)" /></span>
                        <div class="title">
                            <Tip :text="one.name">
                                <strong class="details-name" dir="auto"
                                    ><span class="name-start">{{ nameStart }}</span
                                    ><span class="name-end">{{ nameEnd }}</span></strong
                                >
                            </Tip>
                            <span class="details-meta">{{ meta }}</span>
                        </div>
                    </div>
                    <div class="progress">
                        <span class="details-percent display">{{ percent(one.completed, one.size) }}</span>
                        <span class="figures">
                            <span>{{ t('nj.details.doneOf', size(one.completed), size(one.size)) }}</span>
                            <span>{{ rateLine }}</span>
                        </span>
                    </div>
                    <PartBar
                        :parts="parts ?? NO_PARTS"
                        :completed="one.completed"
                        :text="`${percent(one.completed, one.size)}, ${had}/${partCount(one.size)}`"
                    />
                    <p v-if="note" class="details-note" :class="note.problem ? 'danger' : 'warning'">
                        <Icon :name="note.problem ? 'ERROR' : 'WARNING'" /><span>{{ t(note.key) }}</span>
                    </p>
                    <div class="action-row">
                        <div class="to-do">
                            <button
                                v-for="a in headActions(state!)"
                                :key="a"
                                class="button"
                                :disabled="a === 'openIncoming' && !filesHere(platform)"
                                @click="act(a)"
                            >
                                <Icon :name="ACTIONS[a].icon" />{{ t(ACTIONS[a].key) }}
                            </button>
                        </div>
                        <Tip :text="`${t('nj.copyLink')}  ${withShortcutKey('C')}`">
                            <button class="button icon-only" :aria-label="t('nj.copyLink')" @click="copyLinks([one])">
                                <Icon name="COPY" />
                            </button>
                        </Tip>
                        <DropdownMenuRoot>
                            <Tip :text="t('IDS_MORE')">
                                <DropdownMenuTrigger
                                    ref="moreTrigger"
                                    class="button icon-only"
                                    :aria-label="t('IDS_MORE')"
                                >
                                    <Icon name="MORE" />
                                </DropdownMenuTrigger>
                            </Tip>
                            <DropdownMenuPortal>
                                <DropdownMenuContent class="menu" align="end" :reference="moreTrigger?.$el">
                                    <DropdownMenuItem
                                        class="menu-item"
                                        :disabled="!filesHere(platform)"
                                        @select="preview(platform, one.hash)"
                                    >
                                        <Icon name="PREVIEW" />{{ t('IDS_DL_PREVIEW') }}
                                    </DropdownMenuItem>
                                    <DropdownMenuItem class="menu-item" @select="emit('comment', one)">
                                        <Icon name="COMMENT" />{{ t('IDS_CMT_ADD') }}
                                    </DropdownMenuItem>
                                    <DropdownMenuSub>
                                        <DropdownMenuSubTrigger class="menu-item">
                                            <Icon name="PRIORITY" />{{ t('IDS_PRIORITY')
                                            }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                                        </DropdownMenuSubTrigger>
                                        <DropdownMenuPortal>
                                            <DropdownMenuSubContent class="menu">
                                                <DropdownMenuItem
                                                    v-for="p in PRIORITIES"
                                                    :key="p.value"
                                                    class="menu-item"
                                                    @select="prioritize([one], p.value)"
                                                >
                                                    {{ t(p.key) }}
                                                </DropdownMenuItem>
                                            </DropdownMenuSubContent>
                                        </DropdownMenuPortal>
                                    </DropdownMenuSub>
                                    <DropdownMenuSub>
                                        <DropdownMenuSubTrigger class="menu-item">
                                            <Icon name="LABEL" />{{ t('IDS_CAT')
                                            }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                                        </DropdownMenuSubTrigger>
                                        <DropdownMenuPortal>
                                            <DropdownMenuSubContent class="menu">
                                                <DropdownMenuItem class="menu-item" @select="categorize([one], 0)">
                                                    {{ t('IDS_CAT_UNASSIGN') }}
                                                </DropdownMenuItem>
                                                <DropdownMenuItem
                                                    v-for="c in categories"
                                                    :key="c.id"
                                                    class="menu-item"
                                                    @select="categorize([one], c.id)"
                                                >
                                                    <span
                                                        class="category-dot"
                                                        :class="c.color ? `cat-${c.color}` : ''"
                                                    />{{ c.name }}
                                                </DropdownMenuItem>
                                            </DropdownMenuSubContent>
                                        </DropdownMenuPortal>
                                    </DropdownMenuSub>
                                    <DropdownMenuItem class="menu-item" @select="askSource(one)">
                                        <Icon name="PERSON_ADD" />{{ t('IDS_ADDSRCMANUALLY') }}
                                    </DropdownMenuItem>
                                    <DropdownMenuSeparator class="menu-separator" />
                                    <DropdownMenuItem class="menu-item danger-item" @select="askCancel([one])">
                                        <Icon name="DELETE" />{{ t('nj.transfers.cancelOne') }}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenuPortal>
                        </DropdownMenuRoot>
                    </div>
                </div>
            </div>
            <div class="tabbed">
                <div class="details-tabs">
                    <div class="tab-row" role="tablist">
                        <button
                            v-for="item in TABS"
                            :key="item.tab"
                            class="tab"
                            role="tab"
                            :aria-selected="tab === item.tab"
                            @click="tab = item.tab"
                        >
                            {{ t(item.key)
                            }}<span v-if="count(item.tab) !== null" class="chip-count">{{ count(item.tab) }}</span>
                        </button>
                    </div>
                    <Tip :text="t('IDS_FD_CLOSE')">
                        <button class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')" @click="emit('close')">
                            <Icon name="CLOSE" />
                        </button>
                    </Tip>
                </div>
                <ContextMenuRoot v-if="tab === 'sources'">
                    <ContextMenuTrigger as-child>
                        <div
                            class="source-list"
                            role="listbox"
                            tabindex="0"
                            :aria-label="t('IDS_DL_SOURCES')"
                            @keydown="sourceKeys"
                        >
                            <EmptyState
                                v-if="itsSources.length === 0"
                                icon="NO_SOURCES"
                                :text="t('nj.transfers.noSources')"
                            >
                                <button class="button" @click="askSource(one)">
                                    <Icon name="PERSON_ADD" />{{ t('IDS_ADDSRCMANUALLY') }}
                                </button>
                            </EmptyState>
                            <Tip
                                v-for="s in itsSources"
                                :key="sourceKey(s)"
                                :text="
                                    s.state === 'a4af' && s.askedForName !== ''
                                        ? `${t('nj.transfers.askedFor')}: ${s.askedForName}`
                                        : ''
                                "
                            >
                                <div
                                    class="source-item"
                                    role="option"
                                    :aria-selected="pickedSource === sourceKey(s)"
                                    @click="pickedSource = sourceKey(s)"
                                    @contextmenu="pickedSource = sourceKey(s)"
                                >
                                    <span class="who">
                                        <span v-if="s.country" class="small muted"><Country :code="s.country" /></span>
                                        <strong class="ellipsis" dir="auto">{{ s.nick ?? s.address }}</strong>
                                        <span class="small muted ellipsis" dir="ltr">{{ s.software }}</span>
                                    </span>
                                    <span class="figure" dir="ltr">{{ sourceFigure(s) }}</span>
                                    <span class="small muted state">
                                        <span class="source-dot" :class="sourceDot(sourceShown(s))">{{
                                            sourceDot(sourceShown(s)) === 'unknown' ? '?' : ''
                                        }}</span
                                        >{{ t(SOURCE_STATES[sourceShown(s)]) }}
                                    </span>
                                    <span class="thin-bar has">
                                        <span
                                            class="thin-bar-fill accent"
                                            :style="{
                                                width: `${s.partCount === 0 ? 0 : (s.parts * 100) / s.partCount}%`,
                                                display: 'block',
                                            }"
                                        />
                                    </span>
                                </div>
                            </Tip>
                        </div>
                    </ContextMenuTrigger>
                    <ContextMenuPortal>
                        <ContextMenuContent class="menu">
                            <ContextMenuItem class="menu-item" :disabled="picked === null" @select="viewFiles">
                                <Icon name="FOLDER_OPEN" />{{ t('IDS_VIEWFILES') }}
                            </ContextMenuItem>
                            <ContextMenuItem
                                class="menu-item"
                                :disabled="picked === null || picked.state !== 'a4af'"
                                @select="swapSource(picked!.file, picked!.address)"
                            >
                                <Icon name="SWAP" />{{ t('nj.a4af.swapToThis') }}
                            </ContextMenuItem>
                        </ContextMenuContent>
                    </ContextMenuPortal>
                </ContextMenuRoot>
                <div v-else-if="tab === 'facts'" class="details-page">
                    <dl class="facts">
                        <template v-for="[name, value] in facts" :key="name">
                            <dt class="muted">{{ name }}</dt>
                            <dd dir="auto">{{ value }}</dd>
                        </template>
                    </dl>
                    <span class="muted">{{ withoutColon(t('IDS_FD_HASH')) }}</span>
                    <div class="row">
                        <span class="field grow"
                            ><input class="hash" readonly :value="one.hash" dir="ltr" :aria-label="t('IDS_FD_HASH')"
                        /></span>
                        <Tip :text="t('IDS_COPY')">
                            <button
                                class="button flat icon-only"
                                :aria-label="t('IDS_COPY')"
                                @click="copyHash(one.hash)"
                            >
                                <Icon name="COPY" />
                            </button>
                        </Tip>
                    </div>
                </div>
                <div v-else-if="tab === 'names'" class="details-page">
                    <div class="names" role="grid" :aria-label="t('IDS_SOURCENAMES')">
                        <div class="names-head" role="row">
                            <span role="columnheader">{{ t('IDS_DL_FILENAME') }}</span>
                            <span role="columnheader" class="count">{{ t('IDS_DL_SOURCES') }}</span>
                        </div>
                        <div class="names-body">
                            <div
                                v-for="n in names"
                                :key="n.name"
                                class="names-row"
                                role="row"
                                :aria-selected="pickedName === n.name"
                                @click="pickedName = n.name"
                                @dblclick="takeOver(n.name)"
                            >
                                <span class="name-cell" role="gridcell"
                                    ><FileIcon :name="n.name" /><span class="ellipsis" dir="auto">{{
                                        n.name
                                    }}</span></span
                                >
                                <span class="count" role="gridcell">{{ n.sources }}</span>
                            </div>
                        </div>
                    </div>
                    <span class="field"
                        ><input v-model="newName" :disabled="!renamable" :aria-label="t('IDS_RENAME')" dir="auto"
                    /></span>
                    <div class="buttons">
                        <button class="button" :disabled="!renamable" @click="takeOver(pickedName)">
                            <Icon name="ALIGN_BOTTOM" />{{ t('IDS_TAKEOVER') }}
                        </button>
                        <button class="button" :disabled="!renamable" @click="cleanUp">
                            <Icon name="CLEAN" />{{ t('IDS_CLEANUP') }}
                        </button>
                        <button class="button" :disabled="!renamable" @click="renameShown">
                            <Icon name="EDIT" />{{ t('IDS_RENAME') }}
                        </button>
                    </div>
                </div>
                <div v-else class="details-page">
                    <ul class="comment-list">
                        <li v-if="(comments ?? []).length === 0" class="muted">{{ t('IDS_CMT_NONE') }}</li>
                        <li v-for="(c, i) in comments ?? []" :key="i">
                            <span class="comment-head">
                                <strong dir="auto">{{ c.who }}</strong>
                                <span v-if="c.rating > 0" class="state-chip" :class="rating(c.rating).tone"
                                    ><Icon name="STAR" />{{ t(rating(c.rating).key) }}</span
                                >
                            </span>
                            <span class="small muted" dir="auto">{{ c.text }}</span>
                        </li>
                    </ul>
                    <div>
                        <button class="button" @click="emit('comment', one)">
                            <Icon name="COMMENT" />{{ t('IDS_CMT_ADD') }}
                        </button>
                    </div>
                </div>
            </div>
        </template>
        <ClientFilesDialog v-model:open="browsing" :client="browsed" />
    </section>
</template>

<style scoped>
.download-details {
    display: flex;
    flex: none;
    min-height: 0;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
    overflow: hidden;
}

.details-none {
    margin: 0;
    padding: 16px;
    color: var(--color-fg-muted);
}

.details-many {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px 16px;
}

.buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}

/* What the download is, beside its tabs: wide enough for most names on one line and the legend on two. */
.details-head {
    flex: none;
    width: 440px;
    padding: 12px 16px;
    border-inline-end: 1px solid var(--color-border-muted);
    overflow-y: auto;
}

.head-inner {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.top {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    min-width: 0;
}

.kind-box {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: none;
    width: 40px;
    height: 40px;
    border-radius: 8px;
    background: var(--color-bg-subtle);
    --nj-icon-size: 20px;
    --nj-icon-color: var(--color-fg-muted);
}

.title {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
}

/* One line, cut in its middle so that the end of the name shows; whole in its tooltip. */
.details-name {
    display: flex;
    min-width: 0;
    font-size: 1.05em;
    white-space: nowrap;
}

.name-start {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
}

.name-end {
    flex: none;
}

.details-meta,
.figures {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.progress {
    display: flex;
    align-items: flex-end;
    gap: 12px;
}

.details-percent {
    font-size: 2.15em;
    font-weight: bold;
    line-height: 1;
    direction: ltr;
}

.figures {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    margin-inline-start: auto;
}

/* A state that wants the user: a pastel note of its tone, the words in the theme's colour. */
.details-note {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin: 0;
    padding: 8px 10px;
    border: 1px solid transparent;
    border-radius: 6px;
    font-size: 0.92em;
    --nj-icon-size: 16px;
}

.details-note.warning {
    background: var(--color-warning-subtle);
    border-color: var(--color-warning-muted);
    --nj-icon-color: var(--color-warning-fg);
}

.details-note.danger {
    background: var(--color-danger-subtle);
    border-color: var(--color-danger-muted);
    --nj-icon-color: var(--color-danger-fg);
}

.action-row {
    display: flex;
    align-items: flex-start;
    gap: 8px;
}

.to-do {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    flex: 1;
    min-width: 0;
}

.tabbed {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
}

/* The tabs, underlined rather than drawn as tabs, and at the end of their row the button that closes the details. */
.details-tabs {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 16px;
    border-bottom: 1px solid var(--color-border-muted);
}

.tab-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0 14px;
    flex: 1;
}

.tab {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 10px 2px 8px;
    border: 0;
    border-bottom: 2px solid transparent;
    background: transparent;
    color: var(--color-fg-muted);
    font: inherit;
    cursor: pointer;
}

.tab:hover {
    color: var(--color-fg-default);
}

.tab[aria-selected='true'] {
    color: var(--color-fg-default);
    border-bottom-color: var(--color-accent-emphasis);
    font-weight: bold;
}

.chip-count {
    color: var(--color-fg-muted);
    font-size: 0.9em;
    font-weight: normal;
}

.small {
    font-size: 0.92em;
}

.source-list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    outline: none;
}

.source-list:focus-visible {
    outline: 2px solid var(--color-accent-emphasis);
    outline-offset: -2px;
}

/* A source in two lines: who it is and what it sent or its place in a queue; what it does and how much it has. */
.source-item {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 3px 12px;
    align-items: center;
    padding: 8px 16px;
    border-bottom: 1px solid var(--color-border-muted);
}

.source-item[aria-selected='true'] {
    background: var(--color-accent-muted);
}

.who,
.state {
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 0;
}

.state {
    align-items: center;
}

.figure {
    justify-self: end;
    font-weight: bold;
    font-size: 0.92em;
}

.has {
    flex: none;
    width: 96px;
}

.details-page {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-height: 0;
    padding: 12px 16px 16px;
    overflow-y: auto;
}

.facts {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 6px 16px;
    margin: 0;
}

.facts dt {
    white-space: nowrap;
}

.facts dd {
    margin: 0;
    min-width: 0;
    overflow-wrap: anywhere;
}

.row {
    display: flex;
    align-items: center;
    gap: 6px;
}

.grow {
    flex: 1;
    min-width: 0;
}

.hash {
    font-family: monospace;
    font-size: 0.9em;
}

.names {
    display: flex;
    flex-direction: column;
    flex: none;
    height: 150px;
    min-height: 90px;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    overflow: hidden;
}

.names-head,
.names-row {
    display: flex;
    gap: 8px;
    padding: 0 8px;
}

.names-head {
    height: 30px;
    align-items: center;
    background: var(--color-bg-subtle);
    border-bottom: 1px solid var(--color-border-default);
    font-weight: bold;
}

.names-body {
    flex: 1;
    overflow-y: auto;
}

.names-row {
    height: 28px;
    align-items: center;
    border-bottom: 1px solid var(--color-border-muted);
    cursor: default;
}

.names-row[aria-selected='true'] {
    background: var(--color-accent-muted);
}

.names-head > :first-child,
.names-row > :first-child {
    flex: 1;
    min-width: 0;
}

.count {
    flex: none;
    width: 90px;
    text-align: end;
}

.comment-list {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-height: 0;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
}

.comment-list li {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px 0;
    border-bottom: 1px solid var(--color-border-muted);
}

.comment-head {
    display: flex;
    align-items: center;
    gap: 8px;
}

/* Cancelling is red: what was downloaded goes. */
.danger-item {
    color: var(--color-danger-fg);
    --nj-icon-color: var(--color-danger-fg);
}

/* A name the sources gave beside the icon of its kind, taking the room the count leaves. */
.names-row .name-cell {
    display: flex;
    flex: 1;
    align-items: center;
    gap: 6px;
    min-width: 0;
}
</style>
