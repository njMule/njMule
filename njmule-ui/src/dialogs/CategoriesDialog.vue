<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, ref, watch } from 'vue';
import { answer, api, type Schemas } from '../api/client';
import { t } from '../i18n';
import { categories, type Category } from '../model/engine';
import type { Platform } from '../platform';
import DotSelect from '../screens/transfers/DotSelect.vue';
import { problem } from '../toasts';
import { actsFirst } from '../transfers';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// The download categories: the list, and a form to add, change or remove one.

const props = defineProps<{ platform: Platform }>();
const open = defineModel<boolean>('open', { required: true });

// The limits the engine keeps a category to.
const MAX_NAME = 64;
const MAX_COMMENT = 256;
const MAX_PATTERNS = 1024;
const COLORS = 8;

type Priority = Schemas['DownloadPriority'];
const PRIORITIES: { value: Priority; key: string }[] = [
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];
const COLOR_NAMES = [
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

const chosen = ref<number | null>(null);
const name = ref('');
const incoming = ref('');
const comment = ref('');
const patterns = ref('');
const regex = ref(false);
const color = ref(0);
const priority = ref<Priority>('auto');
const first = actsFirst();

const colors = computed(() =>
    Array.from({ length: COLORS + 1 }, (_, c) => ({ value: c, label: t(COLOR_NAMES[c]!), color: c })),
);

// Printable characters only, as the engine takes them.
function printable(text: string): boolean {
    return !/\p{Cc}/u.test(text);
}

const valid = computed(
    () =>
        name.value.trim() !== '' &&
        name.value.trim().length <= MAX_NAME &&
        [name.value, incoming.value, comment.value, patterns.value].every(printable),
);

watch(open, (now) => {
    if (now) {
        fresh();
    }
});

/** An empty form, for a new category. */
function fresh(): void {
    chosen.value = null;
    name.value = '';
    incoming.value = '';
    comment.value = '';
    patterns.value = '';
    regex.value = false;
    color.value = 0;
    priority.value = 'auto';
}

function edit(c: Category): void {
    chosen.value = c.id;
    name.value = c.name;
    incoming.value = c.incoming ?? '';
    comment.value = c.comment;
    patterns.value = c.patterns ?? '';
    regex.value = c.regex ?? false;
    color.value = c.color ?? 0;
    priority.value = c.priority;
}

async function store(all: Category[]): Promise<boolean> {
    try {
        categories.value = await answer(
            api().PUT('/categories', {
                body: all.map((c) => ({
                    id: c.id,
                    name: c.name,
                    incoming: c.incoming,
                    comment: c.comment,
                    priority: c.priority,
                    color: c.color ?? 0,
                    patterns: c.patterns ?? '',
                    regex: c.regex ?? false,
                })),
            }),
        );
        return true;
    } catch (e) {
        problem(t('nj.settings.invalid', (e as Error).message));
        return false;
    }
}

/** The form as a new category, or as a change of the chosen one. */
async function save(): Promise<void> {
    const id = chosen.value ?? Math.max(0, ...categories.value.map((c) => c.id)) + 1;
    const category: Category = {
        id,
        name: name.value.trim(),
        incoming: incoming.value.trim() === '' ? null : incoming.value.trim(),
        comment: comment.value.trim(),
        priority: priority.value,
        color: color.value,
        patterns: patterns.value.trim(),
        regex: regex.value,
    };
    if (await store([...categories.value.filter((c) => c.id !== id), category])) {
        chosen.value = id;
    }
}

async function remove(): Promise<void> {
    const id = chosen.value;
    if (id !== null && (await store(categories.value.filter((c) => c.id !== id)))) {
        fresh();
    }
}

async function browse(): Promise<void> {
    const folder = await props.platform.chooseFolder(t('IDS_PW_INCOMING'));
    if (folder !== null) {
        incoming.value = folder;
    }
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog categories-dialog" :aria-describedby="undefined">
                <DialogTitle class="dialog-title display">{{ t('nj.categories.title') }}</DialogTitle>
                <div class="body">
                    <div class="list-box">
                        <ul class="list" role="listbox" :aria-label="t('nj.categories.title')">
                            <li v-if="categories.length === 0" class="muted none">{{ t('nj.categories.none') }}</li>
                            <li
                                v-for="c in categories"
                                :key="c.id"
                                role="option"
                                tabindex="0"
                                class="item"
                                :aria-selected="chosen === c.id"
                                @click="edit(c)"
                                @keydown.enter.space.prevent="edit(c)"
                            >
                                <span class="category-dot" :class="c.color ? `cat-${c.color}` : ''" /><span
                                    class="ellipsis"
                                    dir="auto"
                                    >{{ c.name }}</span
                                >
                            </li>
                        </ul>
                        <button class="button flat" @click="fresh"><Icon name="ADD" />{{ t('IDS_NEW') }}</button>
                    </div>
                    <div class="form">
                        <label class="named">
                            <span>{{ t('IDS_TITLE') }}</span>
                            <span class="field"><input v-model="name" :maxlength="MAX_NAME" dir="auto" /></span>
                        </label>
                        <label class="named">
                            <span>{{ t('IDS_PW_INCOMING') }}</span>
                            <span class="row">
                                <Tip :text="t('nj.input.folder')">
                                    <span class="field grow"
                                        ><input v-model="incoming" dir="ltr" :placeholder="t('nj.settings.defaultDir')"
                                    /></span>
                                </Tip>
                                <button class="button" :disabled="!platform.desktop" @click.prevent="browse">
                                    <Icon name="FOLDER_OPEN" />{{ t('IDS_PW_BROWSE') }}
                                </button>
                            </span>
                        </label>
                        <label class="named">
                            <span>{{ t('IDS_COMMENT') }}</span>
                            <span class="field"><input v-model="comment" :maxlength="MAX_COMMENT" dir="auto" /></span>
                        </label>
                        <div class="row">
                            <div class="named grow">
                                <span>{{ t('IDS_COLOR') }}</span>
                                <DotSelect v-model="color" :items="colors" :label="t('IDS_COLOR')" />
                            </div>
                            <label class="named grow">
                                <span>{{ t('IDS_PRIORITY') }}</span>
                                <select v-model="priority" class="field">
                                    <option v-for="p in PRIORITIES" :key="p.value" :value="p.value">
                                        {{ t(p.key) }}
                                    </option>
                                </select>
                            </label>
                        </div>
                        <label class="named">
                            <span>{{ t('IDS_AUTOCAT_LABEL') }}</span>
                            <span class="field"
                                ><input
                                    v-model="patterns"
                                    :maxlength="MAX_PATTERNS"
                                    dir="ltr"
                                    placeholder="*.mkv|*.avi"
                            /></span>
                        </label>
                        <label class="check"
                            ><input v-model="regex" type="checkbox" />{{ t('IDS_REGEXPRESSION') }}</label
                        >
                    </div>
                </div>
                <div class="footer">
                    <button class="button risky" :disabled="chosen === null" @click="remove">
                        <Icon name="DELETE" />{{ t('IDS_CAT_REMOVE') }}
                    </button>
                    <span class="grow" />
                    <button v-if="first" class="button accent" :disabled="!valid" @click="save">
                        <Icon name="SAVE" />{{ t('nj.categories.save') }}
                    </button>
                    <DialogClose class="button"><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose>
                    <button v-if="!first" class="button accent" :disabled="!valid" @click="save">
                        <Icon name="SAVE" />{{ t('nj.categories.save') }}
                    </button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.categories-dialog {
    width: min(760px, calc(100vw - 32px));
    height: min(520px, calc(100vh - 64px));
}

.body {
    display: flex;
    gap: 16px;
    flex: 1;
    min-height: 0;
}

.list-box {
    display: flex;
    flex-direction: column;
    gap: 6px;
    flex: 0 0 200px;
    min-width: 180px;
}

.list {
    flex: 1;
    min-height: 0;
    margin: 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
}

.item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    cursor: default;
}

.item[aria-selected='true'] {
    background: var(--color-accent-muted);
}

.item:focus-visible {
    outline: 2px solid var(--color-accent-emphasis);
    outline-offset: -2px;
}

.none {
    padding: 8px 10px;
}

.form {
    display: flex;
    flex-direction: column;
    gap: 12px;
    flex: 1;
    min-width: 0;
    overflow-y: auto;
}

.named {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.row {
    display: flex;
    gap: 12px;
}

.grow {
    flex: 1;
    min-width: 0;
}

.check {
    display: flex;
    align-items: center;
    gap: 8px;
}

.footer {
    display: flex;
    align-items: center;
    gap: 8px;
}

/* Removing is red at its outline, never the default. */
.risky:not(:disabled) {
    border-color: var(--color-danger-emphasis);
    color: var(--color-danger-fg);
}
</style>
