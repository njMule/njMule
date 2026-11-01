<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, nextTick, ref, watch } from 'vue';
import { t } from '../i18n';
import { type Command, matching, step } from '../palette';
import Icon from '../ui/Icon.vue';

// A command, a search of the network or eD2K links, typed in one box over the top bar's field, which it stands for:
// the commands that hold the words, then what the text is itself. Enter runs the command chosen, Esc closes.

const props = defineProps<{ commands: Command[]; typed: (text: string) => Command[] }>();
const open = defineModel<boolean>('open', { required: true });
const text = defineModel<string>('text', { required: true });
const chosen = ref(0);
const input = ref<HTMLInputElement | null>(null);
const list = ref<HTMLElement | null>(null);

const shown = computed(() => matching(props.commands, text.value, props.typed));

watch(open, async (now) => {
    if (now) {
        chosen.value = step(shown.value, 0, 0);
        await nextTick();
        input.value?.focus();
        // A letter typed on the top bar's field starts the text: the caret goes after it.
        input.value?.setSelectionRange(text.value.length, text.value.length);
    }
});

// The commands' words follow the networks every second: only a new text chooses the first again.
watch(text, () => (chosen.value = step(shown.value, 0, 0)));

function run(command: Command | undefined): void {
    if (command !== undefined && !command.disabled) {
        open.value = false;
        command.run();
    }
}

// The arrows go round past either end.
function move(by: number): void {
    chosen.value = step(shown.value, chosen.value, by);
    void nextTick(() => list.value?.children[chosen.value]?.scrollIntoView({ block: 'nearest' }));
}

function key(e: KeyboardEvent): void {
    if (e.key === 'ArrowDown') {
        move(1);
    } else if (e.key === 'ArrowUp') {
        move(-1);
    } else if (e.key === 'Enter') {
        run(shown.value[chosen.value]);
    } else {
        return;
    }
    e.preventDefault();
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
                    <input
                        ref="input"
                        v-model="text"
                        autocomplete="off"
                        :placeholder="t('nj.palette.prompt')"
                        @keydown="key"
                    />
                </div>
                <ul ref="list" class="commands" role="listbox">
                    <li
                        v-for="(command, i) in shown"
                        :key="command.title"
                        class="menu-item"
                        role="option"
                        :aria-selected="i === chosen"
                        :aria-disabled="command.disabled ? 'true' : undefined"
                        :data-highlighted="i === chosen ? '' : undefined"
                        :data-disabled="command.disabled ? '' : undefined"
                        @click="run(command)"
                    >
                        <span class="ellipsis">{{ command.title }}</span>
                        <span v-if="command.keys" class="menu-end keys">{{ command.keys }}</span>
                    </li>
                </ul>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.command-palette {
    position: fixed;
    top: 10px;
    left: 50%;
    transform: translateX(-50%);
    width: min(560px, calc(100vw - 32px));
    padding: 10px;
    border: 1px solid var(--color-border-default);
    border-radius: 8px;
    background: var(--color-bg-default);
    box-shadow: 0 8px 32px var(--color-shadow-default);
    z-index: 16;
}

/* As tall as what it lists, up to nine rows. */
.commands {
    min-height: 34px;
    max-height: calc(9 * 30px + 4px);
    margin: 6px 0 0;
    padding: 2px 0;
    overflow-y: auto;
    list-style: none;
}

/* The chosen command looks like a list's chosen row. */
.menu-item[data-highlighted] {
    background: var(--color-accent-muted);
}

.menu-item:not([data-highlighted]):not([data-disabled]):hover {
    background: var(--color-bg-subtle);
}

.visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
}
</style>
