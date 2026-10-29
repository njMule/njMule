<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, nextTick, ref, watch } from 'vue';
import { t } from '../i18n';
import Icon from '../ui/Icon.vue';

// A command, a search of the network or eD2K links, typed in one box: the commands that hold the words, then what the
// text is itself.

export interface Command {
    title: string;
    keys: string;
    run: () => void;
}

const props = defineProps<{ commands: Command[]; typed: (text: string) => Command[] }>();
const open = defineModel<boolean>('open', { required: true });
const text = defineModel<string>('text', { required: true });
const chosen = ref(0);
const input = ref<HTMLInputElement | null>(null);

const shown = computed(() => {
    const words = text.value
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    const kept = props.commands.filter((c) => words.every((w) => c.title.toLowerCase().includes(w)));
    return text.value.trim() === '' ? kept : [...kept, ...props.typed(text.value.trim())];
});

watch(open, async (now) => {
    if (now) {
        chosen.value = 0;
        await nextTick();
        input.value?.focus();
        // A letter typed on the top bar's field starts the text: the caret goes after it.
        input.value?.setSelectionRange(text.value.length, text.value.length);
    }
});

watch(text, () => (chosen.value = 0));

function run(command: Command | undefined): void {
    if (command !== undefined) {
        open.value = false;
        command.run();
    }
}

function key(e: KeyboardEvent): void {
    if (e.key === 'ArrowDown') {
        chosen.value = Math.min(shown.value.length - 1, chosen.value + 1);
        e.preventDefault();
    } else if (e.key === 'ArrowUp') {
        chosen.value = Math.max(0, chosen.value - 1);
        e.preventDefault();
    } else if (e.key === 'Enter') {
        run(shown.value[chosen.value]);
        e.preventDefault();
    }
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="command-palette" :aria-describedby="undefined">
                <DialogTitle class="visually-hidden">{{ t('nj.palette.prompt') }}</DialogTitle>
                <div class="field">
                    <Icon name="SEARCH" />
                    <input ref="input" v-model="text" :placeholder="t('nj.palette.prompt')" @keydown="key" />
                </div>
                <ul class="commands" role="listbox">
                    <li
                        v-for="(command, i) in shown"
                        :key="command.title"
                        class="menu-item"
                        role="option"
                        :aria-selected="i === chosen"
                        :data-highlighted="i === chosen ? '' : undefined"
                        @mousemove="chosen = i"
                        @click="run(command)"
                    >
                        <span class="ellipsis">{{ command.title }}</span>
                        <span v-if="command.keys" class="menu-end shortcut-key">{{ command.keys }}</span>
                    </li>
                </ul>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.command-palette {
    position: fixed;
    top: 72px;
    left: 50%;
    transform: translateX(-50%);
    width: min(560px, calc(100vw - 32px));
    padding: 10px;
    border: 1px solid var(--color-border-default);
    border-radius: 8px;
    background: var(--color-bg-overlay);
    box-shadow: 0 8px 32px var(--color-shadow-default);
    z-index: 16;
}

.commands {
    max-height: 360px;
    margin: 8px 0 0;
    padding: 0;
    overflow-y: auto;
    list-style: none;
}

.visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
}
</style>
