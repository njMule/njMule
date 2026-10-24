<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElProgress } from 'element-plus';
import { computed } from 'vue';
import { api } from '../api';
import { usePoll } from '../engine';
import { size } from '../format';
import { language, t } from '../i18n';
import { attempt } from '../notify';

export interface Update {
    current: string;
    canInstall: boolean;
    checking: boolean;
    lastCheck?: string;
    failure?: string;
    available: { version: string; published: string; critical: boolean; notes: string; page: string } | null;
    skipped?: string;
    download: { received: number; size: number } | null;
    ready: boolean;
    install: 'asked' | 'at_exit' | 'restart';
}

const update = usePoll(() => api.get<Update>('/update'), 3000, null as Update | null);
const u = computed(() => update.data.value);

async function act(path: string, body?: unknown): Promise<void> {
    await attempt(() => api.post(path, body));
    await update.refresh();
}

function when(time: string): string {
    return new Date(time).toLocaleString(language.value);
}
</script>

<template>
    <div v-if="u" class="update">
        <p class="hint">{{ t('nj.update.note') }}</p>
        <p v-if="u.install === 'restart'" class="notice">{{ t('nj.update.installing') }}</p>
        <template v-else-if="u.available">
            <p class="notice">
                <b>{{
                    u.ready ? t('nj.update.ready', u.available.version) : t('nj.update.available', u.available.version)
                }}</b>
                <span v-if="u.available.critical"> {{ t('nj.update.critical') }}</span>
                <br />
                {{ t('nj.update.published', when(u.available.published)) }} ·
                <a :href="u.available.page" target="_blank" rel="noreferrer">{{ t('nj.update.changes') }}</a>
            </p>
            <template v-if="u.download">
                <el-progress :percentage="Math.floor((u.download.received * 100) / Math.max(1, u.download.size))" />
                <p>{{ t('nj.update.downloading', size(u.download.received), size(u.download.size)) }}</p>
            </template>
            <div class="buttons">
                <template v-if="u.ready && u.canInstall">
                    <el-button type="primary" @click="act('/update/install', { restart: true })">
                        {{ t('nj.update.restart') }}
                    </el-button>
                    <el-button v-if="u.install !== 'at_exit'" @click="act('/update/install', { restart: false })">
                        {{ t('nj.update.atExit') }}
                    </el-button>
                    <span v-else>{{ t('nj.update.willInstall') }}</span>
                </template>
                <el-button v-else-if="!u.ready && !u.download" type="primary" @click="act('/update/download')">
                    {{ u.canInstall ? t('nj.update.downloadInstall') : t('nj.update.download') }}
                </el-button>
                <el-button
                    v-if="!u.available.critical && u.skipped !== u.available.version"
                    @click="act('/update/skip', { version: u.available.version })"
                >
                    {{ t('nj.update.skip') }}
                </el-button>
            </div>
        </template>
        <p v-else-if="u.lastCheck && !u.failure">{{ t('nj.update.newest', u.current) }}</p>
        <p v-if="u.failure" class="error">{{ t('nj.update.failed', u.failure) }}</p>
        <p v-if="u.checking">{{ t('nj.update.checking') }}</p>
        <div v-else class="buttons">
            <span v-if="u.lastCheck" class="hint">{{ t('nj.update.lastCheck', when(u.lastCheck)) }}</span>
            <el-button @click="act('/update/check')">{{ t('IDS_CHECK4UPDATE') }}</el-button>
        </div>
    </div>
</template>

<style scoped>
.update p {
    margin: 6px 0;
}
.hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.notice {
    padding: 10px 14px;
    border-inline-start: 4px solid var(--nj-accent);
    border-radius: 6px;
    background: var(--nj-subtle);
}
.error {
    color: var(--nj-error-fg);
}
.buttons {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin: 8px 0;
}
.buttons .el-button + .el-button {
    margin-left: 0;
}
</style>
