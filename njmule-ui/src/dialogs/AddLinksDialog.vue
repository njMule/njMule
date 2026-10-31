<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot } from 'reka-ui';
import { computed, nextTick, ref, watch } from 'vue';
import { size } from '../format';
import { t } from '../i18n';
import { fileLinks } from '../links';
import { categories, downloads, preferences, shared } from '../model/engine';
import { actsFirst, kindIcon, kindOf, knownAs } from '../transfers';
import { addLinks } from '../transfers-actions';
import DialogHeader from '../ui/DialogHeader.vue';
import Icon from '../ui/Icon.vue';

// eD2K file links to download, typed or pasted in a box: the files found in it, each with what is known of it already,
// all or some of them, in a category, paused or not.

/** The category shown in the transfers, which new downloads go to by default. */
const props = defineProps<{ category?: number }>();
const open = defineModel<boolean>('open', { required: true });
// The rules of the categories decide, unless one is chosen.
const BY_RULES = -1;

const text = ref('');
const chosenCategory = ref(BY_RULES);
const paused = ref(false);
// The files left out by the user, or by default; a file still in the box keeps its tick.
const unticked = ref(new Set<string>());
const box = ref<HTMLTextAreaElement | null>(null);
const first = actsFirst();

const found = computed(() => fileLinks(text.value));
const downloading = computed(() => new Set(downloads.value.map((d) => d.hash.toUpperCase())));
const sharing = computed(() => new Set(shared.value.map((f) => f.hash.toUpperCase())));
const chosen = computed(() => found.value.filter((l) => !unticked.value.has(l.hash)));
const total = computed(() =>
    chosen.value.length === 0
        ? ''
        : `${chosen.value.length} · ${size(chosen.value.reduce((sum, l) => sum + l.size, 0))}`,
);

// A file being downloaded or shared already is there: it is left out unless ticked.
watch(found, (now, was) => {
    const before = new Set((was ?? []).map((l) => l.hash));
    const next = new Set([...unticked.value].filter((h) => now.some((l) => l.hash === h)));
    for (const l of now) {
        if (!before.has(l.hash) && knownAs(l.hash, downloading.value, sharing.value) !== null) {
            next.add(l.hash);
        }
    }
    unticked.value = next;
});

// Links on the clipboard are in the box already.
watch(open, async (now) => {
    if (!now) {
        return;
    }
    chosenCategory.value =
        props.category !== undefined && categories.value.some((c) => c.id === props.category)
            ? props.category
            : BY_RULES;
    paused.value = preferences.value['download.addPaused'] === 'true';
    unticked.value = new Set();
    text.value = '';
    try {
        const clip = await navigator.clipboard.readText();
        text.value = fileLinks(clip).length > 0 ? clip : '';
    } catch {
        // Nothing to start with.
    }
    await nextTick();
    box.value?.focus();
});

function tick(hash: string, on: boolean): void {
    const next = new Set(unticked.value);
    if (on) {
        next.delete(hash);
    } else {
        next.add(hash);
    }
    unticked.value = next;
}

// The clipboard's text goes after what is typed, on a line of its own.
async function paste(): Promise<void> {
    try {
        const pasted = await navigator.clipboard.readText();
        if (pasted.trim() !== '') {
            text.value = text.value.trim() === '' ? pasted : `${text.value.trimEnd()}\n${pasted}`;
        }
    } catch {
        // The clipboard keeps to itself.
    }
}

async function add(): Promise<void> {
    const links = chosen.value.map((l) => l.link);
    open.value = false;
    await addLinks(
        links.join('\n'),
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
                <DialogHeader :title="t('nj.addLinks.title')" />
                <div class="prompt-row">
                    <label class="field-label" for="links-text">{{ t('nj.addLinks.prompt') }}</label>
                    <button class="button flat" @click="paste"><Icon name="PASTE" />{{ t('IDS_PASTE') }}</button>
                </div>
                <textarea
                    id="links-text"
                    ref="box"
                    v-model="text"
                    class="links-box"
                    placeholder="ed2k://|file|…|/"
                    rows="4"
                    wrap="off"
                    dir="ltr"
                />
                <span class="muted">{{ t('nj.addLinks.hint') }}</span>
                <strong>{{ t('nj.addLinks.found', found.length) }}</strong>
                <div class="links-found">
                    <label v-for="l in found" :key="l.hash" class="link-row">
                        <input
                            type="checkbox"
                            :checked="!unticked.has(l.hash)"
                            @change="tick(l.hash, ($event.target as HTMLInputElement).checked)"
                        />
                        <Icon class="file-kind" :name="kindIcon(kindOf(l.name))" />
                        <span class="ellipsis grow" dir="auto">{{ l.name }}</span>
                        <span v-if="knownAs(l.hash, downloading, sharing)" class="state-chip neutral"
                            ><Icon name="INFO" />{{ t(knownAs(l.hash, downloading, sharing)!) }}</span
                        >
                        <span class="muted" dir="ltr">{{ size(l.size) }}</span>
                    </label>
                </div>
                <label class="option">
                    {{ t('IDS_CAT') }}
                    <select v-model.number="chosenCategory" class="field">
                        <option :value="BY_RULES">{{ t('nj.transfers.categoryByRules') }}</option>
                        <option :value="0">{{ t('IDS_CAT_UNASSIGN') }}</option>
                        <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
                    </select>
                </label>
                <label class="option"><input v-model="paused" type="checkbox" />{{ t('IDS_ADDNEWFILESPAUSED') }}</label>
                <span class="muted">{{ total }}</span>
                <div class="dialog-buttons">
                    <button v-if="first" class="button accent" :disabled="chosen.length === 0" @click="add">
                        {{ t('IDS_DOWNLOAD') }}
                    </button>
                    <DialogClose class="button">{{ t('IDS_CANCEL') }}</DialogClose>
                    <button v-if="!first" class="button accent" :disabled="chosen.length === 0" @click="add">
                        {{ t('IDS_DOWNLOAD') }}
                    </button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.add-links {
    width: min(600px, calc(100vw - 32px));
    gap: 10px;
}

.prompt-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
}

.field-label {
    font-weight: bold;
}

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

/* The files found in the links, each on a line with what is known of it. */
.links-found {
    display: flex;
    flex-direction: column;
    gap: 4px;
    height: 160px;
    padding: 4px 0;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    overflow-y: auto;
}

.link-row {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    padding: 4px 8px;
}

.grow {
    flex: 1;
    min-width: 0;
}

.file-kind {
    --nj-icon-size: 16px;
    --nj-icon-color: var(--color-fg-muted);
}

.option {
    display: flex;
    align-items: center;
    gap: 8px;
}
</style>
