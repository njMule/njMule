<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { TableInstance } from 'element-plus';
import { CUT_HINT, chooser, rememberedWidths } from '../../tables';
import {
    ElButton,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElInput,
    ElMessageBox,
    ElNotification,
    ElOption,
    ElProgress,
    ElSelect,
    ElSwitch,
    ElTable,
    ElTableColumn,
    ElTabPane,
    ElTabs,
} from 'element-plus';
import { computed, h, onUnmounted, ref, useTemplateRef, watch } from 'vue';
import { api } from '../../api';
import { copy } from '../../clipboard';
import AddLinksDialog from '../../components/AddLinksDialog.vue';
import CategoriesDialog from '../../components/CategoriesDialog.vue';
import ClientFilesDialog from '../../components/ClientFilesDialog.vue';
import CommentsDialog from '../../components/CommentsDialog.vue';
import NjIcon from '../../components/NjIcon.vue';
import PartBar from '../../components/PartBar.vue';
import { duration, percent, rate, size } from '../../format';
import { t } from '../../i18n';
import { guest } from '../../role';
import CountryFlag from '../../components/CountryFlag.vue';
import type { IconName } from '../../icons';
import { htmlLinks } from '../../links';
import { attempt, told } from '../../notify';
import { bySending, shown, type Shown } from '../../transfers';
import type { Category, Download, DownloadPriority, Parts, Source } from '../../types';

const props = defineProps<{ downloads: Download[]; categories: Category[]; sources: Source[] }>();
const emit = defineEmits<{ changed: [] }>();

type Chip = 'all' | 'downloading' | 'waiting' | 'noSources' | 'paused' | 'erroneous' | 'complete';

const chip = ref<Chip>('all');
const category = ref<number | null>(null);
const filter = ref('');
const chosen = ref<Download[]>([]);
const downloadTable = useTemplateRef<TableInstance>('downloadTable');
const widths = rememberedWidths(downloadTable, 'downloads');
const adding = ref(false);
const editing = ref(false);
const commenting = ref(false);
const browsing = ref(false);
const client = ref({ address: '', name: '' });

function browse(s: Source): void {
    client.value = { address: s.address, name: s.nick ?? s.address };
    browsing.value = true;
}

const STATES: Record<Shown, { key: string; tone: string; icon: IconName }> = {
    downloading: { key: 'IDS_DOWNLOADING', tone: 'busy', icon: 'DOWNLOAD' },
    waiting: { key: 'IDS_WAITING', tone: '', icon: 'CLOCK' },
    noSources: { key: 'nj.transfers.stateNoSources', tone: 'warn', icon: 'NO_SOURCES' },
    paused: { key: 'IDS_PAUSED', tone: '', icon: 'PAUSE' },
    stopped: { key: 'IDS_STOPPED', tone: '', icon: 'STOP' },
    noSpace: { key: 'IDS_INSUFFICIENT', tone: 'error', icon: 'ERROR' },
    error: { key: 'IDS_ERRORLIKE', tone: 'error', icon: 'ERROR' },
    complete: { key: 'IDS_COMPLETE', tone: 'ok', icon: 'CHECK' },
};

const CHIPS: { chip: Chip; key: string }[] = [
    { chip: 'all', key: 'IDS_ALL' },
    { chip: 'downloading', key: 'IDS_DOWNLOADING' },
    { chip: 'waiting', key: 'IDS_WAITING' },
    { chip: 'noSources', key: 'nj.transfers.stateNoSources' },
    { chip: 'paused', key: 'IDS_PAUSED' },
    { chip: 'erroneous', key: 'IDS_ERRORLIKE' },
    { chip: 'complete', key: 'IDS_COMPLETE' },
];

const PRIORITIES: { value: DownloadPriority; key: string }[] = [
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];

function chipOf(d: Download): Chip {
    const s = shown(d);
    return s === 'stopped' ? 'paused' : s === 'noSpace' || s === 'error' ? 'erroneous' : s;
}

const inCategory = computed(() =>
    props.downloads.filter((d) => category.value === null || d.category === category.value),
);

const counts = computed(() => {
    const all = new Map<Chip, number>([['all', inCategory.value.length]]);
    for (const d of inCategory.value) {
        all.set(chipOf(d), (all.get(chipOf(d)) ?? 0) + 1);
    }
    return all;
});

const visible = computed(() => {
    const words = filter.value
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return inCategory.value.filter(
        (d) =>
            (chip.value === 'all' || chipOf(d) === chip.value) && words.every((w) => d.name.toLowerCase().includes(w)),
    );
});
const choice = chooser(
    downloadTable,
    chosen,
    (d) => d.hash,
    () => visible.value,
);

const one = computed(() => (chosen.value.length === 1 ? chosen.value[0] : undefined));
// A completed download is not cancelled: its file stays, and clearing takes it off the list.
const cancellable = computed(() => chosen.value.filter((d) => d.status !== 'complete'));
const anyComplete = computed(() => props.downloads.some((d) => d.status === 'complete'));
const ofOne = computed(() => (one.value ? props.sources.filter((s) => s.file === one.value?.hash) : []));

function sourcesOf(d: Download): Source[] {
    return bySending(props.sources.filter((s) => s.file === d.hash));
}

// A double click opens a download onto its sources under it, or closes it; a complete one has none left.
function toggleSources(d: Download): void {
    if (d.status !== 'complete') {
        downloadTable.value?.toggleRowExpansion(d);
    }
}

// The parts of the download chosen, read again while it stays chosen.
const parts = ref<Parts | null>(null);
let partsTimer = 0;

async function readParts(hash: string): Promise<void> {
    try {
        parts.value = (await api.get<{ parts: Parts }>(`/downloads/${hash}/parts`)).parts;
    } catch {
        // Gone or not answering: the list tells.
    }
}

watch(
    () => one.value?.hash,
    (hash) => {
        window.clearInterval(partsTimer);
        parts.value = null;
        if (hash !== undefined) {
            void readParts(hash);
            partsTimer = window.setInterval(() => void readParts(hash), 3000);
        }
    },
);
onUnmounted(() => window.clearInterval(partsTimer));

async function setFlag(d: Download, path: 'preview' | 'autoswap', key: string, on: boolean): Promise<void> {
    await attempt(() => api.put(`/downloads/${d.hash}/${path}`, { [key]: on }));
    emit('changed');
}

async function swap(d: Download, direction: 'here' | 'away'): Promise<void> {
    await attempt(async () => {
        const { added } = await api.post<{ added: number }>(`/downloads/${d.hash}/swap`, { direction });
        told(t('nj.a4af.swapped', added));
    });
}

function state(d: Download) {
    return STATES[shown(d)];
}

function remaining(d: Download): string {
    return d.bytesPerSecond > 0 && d.status !== 'complete' ? duration((d.size - d.completed) / d.bytesPerSecond) : '—';
}

function priority(d: Download): string {
    return t(PRIORITIES.find((p) => p.value === d.priority)?.key ?? 'IDS_PRIOAUTO');
}

function categoryName(id: number): string {
    return props.categories.find((c) => c.id === id)?.name ?? '';
}

async function each(call: (d: Download) => Promise<unknown>): Promise<void> {
    await attempt(async () => {
        for (const d of chosen.value) {
            await call(d);
        }
    });
    emit('changed');
}

function act(what: 'pause' | 'resume' | 'stop'): void {
    void each((d) => api.post(`/downloads/${d.hash}/${what}`));
}

function setPriority(value: DownloadPriority): void {
    void each((d) => api.put(`/downloads/${d.hash}/priority`, { priority: value }));
}

function setCategory(id: number): void {
    void each((d) => api.put(`/downloads/${d.hash}/category`, { category: id }));
}

async function cancel(): Promise<void> {
    const gone = cancellable.value;
    const many = gone.length > 1;
    try {
        await ElMessageBox.confirm(
            t('nj.transfers.cancelText'),
            many ? t('nj.transfers.cancelTitleMany', gone.length) : t('nj.transfers.cancelTitleOne'),
            {
                type: 'warning',
                confirmButtonText: many ? t('nj.transfers.cancelMany') : t('nj.transfers.cancelOne'),
                cancelButtonText: t('nj.transfers.keepDownloading'),
                confirmButtonClass: 'el-button--danger',
            },
        );
    } catch {
        return;
    }
    await attempt(async () => {
        for (const d of gone) {
            await api.delete(`/downloads/${d.hash}`);
        }
    });
    emit('changed');
    // The engine keeps what they had downloaded for a few seconds: long enough to change one's mind.
    const note = ElNotification({
        message: h('span', [
            t('nj.transfers.cancelled', gone.length),
            ' ',
            h(
                ElButton,
                {
                    size: 'small',
                    onClick: async () => {
                        note.close();
                        await attempt(async () => {
                            for (const d of gone) {
                                await api.post(`/downloads/${d.hash}/restore`);
                            }
                        });
                        emit('changed');
                    },
                },
                () => t('IDS_UNDO'),
            ),
        ]),
        duration: 10000,
    });
}

async function clearCompleted(): Promise<void> {
    await attempt(() => api.delete('/downloads/completed'));
    emit('changed');
}

async function rename(): Promise<void> {
    const d = one.value;
    if (d === undefined) {
        return;
    }
    try {
        const { value } = await ElMessageBox.prompt(d.name, t('IDS_RENAME'), {
            inputValue: d.name,
            confirmButtonText: t('IDS_RENAME'),
            cancelButtonText: t('IDS_CANCEL'),
        });
        await attempt(() => api.put(`/downloads/${d.hash}/name`, { name: value }));
        emit('changed');
    } catch {
        // Not renamed.
    }
}

async function addSource(): Promise<void> {
    const d = one.value;
    if (d === undefined) {
        return;
    }
    try {
        const { value } = await ElMessageBox.prompt(
            `${t('nj.transfers.sourceFor', d.name)}\n${t('nj.transfers.sourceHint')}`,
            t('nj.transfers.addSourceTitle'),
            {
                inputPlaceholder: '192.0.2.10:4662',
                confirmButtonText: t('nj.transfers.addSource'),
                cancelButtonText: t('IDS_CANCEL'),
            },
        );
        if (await attempt(() => api.post(`/downloads/${d.hash}/sources`, { address: value.trim() }))) {
            told(t('nj.transfers.sourceAdded', value.trim()), 'success');
        }
    } catch {
        // Not added.
    }
}

function copyLinks(html: boolean): void {
    const links = chosen.value.map((d) => ({ link: d.link, name: d.name }));
    void copy(
        html ? htmlLinks(links) : links.map((l) => l.link).join('\n'),
        html ? t('IDS_DL_LINK2') : t('nj.copyLink'),
    );
}

function more(command: string): void {
    switch (command) {
        case 'rename':
            void rename();
            break;
        case 'source':
            void addSource();
            break;
        case 'html':
            copyLinks(true);
            break;
        case 'comments':
            commenting.value = true;
            break;
    }
}

const SOURCE_STATES: Record<Source['state'], string> = {
    waiting: 'IDS_WAITING',
    connecting: 'IDS_CONNECTING',
    asking: 'IDS_ASKING',
    queued: 'IDS_ONQUEUE',
    downloading: 'IDS_TRANSFERRING',
    a4af: 'IDS_ASKED4ANOTHERFILE',
};
</script>

<template>
    <div class="tools">
        <el-button v-if="!guest" @click="adding = true" type="primary"
            ><NjIcon name="ADD" />{{ t('nj.transfers.addLinks') }}</el-button
        >
        <span class="grow"></span>
        <el-select v-model="category" class="category" :placeholder="t('nj.transfers.allCategories')" clearable>
            <el-option :value="0" :label="t('IDS_CAT_UNASSIGN')" />
            <el-option v-for="c in categories" :key="c.id" :value="c.id" :label="c.name" />
        </el-select>
        <el-button v-if="!guest" :title="t('nj.categories.title')" @click="editing = true"
            ><NjIcon name="CATEGORY"
        /></el-button>
        <el-input v-model="filter" class="filter" clearable :placeholder="t('nj.transfers.filter')" />
    </div>
    <div class="chips" role="radiogroup">
        <button
            v-for="c in CHIPS"
            :key="c.chip"
            type="button"
            role="radio"
            :aria-checked="chip === c.chip"
            class="nj-chip"
            :class="{ busy: chip === c.chip }"
            @click="chip = c.chip"
        >
            {{ t(c.key) }} <b>{{ counts.get(c.chip) ?? 0 }}</b>
        </button>
    </div>
    <div v-if="guest" class="actions">
        <el-button :disabled="chosen.length === 0" @click="copyLinks(false)">
            <NjIcon name="COPY" />{{ t('nj.copyLink') }}
        </el-button>
    </div>
    <div v-else class="actions">
        <el-button :disabled="chosen.length === 0" @click="act('resume')">
            <NjIcon name="PLAY" />{{ t('IDS_DL_RESUME') }}
        </el-button>
        <el-button :disabled="chosen.length === 0" @click="act('pause')">
            <NjIcon name="PAUSE" />{{ t('IDS_DL_PAUSE') }}
        </el-button>
        <el-button :disabled="chosen.length === 0" @click="act('stop')">
            <NjIcon name="STOP" />{{ t('IDS_DL_STOP') }}
        </el-button>
        <el-dropdown :disabled="chosen.length === 0" trigger="click" @command="setPriority">
            <el-button :disabled="chosen.length === 0"><NjIcon name="PRIORITY" />{{ t('IDS_PRIORITY') }}</el-button>
            <template #dropdown>
                <el-dropdown-menu>
                    <el-dropdown-item v-for="p in PRIORITIES" :key="p.value" :command="p.value">
                        {{ t(p.key) }}
                    </el-dropdown-item>
                </el-dropdown-menu>
            </template>
        </el-dropdown>
        <el-dropdown :disabled="chosen.length === 0" trigger="click" @command="setCategory">
            <el-button :disabled="chosen.length === 0"><NjIcon name="CATEGORY" />{{ t('IDS_CAT') }}</el-button>
            <template #dropdown>
                <el-dropdown-menu>
                    <el-dropdown-item :command="0">{{ t('IDS_CAT_UNASSIGN') }}</el-dropdown-item>
                    <el-dropdown-item v-for="c in categories" :key="c.id" :command="c.id">{{
                        c.name
                    }}</el-dropdown-item>
                </el-dropdown-menu>
            </template>
        </el-dropdown>
        <el-button :disabled="chosen.length === 0" @click="copyLinks(false)">
            <NjIcon name="COPY" />{{ t('nj.copyLink') }}
        </el-button>
        <el-dropdown :disabled="chosen.length === 0" trigger="click" @command="more">
            <el-button :disabled="chosen.length === 0"><NjIcon name="MORE" />{{ t('IDS_MORE') }}</el-button>
            <template #dropdown>
                <el-dropdown-menu>
                    <el-dropdown-item command="rename" :disabled="!one">{{ t('IDS_RENAME') }}</el-dropdown-item>
                    <el-dropdown-item command="source" :disabled="!one || one.status === 'complete'">
                        {{ t('IDS_ADDSRCMANUALLY') }}
                    </el-dropdown-item>
                    <el-dropdown-item command="html">{{ t('IDS_DL_LINK2') }}</el-dropdown-item>
                    <el-dropdown-item command="comments" :disabled="!one">{{ t('IDS_CMT_ADD') }}</el-dropdown-item>
                </el-dropdown-menu>
            </template>
        </el-dropdown>
        <el-button :disabled="!anyComplete" @click="clearCompleted">
            <NjIcon name="CLEAR_ALL" />{{ t('IDS_DL_CLEAR') }}
        </el-button>
        <el-button :disabled="cancellable.length === 0" type="danger" plain @click="cancel">
            <NjIcon name="DELETE" />{{ t('nj.transfers.cancelOne') }}
        </el-button>
    </div>
    <el-table
        :tooltip-options="CUT_HINT"
        ref="downloadTable"
        border
        @header-dragend="widths"
        :data="visible"
        row-key="hash"
        :row-class-name="choice.rowClass"
        :empty-text="t('nj.transfers.none')"
        @row-click="choice.click"
        @row-dblclick="(row: Download) => toggleSources(row)"
        @selection-change="(rows: Download[]) => (chosen = rows)"
    >
        <el-table-column type="selection" width="40" reserve-selection :resizable="false" />
        <el-table-column type="expand" width="36" :resizable="false">
            <template #default="{ row }">
                <div class="sources-under">
                    <span v-if="sourcesOf(row as Download).length === 0" class="none">{{
                        t('nj.transfers.noSources')
                    }}</span>
                    <div v-for="s in sourcesOf(row as Download)" :key="s.address" class="source">
                        <span class="who">{{ s.nick ?? s.address }}</span>
                        <span class="figure">{{ s.received > 0 ? size(s.received) : '' }}</span>
                        <span class="figure">{{ s.parts }}/{{ s.partCount }}</span>
                        <span class="soft"
                            >{{ s.software }}<template v-if="s.queueRank > 0"> QR: {{ s.queueRank }}</template></span
                        >
                        <span class="state">{{ t(SOURCE_STATES[s.state]) }}</span>
                    </div>
                </div>
            </template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_FILENAME')" prop="name" min-width="260" sortable show-overflow-tooltip />
        <el-table-column :label="t('IDS_DL_SIZE')" prop="size" width="110" sortable align="right">
            <template #default="{ row }">{{ size(row.size) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_PROGRESS')" width="170">
            <template #default="{ row }">
                <el-progress
                    :percentage="row.size > 0 ? Math.floor((row.completed * 1000) / row.size) / 10 : 0"
                    :status="row.status === 'complete' ? 'success' : undefined"
                />
            </template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_SPEED')" prop="bytesPerSecond" width="110" sortable align="right">
            <template #default="{ row }">{{ row.bytesPerSecond > 0 ? rate(row.bytesPerSecond) : '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_SOURCES')" width="90" align="right">
            <template #default="{ row }">
                {{ row.status === 'complete' ? '—' : `${row.transferring}/${row.sources}` }}
            </template>
        </el-table-column>
        <el-table-column :label="t('nj.transfers.status')" width="190">
            <template #default="{ row }">
                <span class="nj-chip" :class="state(row as Download).tone">
                    <NjIcon :name="state(row as Download).icon" />{{ t(state(row as Download).key) }}
                </span>
            </template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_REMAINS')" width="110" align="right">
            <template #default="{ row }">{{ remaining(row as Download) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_PRIORITY')" width="100">
            <template #default="{ row }">{{ priority(row as Download) }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_CAT')" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ categoryName(row.category) }}</template>
        </el-table-column>
    </el-table>

    <section class="details" :aria-label="t('IDS_DETAILS')">
        <p v-if="chosen.length === 0" class="hint">{{ t('nj.details.none') }}</p>
        <p v-else-if="!one" class="hint">{{ t('nj.details.chosen', chosen.length) }}</p>
        <template v-else>
            <h2>{{ one.name }}</h2>
            <p class="facts">
                {{ t('nj.details.doneOf', size(one.completed), size(one.size)) }} ·
                {{ percent(one.completed, one.size) }} · {{ t(state(one).key) }}
            </p>
            <p v-if="shown(one) === 'noSources'" class="hint">{{ t('nj.details.noSourcesNote') }}</p>
            <p v-if="shown(one) === 'noSpace'" class="hint">{{ t('nj.details.diskFullNote') }}</p>
            <p v-if="shown(one) === 'error'" class="hint">{{ t('nj.details.errorNote') }}</p>
            <PartBar v-if="parts && one.status !== 'complete'" :parts="parts" />
            <div v-if="!guest && one.status !== 'complete'" class="flags">
                <label>
                    <el-switch
                        :model-value="one.previewFirst"
                        @change="(on) => setFlag(one!, 'preview', 'previewFirst', Boolean(on))"
                    />
                    {{ t('IDS_DOWNLOADMOVIECHUNKS') }}
                </label>
                <label>
                    <el-switch
                        :model-value="one.autoSwap"
                        @change="(on) => setFlag(one!, 'autoswap', 'autoSwap', Boolean(on))"
                    />
                    {{ t('nj.a4af.auto') }}
                </label>
                <el-button size="small" :disabled="one.status !== 'downloading'" @click="swap(one!, 'here')">
                    {{ t('nj.a4af.toThis') }}
                </el-button>
                <el-button size="small" @click="swap(one!, 'away')">{{ t('nj.a4af.toOther') }}</el-button>
            </div>
            <el-tabs>
                <el-tab-pane :label="`${t('IDS_DL_SOURCES')} (${ofOne.length})`">
                    <el-table
                        :tooltip-options="CUT_HINT"
                        :data="ofOne"
                        :empty-text="t('nj.transfers.noSources')"
                        size="small"
                    >
                        <el-table-column :label="t('IDS_QL_USERNAME')" min-width="160" show-overflow-tooltip>
                            <template #default="{ row }">{{ row.nick ?? row.address }}</template>
                        </el-table-column>
                        <el-table-column :label="t('nj.clients.country')" width="90">
                            <template #default="{ row }"><CountryFlag :code="row.country" /></template>
                        </el-table-column>
                        <el-table-column :label="t('IDS_CD_CSOFT')" prop="software" width="150" />
                        <el-table-column :label="t('nj.transfers.status')" width="190">
                            <template #default="{ row }">
                                {{ t(SOURCE_STATES[(row as Source).state]) }}
                                <span v-if="row.state === 'queued' && row.queueRank > 0">
                                    ({{ t('nj.transfers.queueRank') }} {{ row.queueRank }})
                                </span>
                            </template>
                        </el-table-column>
                        <el-table-column :label="t('IDS_DL_TRANSF')" width="110" align="right">
                            <template #default="{ row }">{{ size(row.received) }}</template>
                        </el-table-column>
                        <el-table-column :label="t('IDS_DL_PROGRESS')" width="120" align="right">
                            <template #default="{ row }">{{ row.parts }}/{{ row.partCount }}</template>
                        </el-table-column>
                        <el-table-column width="170" align="right">
                            <template #default="{ row }">
                                <el-button size="small" @click="browse(row as Source)">{{
                                    t('IDS_VIEWFILES')
                                }}</el-button>
                            </template>
                        </el-table-column>
                    </el-table>
                </el-tab-pane>
            </el-tabs>
        </template>
    </section>

    <ClientFilesDialog v-model="browsing" :address="client.address" :name="client.name" />
    <CommentsDialog v-if="one" v-model="commenting" :hash="one.hash" :name="one.name" :download="true" />
    <CategoriesDialog v-model="editing" :categories="categories" @changed="emit('changed')" />
    <AddLinksDialog v-model="adding" :categories="categories" :downloads="downloads" @added="emit('changed')" />
</template>

<style scoped>
.tools,
.actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
}
.tools .el-button + .el-button,
.actions .el-button + .el-button {
    margin-left: 0;
}
.nj-icon {
    width: 16px;
    height: 16px;
}
.el-button .nj-icon {
    margin-inline-end: 6px;
}
.grow {
    flex: 1;
}
.filter {
    width: 240px;
}
.category {
    width: 200px;
}
.chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-bottom: 12px;
}
.chips button {
    border: 0;
    cursor: pointer;
    font: inherit;
    font-size: 13px;
}
.details {
    margin-top: 18px;
    padding: 14px 16px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.details h2 {
    margin: 0 0 4px;
    font-size: 16px;
    word-break: break-all;
}
.flags {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 16px;
    margin: 10px 0;
}
.flags label {
    display: flex;
    align-items: center;
    gap: 6px;
}
.facts,
.hint {
    margin: 0 0 8px;
    color: var(--el-text-color-secondary);
}
.sources-under {
    padding: 2px 8px 6px 44px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.sources-under .source {
    display: grid;
    grid-template-columns: minmax(160px, 2fr) 90px 80px minmax(140px, 1.5fr) minmax(140px, 1.5fr);
    gap: 12px;
    padding: 2px 0;
}
.sources-under .who {
    color: var(--el-text-color-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.sources-under .figure {
    text-align: end;
    direction: ltr;
}
</style>
