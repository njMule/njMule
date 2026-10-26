<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElDropdown, ElDropdownItem, ElDropdownMenu, ElInput, ElTable, ElTableColumn } from 'element-plus';
import { computed, onMounted, onUnmounted, onUpdated, ref, useTemplateRef, watch } from 'vue';
import { api } from '../../api';
import { copy } from '../../clipboard';
import CountryFlag from '../../components/CountryFlag.vue';
import NjIcon from '../../components/NjIcon.vue';
import PartBar from '../../components/PartBar.vue';
import { duration, percent, rate, size } from '../../format';
import { t } from '../../i18n';
import type { IconName } from '../../icons';
import { attempt } from '../../notify';
import { guest } from '../../role';
import {
    PRIORITIES,
    SOURCE_STATES,
    STATES,
    detailsActions,
    kindIcon,
    partsHave,
    shown,
    sourceDot,
    sourceNames,
    sourceShown,
    type DetailsAction,
} from '../../transfers';
import type { Category, Download, DownloadPriority, Parts, Source } from '../../types';

// The downloads chosen; the sources and the parts are those of the one chosen alone.
const props = defineProps<{ chosen: Download[]; sources: Source[]; parts: Parts | null; categories: Category[] }>();
const emit = defineEmits<{
    act: [what: 'pause' | 'resume' | 'stop'];
    cancel: [];
    copyLink: [];
    comments: [];
    addSource: [];
    priority: [value: DownloadPriority];
    category: [id: number];
    browse: [source: Source];
    swap: [source: Source];
    close: [];
    changed: [];
}>();

type Tab = 'sources' | 'facts' | 'names' | 'comments';

interface Comment {
    who: string;
    fileName: string;
    rating: number;
    text: string;
}

// The ratings from none to the best, as clients number them, with the tone of each.
const RATINGS = [
    'IDS_CMT_NOTRATED',
    'IDS_CMT_FAKE',
    'IDS_CMT_POOR',
    'IDS_CMT_FAIR',
    'IDS_CMT_GOOD',
    'IDS_CMT_EXCELLENT',
];
const RATING_TONES = ['', 'error', 'warn', '', 'busy', 'ok'];

const ACTIONS: Record<DetailsAction, { key: string; icon: IconName }> = {
    pause: { key: 'IDS_DL_PAUSE', icon: 'PAUSE' },
    resume: { key: 'IDS_DL_RESUME', icon: 'PLAY' },
    stop: { key: 'IDS_DL_STOP', icon: 'STOP' },
    addSource: { key: 'IDS_ADDSRCMANUALLY', icon: 'PERSON_ADD' },
};

const tab = ref<Tab>('sources');
const one = computed(() => (props.chosen.length === 1 ? props.chosen[0] : undefined));
const state = computed(() => (one.value ? shown(one.value) : 'waiting'));
const words = computed(() => STATES[state.value]);
const actions = computed(() => (guest ? [] : detailsActions(state.value)));
const have = computed(() => (one.value ? partsHave(one.value, props.parts) : { have: 0, total: 0 }));
const names = computed(() => sourceNames(props.sources));
const note = computed(() => {
    switch (state.value) {
        case 'noSources':
            return { key: 'nj.details.noSourcesNote', tone: 'warning', icon: 'WARNING' as IconName };
        case 'noSpace':
            return { key: 'nj.details.diskFullNote', tone: 'danger', icon: 'ERROR' as IconName };
        case 'error':
            return { key: 'nj.details.errorNote', tone: 'danger', icon: 'ERROR' as IconName };
        default:
            return null;
    }
});

function priorityWord(d: Download): string {
    return t(PRIORITIES.find((p) => p.value === d.priority)?.key ?? 'IDS_PRIOAUTO');
}

function categoryWord(d: Download): string {
    return props.categories.find((c) => c.id === d.category)?.name ?? '';
}

function remaining(d: Download): string {
    return d.bytesPerSecond > 0 && d.status !== 'complete' ? duration((d.size - d.completed) / d.bytesPerSecond) : '—';
}

const meta = computed(() => {
    const d = one.value;
    if (!d) {
        return '';
    }
    const category = categoryWord(d);
    return [size(d.size), ...(category ? [category] : []), priorityWord(d)].join(' · ');
});

function act(what: DetailsAction): void {
    if (what === 'addSource') {
        emit('addSource');
    } else {
        emit('act', what);
    }
}

function more(command: string): void {
    if (command === 'comments') {
        emit('comments');
    } else if (command === 'source') {
        emit('addSource');
    } else if (command === 'cancel') {
        emit('cancel');
    } else if (command.startsWith('priority:')) {
        emit('priority', command.substring('priority:'.length) as DownloadPriority);
    } else if (command.startsWith('category:')) {
        emit('category', Number(command.substring('category:'.length)));
    }
}

// The comments of the sources, asked for once for each download shown.
const comments = ref<Comment[]>([]);
const newName = ref('');
const picked = ref<string | null>(null);

watch(
    () => one.value?.hash,
    async (hash) => {
        comments.value = [];
        picked.value = null;
        newName.value = one.value?.name ?? '';
        if (hash !== undefined) {
            try {
                const found = await api.get<Comment[]>(`/downloads/${hash}/comments`);
                if (one.value?.hash === hash) {
                    comments.value = found;
                }
            } catch {
                // None to show.
            }
        }
    },
    { immediate: true },
);

// A finished download has its file already.
const renamable = computed(() => !guest && one.value !== undefined && one.value.status !== 'complete');

function takeOver(): void {
    if (picked.value !== null && renamable.value) {
        newName.value = picked.value;
    }
}

async function cleanUp(): Promise<void> {
    await attempt(async () => {
        newName.value = (await api.post<{ name: string }>('/names/cleanup', { name: newName.value })).name;
    });
}

async function rename(): Promise<void> {
    const d = one.value;
    const name = newName.value.trim();
    if (d && name !== '') {
        await attempt(() => api.put(`/downloads/${d.hash}/name`, { name }));
        emit('changed');
    }
}

const facts = computed(() => {
    const d = one.value;
    if (!d) {
        return [];
    }
    return [
        { key: 'nj.transfers.status', value: t(words.value.key) },
        { key: 'IDS_FD_SIZE', value: size(d.size) },
        { key: 'IDS_FD_COMPSIZE', value: `${size(d.completed)} (${percent(d.completed, d.size)})` },
        { key: 'IDS_FD_PARTS', value: `${have.value.have} / ${have.value.total}` },
        { key: 'IDS_FD_DATARATE', value: rate(d.bytesPerSecond) },
        { key: 'IDS_DL_SOURCES', value: String(d.sources) },
        { key: 'IDS_TRANSFERRING', value: String(d.transferring) },
        { key: 'IDS_PRIORITY', value: priorityWord(d) },
        { key: 'IDS_CAT', value: categoryWord(d) },
        { key: 'IDS_DL_REMAINS', value: remaining(d) },
    ];
});

// Without the colon some translations end with.
function label(key: string): string {
    return t(key).trim().replace(/:$/, '');
}

function figure(s: Source): string {
    if (s.state === 'downloading') {
        return size(s.received);
    }
    return s.queueRank > 0 ? `${t('nj.transfers.queueRank')} ${s.queueRank}` : '';
}

const TABS: { tab: Tab; key: string }[] = [
    { tab: 'sources', key: 'IDS_DL_SOURCES' },
    { tab: 'facts', key: 'IDS_DETAILS' },
    { tab: 'names', key: 'IDS_SOURCENAMES' },
    { tab: 'comments', key: 'IDS_COMMENT' },
];

function counted(which: Tab): number | null {
    switch (which) {
        case 'sources':
            return props.sources.length;
        case 'names':
            return names.value.length;
        case 'comments':
            return comments.value.length;
        default:
            return null;
    }
}

// As tall as the user left them, else as the head and its buttons need, never lower than that.
const HEIGHT_KEY = 'njmule.details.height';
const head = useTemplateRef<HTMLElement>('head');
const headHeight = ref(0);
const wanted = ref(readHeight());
let watcher: ResizeObserver | undefined;

function readHeight(): number | null {
    try {
        const kept = Number(localStorage.getItem(HEIGHT_KEY));
        return kept > 0 ? kept : null;
    } catch {
        return null;
    }
}

const least = computed(() => headHeight.value + 18);
const height = computed(() => Math.max(wanted.value ?? 300, least.value));

// Measured after each change of what the head shows, and as its width changes the lines it takes.
function measure(): void {
    const now = head.value?.offsetHeight ?? 0;
    if (now !== headHeight.value) {
        headHeight.value = now;
    }
}

onMounted(() => {
    watcher = new ResizeObserver(measure);
    if (head.value) {
        watcher.observe(head.value);
    }
    measure();
});
onUpdated(measure);
onUnmounted(() => watcher?.disconnect());
watch(head, (now) => {
    watcher?.disconnect();
    if (now) {
        watcher?.observe(now);
    }
    measure();
});

// The edge on top is dragged as the divider of the desktop app; where it is let go is remembered.
function drag(e: PointerEvent): void {
    const grip = e.currentTarget as HTMLElement;
    measure();
    const from = e.clientY;
    const was = height.value;
    try {
        grip.setPointerCapture(e.pointerId);
    } catch {
        // A pointer the browser no longer tracks: the moves still come while it is over the edge.
    }
    const move = (m: PointerEvent): void => {
        const most = Math.max(least.value, window.innerHeight - 160);
        wanted.value = Math.min(most, Math.max(least.value, was - (m.clientY - from)));
    };
    const end = (): void => {
        grip.removeEventListener('pointermove', move);
        grip.removeEventListener('pointerup', end);
        grip.removeEventListener('pointercancel', end);
        try {
            localStorage.setItem(HEIGHT_KEY, String(Math.round(height.value)));
        } catch {
            // Kept for this page only.
        }
    };
    grip.addEventListener('pointermove', move);
    grip.addEventListener('pointerup', end);
    grip.addEventListener('pointercancel', end);
}
</script>

<template>
    <section class="download-details" :style="{ height: `${height}px` }" :aria-label="t('IDS_DETAILS')">
        <div class="grip" role="separator" aria-orientation="horizontal" @pointerdown.prevent="drag"></div>
        <p v-if="chosen.length === 0" class="none">{{ t('nj.details.none') }}</p>
        <div v-else-if="!one" class="many">
            <b class="name">{{ t('nj.details.chosen', chosen.length) }}</b>
            <div v-if="!guest" class="buttons">
                <el-button @click="emit('act', 'pause')"><NjIcon name="PAUSE" />{{ t('IDS_DL_PAUSE') }}</el-button>
                <el-button @click="emit('act', 'resume')"><NjIcon name="PLAY" />{{ t('IDS_DL_RESUME') }}</el-button>
                <el-button @click="emit('act', 'stop')"><NjIcon name="STOP" />{{ t('IDS_DL_STOP') }}</el-button>
                <el-button @click="emit('cancel')"
                    ><NjIcon name="DELETE" />{{ t('nj.transfers.cancelMany') }}</el-button
                >
                <el-button @click="emit('copyLink')"><NjIcon name="COPY" />{{ t('nj.copyLink') }}</el-button>
            </div>
            <button type="button" class="close" :title="t('IDS_FD_CLOSE')" @click="emit('close')">
                <NjIcon name="CLOSE" :label="t('IDS_FD_CLOSE')" />
            </button>
        </div>
        <div v-else class="content">
            <div class="head">
                <div ref="head" class="head-body">
                    <div class="top">
                        <span class="kind"><NjIcon :name="kindIcon(one.kind)" /></span>
                        <div class="title">
                            <b class="name" :title="one.name">{{ one.name }}</b>
                            <span class="meta">{{ meta }}</span>
                        </div>
                    </div>
                    <div class="progress">
                        <span class="percent">{{ percent(one.completed, one.size) }}</span>
                        <span class="figures">
                            <span>{{ t('nj.details.doneOf', size(one.completed), size(one.size)) }}</span>
                            <span v-if="state === 'downloading'"
                                >↓ {{ rate(one.bytesPerSecond) }} · {{ remaining(one) }}</span
                            >
                            <span v-else>{{ t(words.key) }}</span>
                        </span>
                    </div>
                    <PartBar v-if="parts && one.status !== 'complete'" :parts="parts" />
                    <p v-if="note" class="note" :class="note.tone"><NjIcon :name="note.icon" />{{ t(note.key) }}</p>
                    <div class="action-row">
                        <div class="to-do">
                            <el-button v-for="a in actions" :key="a" @click="act(a)">
                                <NjIcon :name="ACTIONS[a].icon" />{{ t(ACTIONS[a].key) }}
                            </el-button>
                        </div>
                        <el-button class="icon" :title="t('nj.copyLink')" @click="emit('copyLink')">
                            <NjIcon name="COPY" :label="t('nj.copyLink')" />
                        </el-button>
                        <el-dropdown v-if="!guest" trigger="click" @command="more">
                            <el-button class="icon" :title="t('IDS_MORE')"
                                ><NjIcon name="MORE" :label="t('IDS_MORE')"
                            /></el-button>
                            <template #dropdown>
                                <el-dropdown-menu>
                                    <el-dropdown-item command="comments">
                                        <NjIcon name="COMMENT" />{{ t('IDS_CMT_ADD') }}
                                    </el-dropdown-item>
                                    <el-dropdown-item disabled divided class="heading">
                                        <NjIcon name="PRIORITY" />{{ t('IDS_PRIORITY') }}
                                    </el-dropdown-item>
                                    <el-dropdown-item
                                        v-for="p in PRIORITIES"
                                        :key="p.value"
                                        :command="`priority:${p.value}`"
                                        :disabled="one.status === 'complete'"
                                        class="choice"
                                        :class="{ current: one.priority === p.value }"
                                    >
                                        {{ t(p.key) }}
                                    </el-dropdown-item>
                                    <el-dropdown-item disabled divided class="heading">
                                        <NjIcon name="LABEL" />{{ t('IDS_CAT') }}
                                    </el-dropdown-item>
                                    <el-dropdown-item
                                        command="category:0"
                                        class="choice"
                                        :class="{ current: one.category === 0 }"
                                    >
                                        {{ t('IDS_CAT_UNASSIGN') }}
                                    </el-dropdown-item>
                                    <el-dropdown-item
                                        v-for="c in categories"
                                        :key="c.id"
                                        :command="`category:${c.id}`"
                                        class="choice"
                                        :class="{ current: one.category === c.id }"
                                    >
                                        {{ c.name }}
                                    </el-dropdown-item>
                                    <el-dropdown-item command="source" divided :disabled="one.status === 'complete'">
                                        <NjIcon name="PERSON_ADD" />{{ t('IDS_ADDSRCMANUALLY') }}
                                    </el-dropdown-item>
                                    <el-dropdown-item
                                        command="cancel"
                                        divided
                                        class="danger"
                                        :disabled="one.status === 'complete'"
                                    >
                                        <NjIcon name="DELETE" />{{ t('nj.transfers.cancelOne') }}
                                    </el-dropdown-item>
                                </el-dropdown-menu>
                            </template>
                        </el-dropdown>
                    </div>
                </div>
            </div>
            <div class="tabbed">
                <div class="tabs">
                    <div class="tab-row" role="tablist">
                        <button
                            v-for="item in TABS"
                            :key="item.tab"
                            type="button"
                            role="tab"
                            class="tab"
                            :aria-selected="tab === item.tab"
                            :class="{ chosen: tab === item.tab }"
                            @click="tab = item.tab"
                        >
                            {{ t(item.key) }}
                            <span v-if="counted(item.tab) !== null" class="count">{{ counted(item.tab) }}</span>
                        </button>
                    </div>
                    <button type="button" class="close" :title="t('IDS_FD_CLOSE')" @click="emit('close')">
                        <NjIcon name="CLOSE" :label="t('IDS_FD_CLOSE')" />
                    </button>
                </div>
                <div class="page" role="tabpanel">
                    <template v-if="tab === 'sources'">
                        <div v-if="sources.length === 0" class="empty">
                            <NjIcon name="NO_SOURCES" />
                            <span>{{ t('nj.transfers.noSources') }}</span>
                            <el-button v-if="!guest && one.status !== 'complete'" @click="emit('addSource')">
                                <NjIcon name="PERSON_ADD" />{{ t('IDS_ADDSRCMANUALLY') }}
                            </el-button>
                        </div>
                        <ul v-else class="sources">
                            <li
                                v-for="s in sources"
                                :key="s.address"
                                :title="
                                    s.state === 'a4af' && s.askedForName
                                        ? `${t('nj.transfers.askedFor')}: ${s.askedForName}`
                                        : undefined
                                "
                            >
                                <span class="who">
                                    <b class="source-name">{{ s.nick ?? s.address }}</b>
                                    <CountryFlag :code="s.country" />
                                    <span class="soft">{{ s.software }}</span>
                                </span>
                                <b class="figure">{{ figure(s) }}</b>
                                <span class="tools">
                                    <button
                                        type="button"
                                        class="mini"
                                        :title="t('IDS_VIEWFILES')"
                                        @click="emit('browse', s)"
                                    >
                                        <NjIcon name="FOLDER_OPEN" :label="t('IDS_VIEWFILES')" />
                                    </button>
                                    <button
                                        v-if="!guest && s.state === 'a4af'"
                                        type="button"
                                        class="mini"
                                        :title="t('nj.a4af.swapToThis')"
                                        @click="emit('swap', s)"
                                    >
                                        <NjIcon name="SWAP" :label="t('nj.a4af.swapToThis')" />
                                    </button>
                                </span>
                                <span class="state">
                                    <span :class="['source-dot', 'dot-' + sourceDot(sourceShown(s))]">{{
                                        sourceShown(s) === 'waiting' ? '?' : ''
                                    }}</span
                                    >{{ t(SOURCE_STATES[sourceShown(s)]) }}
                                </span>
                                <span
                                    class="has"
                                    role="img"
                                    :aria-label="percent(s.parts, s.partCount)"
                                    :style="{ '--share': s.partCount > 0 ? s.parts / s.partCount : 0 }"
                                ></span>
                            </li>
                        </ul>
                    </template>
                    <div v-else-if="tab === 'facts'" class="facts">
                        <dl>
                            <template v-for="f in facts" :key="f.key">
                                <dt>{{ label(f.key) }}</dt>
                                <dd>{{ f.value }}</dd>
                            </template>
                        </dl>
                        <span class="muted">{{ label('IDS_FD_HASH') }}</span>
                        <div class="hash">
                            <el-input :model-value="one.hash" readonly />
                            <el-button class="icon" :title="t('IDS_COPY')" @click="copy(one.hash, t('IDS_COPY'))">
                                <NjIcon name="COPY" :label="t('IDS_COPY')" />
                            </el-button>
                        </div>
                    </div>
                    <div v-else-if="tab === 'names'" class="names">
                        <el-table
                            :data="names"
                            size="small"
                            highlight-current-row
                            :empty-text="' '"
                            @current-change="(row: { name: string } | null) => (picked = row?.name ?? null)"
                            @row-dblclick="takeOver"
                        >
                            <el-table-column :label="t('IDS_DL_FILENAME')" prop="name" show-overflow-tooltip />
                            <el-table-column :label="t('IDS_DL_SOURCES')" prop="sources" width="90" align="right" />
                        </el-table>
                        <el-input v-model="newName" :disabled="!renamable" :aria-label="t('IDS_RENAME')" />
                        <div class="buttons">
                            <el-button :disabled="!renamable || picked === null" @click="takeOver">
                                <NjIcon name="ALIGN_BOTTOM" />{{ t('IDS_TAKEOVER') }}
                            </el-button>
                            <el-button :disabled="!renamable" @click="cleanUp">
                                <NjIcon name="CLEAN" />{{ t('IDS_CLEANUP') }}
                            </el-button>
                            <el-button :disabled="!renamable" @click="rename">
                                <NjIcon name="EDIT" />{{ t('IDS_RENAME') }}
                            </el-button>
                        </div>
                    </div>
                    <div v-else class="comments">
                        <p v-if="comments.length === 0" class="muted">{{ t('IDS_CMT_NONE') }}</p>
                        <ul v-else>
                            <li v-for="(c, i) in comments" :key="i">
                                <span class="said">
                                    <b>{{ c.who }}</b>
                                    <span v-if="c.rating > 0" class="nj-chip" :class="RATING_TONES[c.rating]">
                                        <NjIcon name="STAR" />{{ t(RATINGS[c.rating] ?? 'IDS_CMT_NOTRATED') }}
                                    </span>
                                </span>
                                <span class="muted">{{ c.text }}</span>
                            </li>
                        </ul>
                        <el-button v-if="!guest" @click="emit('comments')">
                            <NjIcon name="COMMENT" />{{ t('IDS_CMT_ADD') }}
                        </el-button>
                    </div>
                </div>
            </div>
        </div>
    </section>
</template>

<style scoped>
/* Under the list and in sight however far it scrolls, as the details of the desktop app under theirs. */
.download-details {
    position: sticky;
    bottom: 0;
    z-index: 3;
    display: flex;
    flex-direction: column;
    margin-top: 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
    box-shadow: 0 -6px 16px -12px var(--nj-shade);
}
.grip {
    flex: none;
    height: 8px;
    margin-bottom: -8px;
    cursor: ns-resize;
    touch-action: none;
    position: relative;
    z-index: 1;
}
.grip::after {
    content: '';
    position: absolute;
    top: 2px;
    left: calc(50% - 18px);
    width: 36px;
    height: 3px;
    border-radius: 2px;
    background: var(--el-border-color-darker);
}
.nj-icon {
    width: 16px;
    height: 16px;
}
.el-button .nj-icon,
.el-dropdown-menu .nj-icon {
    margin-inline-end: 6px;
}
.el-button.icon .nj-icon {
    margin: 0;
}
.none {
    margin: 0;
    padding: 16px;
    color: var(--el-text-color-secondary);
}
.many {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 12px 16px 16px;
    position: relative;
}
.many .close {
    position: absolute;
    top: 8px;
    inset-inline-end: 12px;
}
.buttons,
.to-do {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
}
.buttons .el-button + .el-button,
.to-do .el-button + .el-button,
.action-row .el-button + .el-button {
    margin-left: 0;
}
.content {
    display: flex;
    flex: 1;
    min-height: 0;
}
/* What the download is, beside its tabs; the height of its body is the least the details take. */
.head {
    flex: none;
    width: 440px;
    overflow: auto;
    border-inline-end: 1px solid var(--el-border-color);
}
.head-body {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 16px;
}
.top {
    display: flex;
    gap: 12px;
    align-items: flex-start;
}
.kind {
    flex: none;
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    border-radius: 8px;
    background: var(--el-fill-color-light);
    color: var(--el-text-color-secondary);
}
.kind .nj-icon {
    width: 20px;
    height: 20px;
}
.title {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
}
.name {
    font-size: 15px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.meta,
.figures,
.muted,
.soft,
.state {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.progress {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 12px;
}
.percent {
    font-size: 30px;
    font-weight: 700;
    line-height: 1.1;
    direction: ltr;
}
.figures {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    text-align: end;
}
.note {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0;
    padding: 8px 10px;
    border-radius: 6px;
    font-size: 13px;
}
.note.warning {
    background: var(--nj-warn-bg);
}
.note.warning .nj-icon {
    color: var(--nj-warn-fg);
}
.note.danger {
    background: var(--nj-error-bg);
}
.note.danger .nj-icon {
    color: var(--nj-error-fg);
}
.action-row {
    display: flex;
    align-items: flex-start;
    gap: 8px;
}
.to-do {
    flex: 1;
}
.tabbed {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
    min-height: 0;
}
.tabs {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 16px;
    border-bottom: 1px solid var(--el-border-color);
}
.tab-row {
    display: flex;
    flex-wrap: wrap;
    gap: 0 14px;
    flex: 1;
}
.tab {
    padding: 10px 2px 8px;
    border: 0;
    border-bottom: 2px solid transparent;
    background: none;
    color: var(--el-text-color-secondary);
    font: inherit;
    font-size: 14px;
    cursor: pointer;
}
.tab:hover {
    color: var(--el-text-color-primary);
}
.tab.chosen {
    border-bottom-color: var(--el-color-primary);
    color: var(--el-text-color-primary);
    font-weight: 700;
}
.count {
    margin-inline-start: 4px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    font-weight: 400;
}
.close,
.mini {
    display: grid;
    place-items: center;
    padding: 4px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-secondary);
    cursor: pointer;
}
.close:hover,
.mini:hover {
    background: var(--el-fill-color);
    color: var(--el-text-color-primary);
}
.page {
    flex: 1;
    min-height: 0;
    overflow: auto;
}
.empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 24px 16px;
    color: var(--el-text-color-secondary);
}
.empty .nj-icon {
    width: 40px;
    height: 40px;
}
.sources,
.comments ul {
    margin: 0;
    padding: 0;
    list-style: none;
}
.sources li {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    grid-template-areas: 'who figure tools' 'state has has';
    gap: 3px 12px;
    align-items: center;
    padding: 8px 16px;
    border-bottom: 1px solid var(--el-border-color);
}
.who {
    grid-area: who;
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 0;
}
.source-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.soft {
    direction: ltr;
    white-space: nowrap;
}
.figure {
    grid-area: figure;
    justify-self: end;
    font-size: 13px;
    direction: ltr;
}
.tools {
    grid-area: tools;
    display: flex;
    gap: 2px;
    visibility: hidden;
}
.sources li:hover .tools,
.sources li:focus-within .tools {
    visibility: visible;
}
.state {
    grid-area: state;
}
.has {
    grid-area: has;
    justify-self: end;
    width: 96px;
    height: 4px;
    border-radius: 2px;
    background: linear-gradient(
        to right,
        var(--el-color-primary) calc(var(--share) * 100%),
        var(--el-fill-color-dark) calc(var(--share) * 100%)
    );
}
.facts,
.names,
.comments {
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 12px 16px 16px;
}
.facts dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 6px 16px;
    margin: 0;
}
.facts dt {
    color: var(--el-text-color-secondary);
}
.facts dd {
    margin: 0;
}
.hash {
    display: flex;
    gap: 6px;
}
.hash :deep(input) {
    font-family: ui-monospace, monospace;
    font-size: 13px;
}
.comments li {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px 0;
    border-bottom: 1px solid var(--el-border-color);
}
.said {
    display: flex;
    align-items: center;
    gap: 8px;
}
.comments .el-button {
    align-self: flex-start;
}
.el-dropdown-menu .heading {
    font-weight: 700;
}
.el-dropdown-menu .choice {
    position: relative;
    padding-inline-start: 34px;
}
.el-dropdown-menu .choice.current::before {
    content: '✓';
    position: absolute;
    inset-inline-start: 14px;
}
.el-dropdown-menu .danger:not(.is-disabled) {
    color: var(--el-color-danger);
}
@media (max-width: 860px) {
    .content {
        flex-direction: column;
        overflow: auto;
    }
    .head {
        width: auto;
        overflow: visible;
        border-inline-end: 0;
        border-bottom: 1px solid var(--el-border-color);
    }
    .tabbed {
        min-height: 240px;
    }
}
</style>
