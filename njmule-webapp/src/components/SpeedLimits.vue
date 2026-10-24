<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInputNumber, ElPopover } from 'element-plus';
import { ref } from 'vue';
import { api } from '../api';
import { status } from '../engine';
import { rate } from '../format';
import { t } from '../i18n';
import { attempt } from '../notify';
import NjIcon from './NjIcon.vue';

interface Limits {
    uploadKiBps: number;
    downloadKiBps: number;
}

const limits = ref<Limits>({ uploadKiBps: 0, downloadKiBps: 0 });

async function load(): Promise<void> {
    await attempt(async () => {
        limits.value = await api.get<Limits>('/limits');
    });
}

async function save(): Promise<void> {
    await attempt(async () => {
        limits.value = await api.put<Limits>('/limits', limits.value);
    });
}
</script>

<template>
    <el-popover v-if="status" trigger="click" placement="bottom-end" :width="300" @show="load">
        <template #reference>
            <button type="button" class="rates" :title="t('nj.tray.speed')">
                <NjIcon name="ARROW_DOWN" />{{ rate(status.downloadBytesPerSecond) }} <NjIcon name="ARROW_UP" />{{
                    rate(status.uploadBytesPerSecond)
                }}
            </button>
        </template>
        <b>{{ t('nj.tray.limits') }}</b>
        <label class="limit">
            <span>{{ t('IDS_PW_CON_DOWNLBL') }} (KB/s)</span>
            <el-input-number v-model="limits.downloadKiBps" :min="0" :max="10000000" controls-position="right" />
        </label>
        <label class="limit">
            <span>{{ t('IDS_PW_CON_UPLBL') }} (KB/s)</span>
            <el-input-number v-model="limits.uploadKiBps" :min="0" :max="10000000" controls-position="right" />
        </label>
        <p class="hint">0 = {{ t('nj.rate.noLimit') }}</p>
        <el-button type="primary" class="save" @click="save">{{ t('IDS_PW_APPLY') }}</el-button>
    </el-popover>
</template>

<style scoped>
.rates {
    display: flex;
    align-items: center;
    gap: 4px;
    padding: 2px 6px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-secondary);
    font: inherit;
    font-variant-numeric: tabular-nums;
    cursor: pointer;
}
.rates:hover {
    background: var(--nj-subtle);
}
.limit {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    margin-top: 10px;
}
.hint {
    margin: 8px 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.save {
    width: 100%;
}
</style>
