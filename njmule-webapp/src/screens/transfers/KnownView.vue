<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElTable, ElTableColumn } from 'element-plus';
import CountryFlag from '../../components/CountryFlag.vue';
import { size } from '../../format';
import { language, t } from '../../i18n';
import type { KnownClient } from '../../types';

// The clients met in the last 90 days, never their addresses; the totals are those of the credits.
defineProps<{ clients: KnownClient[] }>();

// eMule's texts as column titles, without the colon they end with.
function label(key: string): string {
    return t(key).replace(/\s*:\s*$/, '');
}

function when(seconds: number): string {
    return new Date(seconds * 1000).toLocaleString(language.value);
}
</script>

<template>
    <el-table :data="clients" row-key="userHash" :empty-text="t('nj.clients.none')">
        <el-table-column :label="t('IDS_QL_USERNAME')" prop="nick" min-width="160" sortable show-overflow-tooltip>
            <template #default="{ row }">{{ row.nick || row.userHash }}</template>
        </el-table-column>
        <el-table-column :label="t('nj.clients.country')" prop="country" width="90" sortable>
            <template #default="{ row }"><CountryFlag :code="row.country" /></template>
        </el-table-column>
        <el-table-column :label="t('IDS_CD_CSOFT')" prop="software" width="160" show-overflow-tooltip />
        <el-table-column :label="t('nj.clients.firstSeen')" prop="firstSeen" width="170" sortable>
            <template #default="{ row }">{{ when(row.firstSeen) }}</template>
        </el-table-column>
        <el-table-column :label="label('IDS_LASTSEEN')" prop="lastSeen" width="170" sortable>
            <template #default="{ row }">{{ when(row.lastSeen) }}</template>
        </el-table-column>
        <el-table-column :label="label('IDS_CD_TUP')" prop="uploaded" width="120" sortable align="right">
            <template #default="{ row }">{{ size(row.uploaded) }}</template>
        </el-table-column>
        <el-table-column :label="label('IDS_CD_TDOWN')" prop="downloaded" width="120" sortable align="right">
            <template #default="{ row }">{{ size(row.downloaded) }}</template>
        </el-table-column>
    </el-table>
</template>
