<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import Tip from '../../ui/Tip.vue';
import { keepTyped } from './inputs';

// A text field or area that holds one kind of value: typing and pasting keep only the characters the value can have and
// never make it longer than it may be; a wrong value marks the field, and the tooltip says what it takes. It applies
// when it is left, or on Enter in a field.

defineOptions({ inheritAttrs: false });

const props = defineProps<{
    modelValue: string;
    allowed?: (c: string) => boolean;
    max?: number;
    /** What the field takes, in words. */
    tip?: string;
    invalid?: boolean;
    /** The lines of a text area; a one-line field without. */
    lines?: number;
}>();
const emit = defineEmits<{ 'update:modelValue': [text: string]; commit: [] }>();

function input(e: Event): void {
    const field = e.target as HTMLInputElement | HTMLTextAreaElement;
    const next = field.value;
    const kept =
        props.allowed === undefined && props.max === undefined
            ? next
            : keepTyped(props.modelValue, next, props.allowed ?? (() => true), props.max ?? Number.MAX_SAFE_INTEGER);
    if (kept !== next) {
        const caret = Math.max(0, (field.selectionStart ?? next.length) - (next.length - kept.length));
        field.value = kept;
        field.setSelectionRange(caret, caret);
    }
    emit('update:modelValue', kept);
}
</script>

<template>
    <Tip :text="tip">
        <textarea
            v-if="lines !== undefined"
            v-bind="$attrs"
            class="field area"
            :rows="lines"
            :value="modelValue"
            :aria-invalid="invalid"
            @input="input"
            @blur="emit('commit')"
        />
        <input
            v-else
            v-bind="$attrs"
            class="field"
            :value="modelValue"
            :aria-invalid="invalid"
            @input="input"
            @blur="emit('commit')"
            @keydown.enter="emit('commit')"
        />
    </Tip>
</template>

<style scoped>
.area {
    height: auto;
    padding: 6px 8px;
    resize: vertical;
}

/* A wrong value: the danger state. */
.field[aria-invalid='true'] {
    border-color: var(--color-danger-emphasis);
}

.field:disabled {
    background: var(--color-bg-subtle);
    color: var(--color-fg-muted);
}
</style>
