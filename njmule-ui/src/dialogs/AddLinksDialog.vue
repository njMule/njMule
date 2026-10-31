<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, ref, watch } from 'vue';
import { size } from '../format';
import { t } from '../i18n';
import { fileLinks } from '../links';
import { categories, downloads } from '../model/engine';
import { addLinks } from '../transfers-actions';
import Icon from '../ui/Icon.vue';

// The eD2K file links to add, pasted or typed, with what is known of each file before it is added: a category (the
// rules of the categories choose one by default) and whether it starts paused.

const props = defineProps<{ category?: number }>();
const open = defineModel<boolean>('open', { required: true });
// The rules of the categories decide, unless one is chosen.
const BY_RULES = -1;

const text = ref('');
const chosenCategory = ref(BY_RULES);
const paused = ref(false);
const found = computed(() => fileLinks(text.value));
const knownHashes = computed(() => new Set(downloads.value.map((d) => d.hash.toUpperCase())));

// Links on the clipboard are in the box already.
watch(open, async (now) => {
    if (!now) {
        return;
    }
    chosenCategory.value = props.category ?? BY_RULES;
    paused.value = false;
    try {
        const clip = await navigator.clipboard.readText();
        text.value = fileLinks(clip).length > 0 ? clip : '';
    } catch {
        text.value = '';
    }
});

async function add(): Promise<void> {
    const fresh = found.value.filter((l) => !knownHashes.value.has(l.hash)).map((l) => l.link);
    open.value = false;
    await addLinks(
        fresh.join('\n'),
        chosenCategory.value === BY_RULES ? undefined : chosenCategory.value,
        paused.value,
        false,
    );
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog add-links" :aria-describedby="undefined">
                <DialogTitle class="dialog-title display">{{ t('nj.addLinks.title') }}</DialogTitle>
                <textarea v-model="text" class="links-box" :placeholder="t('nj.addLinks.prompt')" rows="5" />
                <span class="muted">{{ t('nj.addLinks.hint') }}</span>
                <div v-if="found.length > 0" class="links-found">
                    <strong>{{ t('nj.addLinks.found', found.length) }}</strong>
                    <div v-for="l in found" :key="l.hash" class="found-line">
                        <span class="ellipsis" dir="auto">{{ l.name }}</span>
                        <span class="muted">{{ knownHashes.has(l.hash) ? t('IDS_IMP_ALRDWL') : size(l.size) }}</span>
                    </div>
                </div>
                <div class="options">
                    <label class="option">
                        {{ t('IDS_CAT') }}
                        <select v-model.number="chosenCategory" class="field">
                            <option :value="BY_RULES">{{ t('nj.transfers.categoryByRules') }}</option>
                            <option :value="0">{{ t('IDS_CAT_UNASSIGN') }}</option>
                            <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
                        </select>
                    </label>
                    <label class="option"
                        ><input v-model="paused" type="checkbox" />{{ t('IDS_ADDNEWFILESPAUSED') }}</label
                    >
                </div>
                <div class="dialog-buttons">
                    <DialogClose class="button">{{ t('IDS_CANCEL') }}</DialogClose>
                    <button class="button accent" :disabled="found.every((l) => knownHashes.has(l.hash))" @click="add">
                        <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                    </button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.links-box {
    width: 100%;
    padding: 8px;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
    color: inherit;
    font: inherit;
    resize: vertical;
}

.links-found {
    display: flex;
    flex-direction: column;
    gap: 4px;
    max-height: 160px;
    padding: 8px 12px;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    overflow-y: auto;
}

.found-line {
    display: flex;
    justify-content: space-between;
    gap: 12px;
}

.options {
    display: flex;
    align-items: center;
    gap: 24px;
}

.option {
    display: inline-flex;
    align-items: center;
    gap: 8px;
}
</style>
