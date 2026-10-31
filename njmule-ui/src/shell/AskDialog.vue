<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogOverlay,
    AlertDialogPortal,
    AlertDialogRoot,
    AlertDialogTitle,
} from 'reka-ui';
import { computed, ref, watch } from 'vue';
import { answerOrder, onWindows, question } from '../ask';
import Icon from '../ui/Icon.vue';

// The question asked, over the window; Escape or the safe answer keeps things as they are. Enter takes the default
// answer, which is the safe one of a risky question.
const field = ref('');
const check = ref(false);
const input = ref<HTMLInputElement | null>(null);
const buttons = ref<HTMLElement | null>(null);
const ORDER = answerOrder(onWindows());

watch(question, (q) => {
    field.value = q?.field?.value ?? '';
    check.value = q?.check?.value ?? false;
});

const open = computed({
    get: () => question.value !== null,
    set: (now) => {
        if (!now) {
            question.value?.answer(null);
        }
    },
});

const names = computed(() => question.value?.names ?? []);

// An empty field is an answer too: the caller leaves things as they are then.
function yes(): void {
    question.value?.answer({ field: field.value.trim(), check: check.value });
}

function byDefault(e: KeyboardEvent): void {
    if (e.isComposing) {
        return;
    }
    e.preventDefault();
    if (question.value?.risky) {
        question.value.answer(null);
    } else {
        yes();
    }
}

function isDefault(answer: 'yes' | 'no'): boolean {
    return (answer === 'no') === (question.value?.risky === true);
}

// A field opens with its text chosen, to be typed over; otherwise the default answer has the focus.
function opened(e: Event): void {
    e.preventDefault();
    if (input.value) {
        input.value.focus();
        input.value.select();
    } else {
        buttons.value?.querySelector<HTMLButtonElement>('[data-default]')?.focus();
    }
}
</script>

<template>
    <AlertDialogRoot v-model:open="open">
        <AlertDialogPortal>
            <AlertDialogOverlay class="scrim" />
            <AlertDialogContent v-if="question" class="dialog ask" @keydown.enter="byDefault" @open-auto-focus="opened">
                <AlertDialogTitle class="dialog-title display">
                    <Icon v-if="question.warning" class="warning" name="WARNING" />{{ question.title }}
                </AlertDialogTitle>
                <ul v-if="question.namesFirst && names.length > 0" class="names">
                    <li v-for="name in names" :key="name" class="ellipsis" dir="auto">{{ name }}</li>
                </ul>
                <AlertDialogDescription v-if="question.text">{{ question.text }}</AlertDialogDescription>
                <ul v-if="!question.namesFirst && names.length > 0" class="names">
                    <li v-for="name in names" :key="name" class="ellipsis" dir="auto">{{ name }}</li>
                </ul>
                <label v-if="question.field" class="ask-field">
                    <span class="label">{{ question.field.label }}</span>
                    <input ref="input" v-model="field" class="field" />
                    <span v-if="question.field.hint" class="hint">{{ question.field.hint }}</span>
                </label>
                <label v-if="question.check" class="ask-check">
                    <input v-model="check" type="checkbox" />{{ question.check.label }}
                </label>
                <p v-if="question.note" class="note"><Icon name="SHIELD" />{{ question.note }}</p>
                <div ref="buttons" class="dialog-buttons">
                    <button
                        v-for="answer in ORDER"
                        :key="answer"
                        class="button"
                        :class="answer === 'yes' ? (question.risky ? 'risky' : 'accent') : question.risky ? 'safe' : ''"
                        :data-default="isDefault(answer) || undefined"
                        @click="answer === 'yes' ? yes() : question.answer(null)"
                    >
                        {{ answer === 'yes' ? question.yes : question.no }}
                    </button>
                </div>
            </AlertDialogContent>
        </AlertDialogPortal>
    </AlertDialogRoot>
</template>

<style scoped>
.ask {
    width: min(480px, calc(100vw - 32px));
}

.dialog-title {
    display: flex;
    align-items: center;
    gap: 8px;
}

.warning {
    --nj-icon-size: 22px;
    --nj-icon-color: var(--color-warning-emphasis);
}

.names {
    margin: 0;
    padding: 8px 10px;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    list-style: none;
}

.ask-field {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.label {
    font-size: 0.92em;
    font-weight: bold;
    color: var(--color-fg-muted);
}

.hint {
    font-size: 0.92em;
    color: var(--color-fg-muted);
}

.ask-check {
    display: flex;
    align-items: center;
    gap: 8px;
}

.note {
    display: flex;
    gap: 8px;
    margin: 0;
    font-size: 0.92em;
    color: var(--color-fg-muted);
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-fg-muted);
}

/* The risky answer is red at its outline, never the default. */
.risky {
    border-color: var(--color-danger-emphasis);
    color: var(--color-danger-fg);
}

/* The safe answer of a risky question is the default, ringed rather than filled. */
.safe {
    border-color: var(--color-accent-fg);
}
</style>
