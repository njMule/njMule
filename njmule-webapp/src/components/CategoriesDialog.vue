<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElCheckbox, ElDialog, ElInput, ElOption, ElSelect } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import { t } from '../i18n';
import { attempt } from '../notify';
import type { Category, DownloadPriority } from '../types';

const props = defineProps<{ categories: Category[] }>();
const open = defineModel<boolean>({ required: true });
const emit = defineEmits<{ changed: [] }>();

const empty = (): Category => ({
    id: 0,
    name: '',
    comment: '',
    priority: 'auto',
    color: 0,
    patterns: '',
    regex: false,
});
const form = ref<Category>(empty());

const COLORS = [
    'nj.category.color0',
    'nj.category.color1',
    'nj.category.color2',
    'nj.category.color3',
    'nj.category.color4',
    'nj.category.color5',
    'nj.category.color6',
    'nj.category.color7',
    'nj.category.color8',
];

const PRIORITIES: { value: DownloadPriority; key: string }[] = [
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];

const isNew = computed(() => form.value.id === 0);

watch(open, (now) => {
    if (now) {
        form.value = empty();
    }
});

function choose(c: Category): void {
    form.value = { ...c, incoming: c.incoming ?? '' };
}

// The whole list is written at once; a new category takes the next id.
async function write(all: Category[]): Promise<void> {
    const body = all.map((c) => ({ ...c, incoming: c.incoming === '' ? null : (c.incoming ?? null) }));
    if (await attempt(() => api.put('/categories', body))) {
        emit('changed');
        form.value = empty();
    }
}

async function save(): Promise<void> {
    const next = isNew.value
        ? { ...form.value, id: Math.max(0, ...props.categories.map((c) => c.id)) + 1 }
        : form.value;
    await write([...props.categories.filter((c) => c.id !== next.id), next].sort((a, b) => a.id - b.id));
}

async function remove(): Promise<void> {
    await write(props.categories.filter((c) => c.id !== form.value.id));
}
</script>

<template>
    <el-dialog v-model="open" :title="t('nj.categories.title')" width="720px">
        <div class="layout">
            <aside>
                <ul>
                    <li v-for="c in categories" :key="c.id">
                        <button type="button" :class="{ current: form.id === c.id }" @click="choose(c)">
                            <span class="tint" :class="`cat-${c.color ?? 0}`"></span>{{ c.name }}
                        </button>
                    </li>
                </ul>
                <p v-if="categories.length === 0" class="hint">{{ t('nj.categories.none') }}</p>
                <el-button @click="form = empty()">{{ t('IDS_NEW') }}</el-button>
            </aside>
            <form class="form" @submit.prevent="save">
                <label>{{ t('IDS_TITLE') }}<el-input v-model="form.name" maxlength="64" /></label>
                <label
                    >{{ t('IDS_PW_INCOMING')
                    }}<el-input v-model="form.incoming" :placeholder="t('nj.settings.defaultDir')"
                /></label>
                <label>{{ t('IDS_COMMENT') }}<el-input v-model="form.comment" maxlength="256" /></label>
                <label
                    >{{ t('IDS_COLOR') }}
                    <el-select v-model="form.color">
                        <el-option v-for="(key, i) in COLORS" :key="i" :value="i" :label="t(key)" />
                    </el-select>
                </label>
                <label
                    >{{ t('IDS_PRIORITY') }}
                    <el-select v-model="form.priority">
                        <el-option v-for="p in PRIORITIES" :key="p.value" :value="p.value" :label="t(p.key)" />
                    </el-select>
                </label>
                <label
                    >{{ t('IDS_AUTOCAT_LABEL')
                    }}<el-input v-model="form.patterns" maxlength="1024" placeholder="*.mkv|*.avi"
                /></label>
                <el-checkbox v-model="form.regex">{{ t('IDS_REGEXPRESSION') }}</el-checkbox>
            </form>
        </div>
        <template #footer>
            <el-button v-if="!isNew" type="danger" plain @click="remove">{{ t('IDS_CAT_REMOVE') }}</el-button>
            <el-button @click="open = false">{{ t('IDS_FD_CLOSE') }}</el-button>
            <el-button type="primary" :disabled="form.name.trim() === ''" @click="save">
                {{ t('nj.categories.save') }}
            </el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.layout {
    display: grid;
    grid-template-columns: 200px minmax(0, 1fr);
    gap: 16px;
}
ul {
    margin: 0 0 10px;
    padding: 0;
    list-style: none;
}
li button {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 6px 8px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-primary);
    font: inherit;
    text-align: start;
    cursor: pointer;
}
li button.current {
    background: var(--el-fill-color);
    font-weight: 600;
}
.tint {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    border: 1px solid var(--el-border-color-darker);
}
.form label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 10px;
}
.hint {
    color: var(--el-text-color-secondary);
}
</style>
