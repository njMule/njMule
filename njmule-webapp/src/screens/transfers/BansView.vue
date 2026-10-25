<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { rememberedWidths } from '../../tables';
import { ElButton, ElTable, ElTableColumn, type TableInstance } from 'element-plus';
import { useTemplateRef } from 'vue';
import { api } from '../../api';
import { duration } from '../../format';
import { t } from '../../i18n';
import { attempt } from '../../notify';
import type { Ban } from '../../types';

defineProps<{ bans: Ban[] }>();
const banTable = useTemplateRef<TableInstance>('banTable');
const widths = rememberedWidths(banTable, 'bans');
const emit = defineEmits<{ changed: [] }>();

async function unban(address: string): Promise<void> {
    await attempt(() => api.delete(`/bans/${encodeURIComponent(address)}`));
    emit('changed');
}
</script>

<template>
    <el-table ref="banTable" border @header-dragend="widths" :data="bans" :empty-text="t('nj.transfers.noBans')">
        <el-table-column :label="t('IDS_IP')" prop="address" min-width="200" sortable />
        <el-table-column :label="t('nj.transfers.banLeft')" prop="secondsLeft" width="140" sortable align="right">
            <template #default="{ row }">{{ duration(row.secondsLeft) }}</template>
        </el-table-column>
        <el-table-column width="140" align="right">
            <template #default="{ row }">
                <el-button size="small" @click="unban(row.address)">{{ t('IDS_UNBAN') }}</el-button>
            </template>
        </el-table-column>
    </el-table>
</template>
