<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { TableInstance } from 'element-plus';
import { CUT_HINT, chooser } from '../tables';
import { ElButton, ElDialog, ElTable, ElTableColumn } from 'element-plus';
import { ref, useTemplateRef, watch } from 'vue';
import { api } from '../api';
import { size } from '../format';
import { t } from '../i18n';
import { attempt, told } from '../notify';

interface Shared {
    hash: string;
    name: string;
    size: number;
    type?: string;
    link: string;
}

const props = defineProps<{ address: string; name: string }>();
const open = defineModel<boolean>({ required: true });

const files = ref<Shared[]>([]);
const chosen = ref<Shared[]>([]);
const fileTable = useTemplateRef<TableInstance>('fileTable');
const choice = chooser(
    fileTable,
    chosen,
    (f) => f.hash,
    () => files.value,
);
const said = ref('');

watch(open, async (now) => {
    if (!now) {
        return;
    }
    files.value = [];
    said.value = t('nj.browse.asking');
    try {
        files.value = await api.post<Shared[]>(`/clients/${encodeURIComponent(props.address)}/files`);
        said.value = files.value.length > 0 ? t('nj.browse.count', files.value.length) : t('nj.browse.none');
    } catch (e) {
        said.value = t('nj.browse.failed', e instanceof Error ? e.message : String(e));
    }
});

async function download(): Promise<void> {
    if (await attempt(() => api.post('/downloads', { links: chosen.value.map((f) => f.link) }))) {
        chosen.value.forEach((f) => told(t('nj.transfers.added', f.name), 'success'));
    }
}
</script>

<template>
    <el-dialog v-model="open" :title="`${t('IDS_VIEWFILES')}: ${name}`" width="760px">
        <p class="said">{{ said }}</p>
        <el-table
            :tooltip-options="CUT_HINT"
            ref="fileTable"
            :data="files"
            row-key="hash"
            height="360"
            :row-class-name="choice.rowClass"
            @row-click="choice.click"
            @selection-change="(rows: Shared[]) => (chosen = rows)"
        >
            <el-table-column type="selection" width="40" reserve-selection />
            <el-table-column :label="t('IDS_DL_FILENAME')" prop="name" min-width="300" sortable show-overflow-tooltip />
            <el-table-column :label="t('IDS_DL_SIZE')" prop="size" width="110" sortable align="right">
                <template #default="{ row }">{{ size(row.size) }}</template>
            </el-table-column>
            <el-table-column :label="t('IDS_TYPE')" prop="type" width="100" />
        </el-table>
        <template #footer>
            <el-button @click="open = false">{{ t('IDS_FD_CLOSE') }}</el-button>
            <el-button type="primary" :disabled="chosen.length === 0" @click="download">
                {{ t('IDS_DOWNLOAD') }}
            </el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.said {
    margin-top: 0;
    color: var(--el-text-color-secondary);
}
</style>
