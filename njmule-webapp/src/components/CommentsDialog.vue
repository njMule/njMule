<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElDialog, ElInput, ElOption, ElSelect } from 'element-plus';
import { ref, watch } from 'vue';
import { api } from '../api';
import { t } from '../i18n';
import { attempt, failed } from '../notify';

interface Comment {
    who: string;
    fileName: string;
    rating: number;
    text: string;
}

// The file whose comments are shown; a download also has the comments of its sources.
const props = defineProps<{ hash: string; name: string; download: boolean }>();
const open = defineModel<boolean>({ required: true });

const RATINGS = [
    'IDS_CMT_NOTRATED',
    'IDS_CMT_FAKE',
    'IDS_CMT_POOR',
    'IDS_CMT_FAIR',
    'IDS_CMT_GOOD',
    'IDS_CMT_EXCELLENT',
];

const rating = ref(0);
const text = ref('');
const comments = ref<Comment[]>([]);
const kad = ref('');

watch(open, async (now) => {
    if (!now) {
        return;
    }
    kad.value = '';
    await attempt(async () => {
        const mine = await api.get<{ rating: number; text: string }>(`/files/${props.hash}/comment`);
        rating.value = mine.rating;
        text.value = mine.text;
        const peers = props.download ? await api.get<Comment[]>(`/downloads/${props.hash}/comments`) : [];
        comments.value = [...peers, ...(await api.get<Comment[]>(`/files/${props.hash}/notes`))];
    });
});

async function searchKad(): Promise<void> {
    kad.value = t('IDS_KADSEARCHACTIVE');
    try {
        const notes = await api.post<Comment[]>(`/files/${props.hash}/notes`);
        kad.value = notes.length > 0 ? t('nj.comments.kadCount', notes.length) : t('nj.comments.kadNone');
        comments.value = [...comments.value.filter((c) => !notes.some((n) => n.who === c.who)), ...notes];
    } catch (e) {
        kad.value = t('nj.comments.kadFailed', e instanceof Error ? e.message : String(e));
    }
}

async function save(): Promise<void> {
    try {
        await api.put(`/files/${props.hash}/comment`, { rating: rating.value, text: text.value });
        open.value = false;
    } catch (e) {
        failed(e);
    }
}
</script>

<template>
    <el-dialog v-model="open" :title="t('nj.comments.title')" width="620px">
        <p class="name">{{ name }}</p>
        <label class="field">
            {{ t('IDS_CMT_RATEQUEST') }}
            <el-select v-model="rating">
                <el-option v-for="(key, i) in RATINGS" :key="i" :value="i" :label="t(key)" />
            </el-select>
        </label>
        <label class="field">
            {{ t('IDS_CMT_READ') }}
            <el-input v-model="text" maxlength="128" :placeholder="t('IDS_CMT_QUEST')" />
        </label>
        <h3>{{ t('IDS_CMT_READALL') }}</h3>
        <ul v-if="comments.length > 0" class="comments">
            <li v-for="(c, i) in comments" :key="i" :title="c.fileName">
                <b>{{ c.who }}</b
                >: <span class="nj-chip">{{ t(RATINGS[c.rating] ?? 'IDS_CMT_NOTRATED') }}</span>
                {{ c.text }}
            </li>
        </ul>
        <p v-else class="hint">{{ t('IDS_CMT_NONE') }}</p>
        <p class="kad">
            <el-button size="small" @click="searchKad">{{ t('IDS_SEARCHKAD') }}</el-button>
            <span class="hint">{{ kad }}</span>
        </p>
        <template #footer>
            <el-button @click="open = false">{{ t('IDS_FD_CLOSE') }}</el-button>
            <el-button type="primary" @click="save">{{ t('nj.categories.save') }}</el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.name {
    margin-top: 0;
    font-weight: 600;
    word-break: break-all;
}
.field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 10px;
}
.comments {
    max-height: 220px;
    overflow-y: auto;
    padding-inline-start: 18px;
}
.kad {
    display: flex;
    align-items: center;
    gap: 10px;
}
.hint {
    color: var(--el-text-color-secondary);
}
</style>
