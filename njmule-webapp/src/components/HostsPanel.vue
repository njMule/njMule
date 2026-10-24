<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInput } from 'element-plus';
import { onMounted, ref } from 'vue';
import { ApiError } from '../api';
import { t } from '../i18n';
import { failed, told } from '../notify';

// The web interface's own settings, which it keeps apart from the engine's.
const hosts = ref('');

function csrf(): string {
    return document.querySelector<HTMLMetaElement>('meta[name="njmule-csrf"]')?.content ?? '';
}

async function call(method: string, body?: URLSearchParams): Promise<void> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (method !== 'GET') {
        headers['X-NjMule-CSRF'] = csrf();
    }
    const answer = await fetch('webui/hosts', { method, headers, body, credentials: 'same-origin' });
    const json = (await answer.json()) as { hosts?: string[]; error?: string };
    if (!answer.ok) {
        throw new ApiError(answer.status, json.error ?? answer.statusText);
    }
    hosts.value = (json.hosts ?? []).join(', ');
}

onMounted(() => call('GET').catch(failed));

async function save(): Promise<void> {
    try {
        await call('PUT', new URLSearchParams({ hosts: hosts.value }));
        told(t('nj.settings.saved'), 'success');
    } catch (e) {
        failed(e);
    }
}
</script>

<template>
    <form class="hosts" @submit.prevent="save">
        <label for="nj-hosts">
            {{ t('nj.webui.hosts') }}
            <small>{{ t('nj.webui.hostsHint') }}</small>
        </label>
        <el-input id="nj-hosts" v-model="hosts" placeholder="nas.local, njmule.example.com" maxlength="4096" />
        <el-button type="primary" native-type="submit">{{ t('IDS_SAVE') }}</el-button>
    </form>
</template>

<style scoped>
.hosts {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 16px;
    padding: 10px 0;
}
.hosts label {
    display: flex;
    flex: 1 1 100%;
    flex-direction: column;
}
.hosts small {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.hosts .el-input {
    flex: 1 1 320px;
}
</style>
