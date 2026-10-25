<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { ElDialog, ElInput } from 'element-plus';
import { computed, nextTick, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api';
import { t } from '../i18n';
import { guest } from '../role';
import { fileLinks } from '../links';
import { attempt, told } from '../notify';

export interface Place {
    title: string;
    route: string;
}

const props = defineProps<{ places: Place[] }>();
const open = defineModel<boolean>({ required: true });
const router = useRouter();

const text = ref('');
const field = ref<InstanceType<typeof ElInput> | null>(null);

const links = computed(() => (guest ? [] : fileLinks(text.value)));
const matching = computed(() => {
    const words = text.value.trim().toLowerCase();
    return words === '' ? props.places : props.places.filter((p) => t(p.title).toLowerCase().includes(words));
});

watch(open, async (now) => {
    if (now) {
        text.value = '';
        await nextTick();
        field.value?.focus();
    }
});

function go(route: string): void {
    open.value = false;
    void router.push(route);
}

// Links download; any other text is searched for, unless it names a place.
async function run(): Promise<void> {
    if (links.value.length > 0) {
        const found = links.value;
        open.value = false;
        if (await attempt(() => api.post('/downloads', { links: found.map((l) => l.link) }))) {
            found.forEach((l) => told(t('nj.transfers.added', l.name), 'success'));
        }
    } else if (matching.value.length === 1 && matching.value[0]) {
        go(matching.value[0].route);
    } else if (text.value.trim() !== '') {
        go(`/search?q=${encodeURIComponent(text.value.trim())}`);
    }
}
</script>

<template>
    <el-dialog v-model="open" width="560px" :show-close="false" class="palette" align-center>
        <el-input ref="field" v-model="text" :placeholder="t('nj.palette.prompt')" @keydown.enter.prevent="run" />
        <ul class="choices">
            <li v-for="l in links" :key="l.hash">
                <button type="button" @click="run">{{ t('IDS_DOWNLOAD') }}: {{ l.name }}</button>
            </li>
            <template v-if="links.length === 0">
                <li v-for="p in matching" :key="p.route">
                    <button type="button" @click="go(p.route)">{{ t(p.title) }}</button>
                </li>
                <li v-if="!guest && text.trim() !== ''">
                    <button type="button" @click="go(`/search?q=${encodeURIComponent(text.trim())}`)">
                        {{ t('nj.palette.search', text.trim()) }}
                    </button>
                </li>
            </template>
        </ul>
    </el-dialog>
</template>

<style scoped>
.choices {
    margin: 10px 0 0;
    padding: 0;
    list-style: none;
}
.choices button {
    width: 100%;
    padding: 7px 10px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-primary);
    font: inherit;
    text-align: start;
    cursor: pointer;
}
.choices button:hover,
.choices button:focus {
    background: var(--el-fill-color);
}
</style>
