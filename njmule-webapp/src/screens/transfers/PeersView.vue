<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElTable, ElTableColumn } from 'element-plus';
import { duration, rate, size } from '../../format';
import { t } from '../../i18n';
import type { Upload } from '../../types';

defineProps<{ peers: Upload[] }>();
</script>

<template>
    <el-table :data="peers" :empty-text="t('nj.transfers.noUploads')">
        <el-table-column :label="t('IDS_QL_USERNAME')" min-width="160" sortable show-overflow-tooltip>
            <template #default="{ row }">{{ row.nick ?? row.address }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_CD_CSOFT')" prop="software" width="160" show-overflow-tooltip />
        <el-table-column :label="t('IDS_DL_FILENAME')" prop="file" min-width="240" sortable show-overflow-tooltip />
        <el-table-column :label="t('IDS_DL_SPEED')" prop="bytesPerSecond" width="110" sortable align="right">
            <template #default="{ row }">{{ row.bytesPerSecond > 0 ? rate(row.bytesPerSecond) : '—' }}</template>
        </el-table-column>
        <el-table-column :label="t('IDS_DL_TRANSF')" prop="transferred" width="120" sortable align="right">
            <template #default="{ row }">{{ size(row.transferred) }}</template>
        </el-table-column>
        <el-table-column :label="t('nj.transfers.waited')" prop="waitingSeconds" width="120" sortable align="right">
            <template #default="{ row }">{{ duration(row.waitingSeconds) }}</template>
        </el-table-column>
    </el-table>
</template>
