<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElSegmented } from 'element-plus';
import { computed, ref } from 'vue';
import { api } from '../api';
import { usePoll } from '../engine';
import { t } from '../i18n';
import { guest } from '../role';
import type { Ban, Category, Download, Source, Upload } from '../types';
import BansView from './transfers/BansView.vue';
import DownloadsView from './transfers/DownloadsView.vue';
import PeersView from './transfers/PeersView.vue';

type Shelf = 'downloads' | 'uploads' | 'queue' | 'banned';

const shelf = ref<Shelf>('downloads');
const downloads = usePoll(() => api.get<Download[]>('/downloads'), 1000, [] as Download[]);
const peers = usePoll(() => api.get<Upload[]>('/uploads'), 2000, [] as Upload[]);
const bans = usePoll(() => (guest ? Promise.resolve([]) : api.get<Ban[]>('/bans')), 2000, [] as Ban[]);
const sources = usePoll(() => api.get<Source[]>('/sources'), 2000, [] as Source[]);
const categories = usePoll(() => api.get<Category[]>('/categories'), 15000, [] as Category[]);

const uploading = computed(() => peers.data.value.filter((p) => p.state === 'uploading'));
const queued = computed(() => peers.data.value.filter((p) => p.state === 'queued'));

const shelves = computed(() =>
    [
        { value: 'downloads', label: `${t('IDS_TW_DOWNLOADS')} ${downloads.data.value.length}` },
        { value: 'uploads', label: `${t('IDS_TW_UPLOADS')} ${uploading.value.length}` },
        { value: 'queue', label: `${t('IDS_ONQUEUE')} ${queued.value.length}` },
        { value: 'banned', label: `${t('IDS_BANNED')} ${bans.data.value.length}` },
    ].filter((s) => !guest || s.value !== 'banned'),
);
</script>

<template>
    <el-segmented v-model="shelf" :options="shelves" class="shelves" />
    <DownloadsView
        v-if="shelf === 'downloads'"
        :downloads="downloads.data.value"
        :categories="categories.data.value"
        :sources="sources.data.value"
        @changed="
            downloads.refresh();
            categories.refresh();
        "
    />
    <PeersView v-else-if="shelf === 'uploads'" :peers="uploading" />
    <PeersView v-else-if="shelf === 'queue'" :peers="queued" />
    <BansView v-else :bans="bans.data.value" @changed="bans.refresh" />
</template>

<style scoped>
.shelves {
    margin-bottom: 14px;
}
</style>
