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
import { question } from '../ask';
import Icon from '../ui/Icon.vue';

// The question asked, over the window; Escape or the safe answer keeps things as they are.
const field = ref('');
const check = ref(false);

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

function yes(): void {
    if (question.value?.field && field.value.trim() === '') {
        return;
    }
    question.value?.answer({ field: field.value.trim(), check: check.value });
}
</script>

<template>
    <AlertDialogRoot v-model:open="open">
        <AlertDialogPortal>
            <AlertDialogOverlay class="scrim" />
            <AlertDialogContent v-if="question" class="dialog ask" @keydown.enter.prevent="!question.risky && yes()">
                <AlertDialogTitle class="dialog-title display">
                    <Icon v-if="question.warning" class="warning" name="WARNING" />{{ question.title }}
                </AlertDialogTitle>
                <AlertDialogDescription v-if="question.text">{{ question.text }}</AlertDialogDescription>
                <ul v-if="question.names && question.names.length > 0" class="names">
                    <li v-for="name in question.names" :key="name" class="ellipsis" dir="auto">{{ name }}</li>
                </ul>
                <label v-if="question.field" class="ask-field">
                    <span class="muted">{{ question.field.label }}</span>
                    <input v-model="field" class="field" :placeholder="question.field.hint" autofocus />
                </label>
                <label v-if="question.check" class="ask-check">
                    <input v-model="check" type="checkbox" />{{ question.check.label }}
                </label>
                <p v-if="question.note" class="note"><Icon name="SHIELD" />{{ question.note }}</p>
                <div class="dialog-buttons">
                    <template v-if="question.risky">
                        <button class="button risky" @click="yes">{{ question.yes }}</button>
                        <button class="button accent" @click="question.answer(null)">{{ question.no }}</button>
                    </template>
                    <template v-else>
                        <button class="button" @click="question.answer(null)">{{ question.no }}</button>
                        <button class="button accent" @click="yes">{{ question.yes }}</button>
                    </template>
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
    padding: 8px 12px;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    list-style: none;
}

.ask-field {
    display: flex;
    flex-direction: column;
    gap: 4px;
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
    color: var(--color-fg-muted);
}

/* The risky answer is red at its outline, never the default. */
.risky {
    border-color: var(--color-danger-emphasis);
    color: var(--color-danger-fg);
}
</style>
