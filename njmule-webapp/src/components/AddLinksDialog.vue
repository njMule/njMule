<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElButton, ElCheckbox, ElDialog, ElInput, ElOption, ElSelect } from 'element-plus';
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import { size } from '../format';
import { t } from '../i18n';
import { fileLinks } from '../links';
import { attempt, told } from '../notify';
import type { Category, Download } from '../types';

const props = defineProps<{ categories: Category[]; downloads: Download[]; text?: string }>();
const open = defineModel<boolean>({ required: true });
const emit = defineEmits<{ added: [] }>();

const text = ref('');
const category = ref<number | null>(null);
const ticked = ref<Set<string>>(new Set());

const found = computed(() => fileLinks(text.value));
const running = computed(() => new Set(props.downloads.map((d) => d.hash.toUpperCase())));
const chosen = computed(() => found.value.filter((f) => ticked.value.has(f.hash)));
const total = computed(() => chosen.value.reduce((sum, f) => sum + f.size, 0));

watch(open, (now) => {
    if (now) {
        text.value = props.text ?? '';
        category.value = null;
    }
});

// New files are ticked, those downloading already are not.
watch(found, (links) => {
    ticked.value = new Set(links.filter((f) => !running.value.has(f.hash)).map((f) => f.hash));
});

function tick(hash: string, on: boolean): void {
    const next = new Set(ticked.value);
    if (on) {
        next.add(hash);
    } else {
        next.delete(hash);
    }
    ticked.value = next;
}

async function download(): Promise<void> {
    const body: { links: string[]; category?: number } = { links: chosen.value.map((f) => f.link) };
    if (category.value !== null) {
        body.category = category.value;
    }
    if (await attempt(() => api.post('/downloads', body))) {
        for (const f of chosen.value) {
            told(t('nj.transfers.added', f.name), 'success');
        }
        open.value = false;
        emit('added');
    }
}
</script>

<template>
    <el-dialog v-model="open" :title="t('nj.addLinks.title')" width="640px">
        <label class="label" for="nj-links">{{ t('nj.addLinks.prompt') }}</label>
        <el-input id="nj-links" v-model="text" type="textarea" :rows="5" placeholder="ed2k://|file|…|/" />
        <p class="hint">{{ t('nj.addLinks.hint') }}</p>
        <template v-if="found.length > 0">
            <p class="label">{{ t('nj.addLinks.found', found.length) }}</p>
            <ul class="files">
                <li v-for="f in found" :key="f.hash">
                    <el-checkbox :model-value="ticked.has(f.hash)" @change="(on) => tick(f.hash, Boolean(on))">
                        {{ f.name }}
                    </el-checkbox>
                    <span v-if="running.has(f.hash)" class="nj-chip busy">{{ t('IDS_DOWNLOADING') }}</span>
                    <span class="size">{{ size(f.size) }}</span>
                </li>
            </ul>
            <div class="row">
                <span>{{ t('IDS_CAT') }}</span>
                <el-select
                    v-model="category"
                    class="category"
                    :placeholder="t('nj.transfers.categoryByRules')"
                    clearable
                >
                    <el-option :value="0" :label="t('IDS_CAT_UNASSIGN')" />
                    <el-option v-for="c in categories" :key="c.id" :value="c.id" :label="c.name" />
                </el-select>
                <span class="grow"></span>
                <span>{{ chosen.length }} · {{ size(total) }}</span>
            </div>
        </template>
        <template #footer>
            <el-button @click="open = false">{{ t('IDS_CANCEL') }}</el-button>
            <el-button type="primary" :disabled="chosen.length === 0" @click="download">{{
                t('IDS_DOWNLOAD')
            }}</el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.label {
    display: block;
    margin: 0 0 6px;
    font-weight: 600;
}
.hint {
    margin: 6px 0 12px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.files {
    max-height: 220px;
    overflow-y: auto;
    margin: 0 0 12px;
    padding: 0;
    list-style: none;
}
.files li {
    display: flex;
    align-items: center;
    gap: 8px;
}
.size {
    margin-inline-start: auto;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
}
.row {
    display: flex;
    align-items: center;
    gap: 10px;
}
.category {
    width: 260px;
}
.grow {
    flex: 1;
}
</style>
