<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElInput } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import { ApiError } from '../api';
import { t } from '../i18n';
import { failed, told } from '../notify';

// The guest's password, which the web interface keeps apart from the engine's settings; a guest may only look.
const MIN_LENGTH = 8;
const on = ref(false);
const password = ref('');
const again = ref('');
const ready = computed(() => password.value.length >= MIN_LENGTH && password.value === again.value);

function csrf(): string {
    return document.querySelector<HTMLMetaElement>('meta[name="njmule-csrf"]')?.content ?? '';
}

async function call(method: string, body?: URLSearchParams): Promise<void> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (method !== 'GET') {
        headers['X-NjMule-CSRF'] = csrf();
    }
    const answer = await fetch('webui/guest', { method, headers, body, credentials: 'same-origin' });
    const json = (await answer.json()) as { guest?: boolean; error?: string };
    if (!answer.ok) {
        throw new ApiError(answer.status, json.error ?? answer.statusText);
    }
    on.value = json.guest === true;
}

onMounted(() => call('GET').catch(failed));

// An empty password takes the guest away.
async function save(given: string): Promise<void> {
    try {
        await call('PUT', new URLSearchParams({ password: given }));
        password.value = '';
        again.value = '';
        told(t('nj.settings.saved'), 'success');
    } catch (e) {
        failed(e);
    }
}
</script>

<template>
    <form class="guest" @submit.prevent="save(password)">
        <label for="nj-guest">
            {{ t('nj.webui.guestPassword') }}
            <small>{{ t('nj.webui.guestHint') }}</small>
            <small v-if="on">{{ t('nj.webui.guestOn') }}</small>
        </label>
        <el-input
            id="nj-guest"
            v-model="password"
            type="password"
            show-password
            autocomplete="new-password"
            maxlength="256"
        />
        <el-input
            v-model="again"
            type="password"
            autocomplete="new-password"
            maxlength="256"
            :placeholder="t('nj.input.passwordAgain')"
            :aria-label="t('nj.input.passwordAgain')"
        />
        <el-button type="primary" native-type="submit" :disabled="!ready">{{ t('IDS_SAVE') }}</el-button>
        <el-button :disabled="!on" @click="save('')">{{ t('nj.webui.noGuest') }}</el-button>
    </form>
</template>

<style scoped>
.guest {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px 16px;
    padding: 10px 0;
}
.guest label {
    display: flex;
    flex: 1 1 100%;
    flex-direction: column;
}
.guest small {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.guest .el-input {
    flex: 1 1 200px;
}
</style>
