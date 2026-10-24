<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInput, ElMessage, ElMessageBox, ElProgress, ElTable, ElTableColumn } from 'element-plus';
import { computed, ref } from 'vue';
import { api, ApiError } from '../api';
import NjIcon from '../components/NjIcon.vue';
import { usePoll } from '../engine';
import { duration, rate, size } from '../format';
import { t } from '../i18n';
import type { Download } from '../types';
import { shown, type Shown } from '../transfers';

const { data: downloads, refresh } = usePoll(() => api.get<Download[]>('/downloads'), 1000, [] as Download[]);
const filter = ref('');
const chosen = ref<Download[]>([]);

const visible = computed(() => {
    const words = filter.value
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return downloads.value.filter((d) => words.every((w) => d.name.toLowerCase().includes(w)));
});

const STATES: Record<
    Shown,
    { key: string; tone: string; icon: 'DOWNLOAD' | 'CLOCK' | 'NO_SOURCES' | 'PAUSE' | 'STOP' | 'ERROR' | 'CHECK' }
> = {
    downloading: { key: 'IDS_DOWNLOADING', tone: 'busy', icon: 'DOWNLOAD' },
    waiting: { key: 'IDS_WAITING', tone: '', icon: 'CLOCK' },
    noSources: { key: 'nj.transfers.stateNoSources', tone: 'warn', icon: 'NO_SOURCES' },
    paused: { key: 'IDS_PAUSED', tone: '', icon: 'PAUSE' },
    stopped: { key: 'IDS_STOPPED', tone: '', icon: 'STOP' },
    noSpace: { key: 'IDS_INSUFFICIENT', tone: 'error', icon: 'ERROR' },
    error: { key: 'IDS_ERRORLIKE', tone: 'error', icon: 'ERROR' },
    complete: { key: 'IDS_COMPLETE', tone: 'ok', icon: 'CHECK' },
};

function state(d: Download) {
    return STATES[shown(d)];
}

function remaining(d: Download): string {
    return d.bytesPerSecond > 0 && d.status !== 'complete' ? duration((d.size - d.completed) / d.bytesPerSecond) : '—';
}

async function act(what: 'pause' | 'resume' | 'stop'): Promise<void> {
    await each(chosen.value, (d) => api.post(`/downloads/${d.hash}/${what}`));
}

async function cancel(): Promise<void> {
    const many = chosen.value.length > 1;
    try {
        await ElMessageBox.confirm(
            t('nj.transfers.cancelText'),
            many ? t('nj.transfers.cancelTitleMany', chosen.value.length) : t('nj.transfers.cancelTitleOne'),
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
    const gone = chosen.value;
    await each(gone, (d) => api.delete(`/downloads/${d.hash}`));
    ElMessage({ message: t('nj.transfers.cancelled', gone.length), type: 'info' });
}

async function each(items: Download[], call: (d: Download) => Promise<unknown>): Promise<void> {
    try {
        for (const d of items) {
            await call(d);
        }
    } catch (e) {
        ElMessage({
            message: e instanceof ApiError ? e.message : String(e),
            type: 'error',
            duration: 0,
            showClose: true,
        });
    }
    await refresh();
}
</script>

<template>
    <div class="tools">
        <el-button :disabled="chosen.length === 0" @click="act('resume')">
            <NjIcon name="PLAY" />{{ t('IDS_DL_RESUME') }}
        </el-button>
        <el-button :disabled="chosen.length === 0" @click="act('pause')">
            <NjIcon name="PAUSE" />{{ t('IDS_DL_PAUSE') }}
        </el-button>
        <el-button :disabled="chosen.length === 0" @click="act('stop')">
            <NjIcon name="STOP" />{{ t('IDS_DL_STOP') }}
        </el-button>
        <el-button :disabled="chosen.length === 0" type="danger" plain @click="cancel">
            <NjIcon name="DELETE" />{{ t('nj.transfers.cancelOne') }}
        </el-button>
        <span class="grow"></span>
        <el-input v-model="filter" class="filter" clearable :placeholder="t('nj.transfers.filter')" />
    </div>
    <el-table
        :data="visible"
        row-key="hash"
        :empty-text="t('nj.transfers.none')"
        @selection-change="(rows: Download[]) => (chosen = rows)"
    >
        <el-table-column type="selection" width="40" />
        <el-table-column :label="t('IDS_DL_FILENAME')" prop="name" min-width="280" sortable show-overflow-tooltip />
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
        <el-table-column :label="t('IDS_DL_SOURCES')" width="100" align="right">
            <template #default="{ row }">
                {{ row.status === 'complete' ? '—' : `${row.transferring}/${row.sources}` }}
            </template>
        </el-table-column>
        <el-table-column :label="t('nj.transfers.status')" width="190">
            <template #default="{ row }">
                <span class="nj-chip" :class="state(row as Download).tone"
                    ><NjIcon :name="state(row as Download).icon" />{{ t(state(row as Download).key) }}</span
                >
            </template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_REMAINS')" width="120" align="right">
            <template #default="{ row }">{{ remaining(row as Download) }}</template>
        </el-table-column>
    </el-table>
</template>

<style scoped>
.tools {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
}
.tools .el-button + .el-button {
    margin-left: 0;
}
.tools .nj-icon {
    width: 16px;
    height: 16px;
    margin-inline-end: 6px;
}
.grow {
    flex: 1;
}
.filter {
    width: 260px;
}
</style>
