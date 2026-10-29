<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogOverlay,
    AlertDialogPortal,
    AlertDialogRoot,
    AlertDialogTitle,
} from 'reka-ui';
import { computed } from 'vue';
import { question } from '../ask';

// The question asked, over the window; Escape or the safe answer keeps things as they are.
const open = computed({
    get: () => question.value !== null,
    set: (now) => {
        if (!now) {
            question.value?.answer(false);
        }
    },
});
</script>

<template>
    <AlertDialogRoot v-model:open="open">
        <AlertDialogPortal>
            <AlertDialogOverlay class="scrim" />
            <AlertDialogContent v-if="question" class="dialog ask">
                <AlertDialogTitle class="dialog-title display">{{ question.title }}</AlertDialogTitle>
                <AlertDialogDescription>{{ question.text }}</AlertDialogDescription>
                <ul v-if="question.names.length > 0" class="names">
                    <li v-for="name in question.names" :key="name" class="ellipsis" dir="auto">{{ name }}</li>
                </ul>
                <div class="dialog-buttons">
                    <AlertDialogAction class="button risky" @click="question.answer(true)">{{
                        question.risky
                    }}</AlertDialogAction>
                    <AlertDialogCancel class="button accent" @click="question.answer(false)">{{
                        question.safe
                    }}</AlertDialogCancel>
                </div>
            </AlertDialogContent>
        </AlertDialogPortal>
    </AlertDialogRoot>
</template>

<style scoped>
.ask {
    width: min(480px, calc(100vw - 32px));
}

.names {
    margin: 0;
    padding: 8px 12px;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    list-style: none;
}

/* The risky answer is red at its outline, never the default. */
.risky {
    border-color: var(--color-danger-emphasis);
    color: var(--color-danger-fg);
}
</style>
