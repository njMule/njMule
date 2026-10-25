<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    ElButton,
    ElInput,
    ElInputNumber,
    ElPopover,
    ElSegmented,
    ElTable,
    ElTableColumn,
    ElTabPane,
    ElTabs,
} from 'element-plus';
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '../api';
import { copy } from '../clipboard';
import NjIcon from '../components/NjIcon.vue';
import { status, usePoll } from '../engine';
import { size } from '../format';
import { t } from '../i18n';
import { htmlLinks } from '../links';
import { attempt, told } from '../notify';
import { guest } from '../role';
import {
    completeness,
    expression,
    link,
    playingTime,
    searches,
    words,
    type FileType,
    type Filters,
    type Result,
    type Search,
} from '../search';
import type { Download } from '../types';

type Scope = 'automatic' | 'server' | 'global' | 'kad';

const query = ref('');
const scope = ref<Scope>('automatic');
const filters = ref<Filters>({ type: '', extension: '' });
const active = ref(searches.value.at(-1)?.id.toString() ?? '');
const filter = ref('');
const chosen = ref<Result[]>([]);
const downloads = usePoll(() => api.get<Download[]>('/downloads'), 5000, [] as Download[]);

const SCOPES = computed(() => [
    { value: 'automatic', label: t('IDS_AUTOMATIC') },
    { value: 'server', label: t('IDS_SERVER') },
    { value: 'global', label: t('IDS_GLOBALSEARCH') },
    { value: 'kad', label: t('IDS_KADEMLIA') },
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

const filterCount = computed(
    () =>
        [
            filters.value.type !== '',
            filters.value.minMb,
            filters.value.maxMb,
            filters.value.availability,
            filters.value.complete,
            filters.value.extension.trim() !== '' ? 1 : undefined,
        ].filter((v) => v !== undefined && v !== false).length,
);

const shown = computed(() => searches.value.find((s) => s.id.toString() === active.value));
const downloading = computed(() => new Set(downloads.data.value.map((d) => d.hash.toUpperCase())));
const visible = computed(() => {
    const results = shown.value?.results ?? [];
    const all = filter.value
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    const wanted = all.filter((w) => !w.startsWith('-'));
    const unwanted = all.filter((w) => w.startsWith('-') && w.length > 1).map((w) => w.substring(1));
    return results.filter((r) => {
        const name = r.name.toLowerCase();
        return wanted.every((w) => name.includes(w)) && !unwanted.some((w) => name.includes(w));
    });
});

function resetFilters(): void {
    filters.value = { type: '', extension: '' };
}

// The searches started through the web interface, here or in another browser: a guest sees those only.
async function takeTheServersSearches(): Promise<void> {
    try {
        const listed = await api.get<{ id: number; query: string; running: boolean }[]>('/searches');
        const known = new Set(searches.value.map((s) => s.id));
        const added = listed
            .filter((l) => !known.has(l.id) && l.query !== '')
            .map((l) => ({ id: l.id, expression: l.query, results: [] as Result[], running: l.running }));
        if (added.length > 0) {
            searches.value = [...searches.value, ...added];
            active.value ||= added.at(-1)!.id.toString();
        }
    } catch {
        // The page's own searches stay.
    }
}

async function start(): Promise<void> {
    if (guest || query.value.trim() === '') {
        return;
    }
    const e = expression(query.value, filters.value);
    await attempt(async () => {
        const { id } = await api.post<{ id: number }>('/searches', { query: e, scope: scope.value });
        searches.value = [...searches.value, { id, expression: e, results: [] }];
        active.value = id.toString();
    });
}

async function close(name: string | number): Promise<void> {
    const id = Number(name);
    searches.value = searches.value.filter((s) => s.id !== id);
    if (active.value === String(name)) {
        active.value = searches.value.at(-1)?.id.toString() ?? '';
    }
    try {
        await api.delete(`/searches/${id}`);
    } catch {
        // Gone already: the engine keeps the last searches only.
    }
}

async function more(): Promise<void> {
    if (shown.value) {
        const id = shown.value.id;
        await attempt(() => api.post(`/searches/${id}/more`));
    }
}

// A guest's double click does nothing.
function downloadOnDoubleClick(row: Result): void {
    if (!guest) {
        void download([row]);
    }
}

async function download(results: Result[]): Promise<void> {
    const s = shown.value;
    if (s === undefined) {
        return;
    }
    await attempt(async () => {
        for (const r of results) {
            await api.post(`/searches/${s.id}/download`, { hash: r.hash });
            told(t('nj.transfers.added', r.name), 'success');
        }
    });
    await downloads.refresh();
}

async function markSpam(spam: boolean): Promise<void> {
    const s = shown.value;
    if (s === undefined || chosen.value.length === 0) {
        return;
    }
    const hashes = chosen.value.map((r) => r.hash);
    await attempt(() => api.post(`/searches/${s.id}/spam`, { hashes, spam }));
    await refresh(s);
}

const KNOWN: Record<NonNullable<Result['known']>, { key: string; tone: string }> = {
    downloading: { key: 'IDS_DOWNLOADING', tone: 'busy' },
    shared: { key: 'IDS_SHARED', tone: 'ok' },
    downloaded: { key: 'IDS_DOWNLOADED', tone: 'ok' },
    cancelled: { key: 'IDS_CANCELLED', tone: '' },
};

// The media columns show when a result is a piece of music or a film, as the desktop app's do.
const hasMedia = computed(() => (shown.value?.results ?? []).some((r) => r.media !== undefined));

// The files related to those chosen, which only some servers find.
async function searchRelated(): Promise<void> {
    const hashes = [...new Set(chosen.value.map((r) => r.hash))];
    if (hashes.length === 0) {
        return;
    }
    const e = `related::${hashes.join('::')}`;
    await attempt(async () => {
        const { id } = await api.post<{ id: number }>('/searches', { query: e, scope: 'server' });
        searches.value = [...searches.value, { id, expression: e, results: [] }];
        active.value = id.toString();
    });
}

function copyLinks(html: boolean): void {
    const links = chosen.value.map((r) => ({ link: link(r), name: r.name }));
    void copy(
        html ? htmlLinks(links) : links.map((l) => l.link).join('\n'),
        html ? t('IDS_DL_LINK2') : t('nj.copyLink'),
    );
}

async function refresh(s: Search): Promise<void> {
    try {
        const answer = await api.get<{ running: boolean; results: Result[] }>(`/searches/${s.id}`);
        s.results = answer.results;
        s.running = answer.running;
    } catch {
        // A search the engine forgot keeps the results it showed.
    }
}

// A search asked for elsewhere, such as from the palette.
const route = useRoute();
const router = useRouter();
watch(
    () => route.query.q,
    (q) => {
        if (typeof q === 'string' && q.trim() !== '') {
            query.value = q;
            void start();
            // Once: going back to the page does not search again.
            void router.replace('/search');
        }
    },
    { immediate: true },
);

let timer = 0;
onMounted(() => {
    void takeTheServersSearches();
    timer = window.setInterval(() => {
        if (shown.value) {
            void refresh(shown.value);
        }
    }, 2000);
});
onUnmounted(() => window.clearInterval(timer));
</script>

<template>
    <form v-if="!guest" class="tools" @submit.prevent="start">
        <el-input v-model="query" class="query" :placeholder="t('IDS_SEARCH_TEXT')" clearable />
        <el-segmented v-model="scope" :options="SCOPES" />
        <el-popover trigger="click" :width="360">
            <template #reference>
                <el-button><NjIcon name="FILTER" />{{ t('nj.search.filters') }} ({{ filterCount }})</el-button>
            </template>
            <div class="filters">
                <div class="types">
                    <button
                        v-for="ty in TYPES"
                        :key="ty.value"
                        type="button"
                        class="nj-chip"
                        :class="{ busy: filters.type === ty.value }"
                        @click="filters.type = ty.value"
                    >
                        {{ t(ty.key) }}
                    </button>
                </div>
                <label>
                    <span>{{ t('IDS_SEARCHMINSIZE') }}</span>
                    <el-input-number v-model="filters.minMb" :min="0" :max="262144" controls-position="right" />
                </label>
                <label>
                    <span>{{ t('IDS_SEARCHMAXSIZE') }}</span>
                    <el-input-number v-model="filters.maxMb" :min="0" :max="262144" controls-position="right" />
                </label>
                <label>
                    <span>{{ t('IDS_SEARCHAVAIL') }}</span>
                    <el-input-number v-model="filters.availability" :min="1" :max="1000000" controls-position="right" />
                </label>
                <label>
                    <span>{{ t('IDS_COMPLSOURCES') }}</span>
                    <el-input-number v-model="filters.complete" :min="1" :max="1000000" controls-position="right" />
                </label>
                <label>
                    <span>{{ t('IDS_SEARCHEXTENTION') }}</span>
                    <el-input v-model="filters.extension" maxlength="10" class="ext" />
                </label>
                <p class="hint">{{ t('nj.search.filtersNote') }}</p>
                <el-button size="small" @click="resetFilters">{{ t('IDS_PW_RESET') }}</el-button>
            </div>
        </el-popover>
        <el-button type="primary" native-type="submit"><NjIcon name="SEARCH" />{{ t('nj.search.start') }}</el-button>
    </form>

    <div v-if="searches.length === 0" class="empty">
        <h2>{{ t('nj.search.emptyTitle') }}</h2>
        <p v-if="!guest">{{ t('nj.search.emptyText') }}</p>
        <ul v-if="!guest">
            <li>{{ t('nj.search.tipExact') }}: <code>"night of the living dead"</code></li>
            <li>{{ t('nj.search.tipWithout') }}: <code>debian -netinst</code></li>
            <li>{{ t('nj.search.tipLink') }}: <code>ed2k://|file|…|/</code></li>
        </ul>
    </div>

    <template v-else>
        <el-tabs v-model="active" type="card" :closable="!guest" @tab-remove="close">
            <el-tab-pane
                v-for="s in searches"
                :key="s.id"
                :name="s.id.toString()"
                :label="`${words(s.expression)} (${s.results.length})${s.running ? ' …' : ''}`"
            />
        </el-tabs>
        <div class="actions">
            <el-button v-if="!guest" :disabled="chosen.length === 0" type="primary" @click="download(chosen)">
                <NjIcon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
            </el-button>
            <el-button :disabled="chosen.length === 0" @click="copyLinks(false)">
                <NjIcon name="COPY" />{{ t('nj.copyLink') }}
            </el-button>
            <el-button :disabled="chosen.length === 0" @click="copyLinks(true)">{{ t('IDS_DL_LINK2') }}</el-button>
            <template v-if="!guest">
                <el-button :disabled="chosen.length === 0 || !status?.relatedSearch" @click="searchRelated">
                    {{ t('IDS_SEARCHRELATED') }}
                </el-button>
                <el-button :disabled="chosen.length === 0" @click="markSpam(true)">{{ t('IDS_MARKSPAM') }}</el-button>
                <el-button :disabled="chosen.length === 0" @click="markSpam(false)">{{
                    t('IDS_MARKNOTSPAM')
                }}</el-button>
                <el-button @click="more">{{ t('nj.search.more') }}</el-button>
            </template>
            <span class="grow"></span>
            <span class="count">{{ t('nj.search.count', `${visible.length}/${shown?.results.length ?? 0}`) }}</span>
            <el-input v-model="filter" class="filter" clearable :placeholder="t('IDS_DL_FILENAME')" />
        </div>
        <el-table
            :data="visible"
            row-key="hash"
            :empty-text="t('nj.search.waiting')"
            :default-sort="{ prop: 'sources', order: 'descending' }"
            @selection-change="(rows: Result[]) => (chosen = rows)"
            @row-dblclick="(row: Result) => downloadOnDoubleClick(row)"
        >
            <el-table-column type="selection" width="40" />
            <el-table-column :label="t('IDS_DL_FILENAME')" prop="name" min-width="300" sortable show-overflow-tooltip>
                <template #default="{ row }">
                    <span :class="{ mine: downloading.has(row.hash.toUpperCase()), spam: row.spam }">{{
                        row.name
                    }}</span>
                </template>
            </el-table-column>
            <el-table-column :label="t('IDS_DL_SIZE')" prop="size" width="110" sortable align="right">
                <template #default="{ row }">{{ size(row.size) }}</template>
            </el-table-column>
            <el-table-column :label="t('IDS_SEARCHAVAIL')" prop="sources" width="120" sortable align="right" />
            <el-table-column :label="t('IDS_COMPLETE')" width="100" align="right">
                <template #default="{ row }">{{ completeness(row as Result) }}</template>
            </el-table-column>
            <el-table-column :label="t('IDS_TYPE')" prop="type" width="110" sortable />
            <el-table-column :label="t('nj.search.known')" width="150">
                <template #default="{ row }">
                    <span v-if="row.spam" class="nj-chip error">{{ t('IDS_SPAM') }}</span>
                    <span v-else-if="row.known" class="nj-chip" :class="KNOWN[(row as Result).known!].tone">
                        {{ t(KNOWN[(row as Result).known!].key) }}
                    </span>
                </template>
            </el-table-column>
            <template v-if="hasMedia">
                <el-table-column :label="t('IDS_ARTIST')" min-width="120" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.media?.artist }}</template>
                </el-table-column>
                <el-table-column :label="t('IDS_ALBUM')" min-width="120" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.media?.album }}</template>
                </el-table-column>
                <el-table-column :label="t('IDS_TITLE')" min-width="120" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.media?.title }}</template>
                </el-table-column>
                <el-table-column :label="t('IDS_LENGTH')" width="90" align="right">
                    <template #default="{ row }">{{ row.media?.length ? playingTime(row.media.length) : '' }}</template>
                </el-table-column>
                <el-table-column :label="t('IDS_BITRATE')" width="110" align="right">
                    <template #default="{ row }">
                        {{ row.media?.bitrate ? `${row.media.bitrate} ${t('IDS_KBITSSEC')}` : '' }}
                    </template>
                </el-table-column>
                <el-table-column :label="t('IDS_CODEC')" width="90">
                    <template #default="{ row }">{{ row.media?.codec }}</template>
                </el-table-column>
            </template>
        </el-table>
    </template>
</template>

<style scoped>
.tools,
.actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
}
.tools .el-button + .el-button,
.actions .el-button + .el-button {
    margin-left: 0;
}
.el-button .nj-icon {
    width: 16px;
    height: 16px;
    margin-inline-end: 6px;
}
.query {
    flex: 1 1 280px;
    max-width: 520px;
}
.filters label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-top: 8px;
}
.types {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}
.types button {
    border: 0;
    cursor: pointer;
    font: inherit;
    font-size: 13px;
}
.ext {
    width: 150px;
}
.hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.empty {
    max-width: 640px;
    padding: 24px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.empty h2 {
    margin-top: 0;
}
.grow {
    flex: 1;
}
.count {
    color: var(--el-text-color-secondary);
}
.filter {
    width: 220px;
}
.mine {
    font-weight: 600;
}
.spam {
    color: var(--el-text-color-secondary);
}
</style>
