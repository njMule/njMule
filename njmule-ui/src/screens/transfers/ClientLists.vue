<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { computed, ref } from 'vue';
import { duration, rate, size } from '../../format';
import { t } from '../../i18n';
import { bans, known, uploads, type Ban, type KnownClient, type Upload } from '../../model/engine';
import DataTable, { type ColumnMeta } from '../../ui/DataTable.vue';

// The clients we upload to, those waiting for a slot, the banned and the clients met: one list at a time.

const props = defineProps<{ shelf: 'uploads' | 'queue' | 'banned' | 'known' }>();
const selected = ref<string[]>([]);
const numeric: ColumnMeta = { numeric: true };

const uploadColumns: ColumnDef<any, Upload, any>[] = [
    { id: 'client', header: t('IDS_QL_USERNAME'), accessorFn: (u: Upload) => u.nick ?? u.address, size: 180 },
    { id: 'file', header: t('IDS_DL_FILENAME'), accessorFn: (u: Upload) => u.file, size: 240 },
    { id: 'software', header: t('IDS_CD_CSOFT'), accessorFn: (u: Upload) => u.software, size: 140 },
    {
        id: 'speed',
        header: t('IDS_DL_SPEED'),
        accessorFn: (u: Upload) => u.bytesPerSecond,
        size: 90,
        meta: numeric,
        cell: ({ getValue }: any) => rate(getValue()),
    },
    {
        id: 'transferred',
        header: t('IDS_DL_TRANSF'),
        accessorFn: (u: Upload) => u.transferred,
        size: 100,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'waited',
        header: t('IDS_WAITED'),
        accessorFn: (u: Upload) => u.waitingSeconds,
        size: 100,
        meta: numeric,
        cell: ({ getValue }: any) => duration(getValue()),
    },
];

const banColumns: ColumnDef<any, Ban, any>[] = [
    { id: 'address', header: t('IDS_IP'), accessorFn: (b: Ban) => b.address, size: 220 },
    {
        id: 'left',
        header: t('IDS_DL_REMAINS'),
        accessorFn: (b: Ban) => b.secondsLeft,
        size: 120,
        meta: numeric,
        cell: ({ getValue }: any) => duration(getValue()),
    },
];

const knownColumns: ColumnDef<any, KnownClient, any>[] = [
    { id: 'client', header: t('IDS_QL_USERNAME'), accessorFn: (k: KnownClient) => k.nick, size: 200 },
    { id: 'software', header: t('IDS_CD_CSOFT'), accessorFn: (k: KnownClient) => k.software, size: 160 },
    {
        id: 'uploaded',
        header: t('IDS_STATS_SESSIONUL'),
        accessorFn: (k: KnownClient) => k.uploaded,
        size: 110,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'downloaded',
        header: t('IDS_STATS_SESSIONDL'),
        accessorFn: (k: KnownClient) => k.downloaded,
        size: 110,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
];

const uploading = computed(() => uploads.value.filter((u) => u.state === 'uploading'));
const queued = computed(() => uploads.value.filter((u) => u.state === 'queued'));
const label = computed(
    () =>
        ({
            uploads: t('IDS_TW_UPLOADS'),
            queue: t('IDS_ONQUEUE'),
            banned: t('IDS_BANNED'),
            known: t('IDS_CLIENTLIST'),
        })[props.shelf],
);
</script>

<template>
    <DataTable
        v-if="shelf === 'uploads' || shelf === 'queue'"
        :key="shelf"
        v-model:selected="selected"
        :name="shelf"
        :rows="shelf === 'uploads' ? uploading : queued"
        :columns="uploadColumns"
        :row-id="(u: Upload) => `${u.userHash}/${u.address}`"
        :label="label"
        :columns-label="label"
    />
    <DataTable
        v-else-if="shelf === 'banned'"
        v-model:selected="selected"
        name="bans"
        :rows="bans"
        :columns="banColumns"
        :row-id="(b: Ban) => b.address"
        :label="label"
        :columns-label="label"
    />
    <DataTable
        v-else
        v-model:selected="selected"
        name="known"
        :rows="known"
        :columns="knownColumns"
        :row-id="(k: KnownClient) => k.userHash"
        :label="label"
        :columns-label="label"
    />
</template>
