<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
// An on and off switch, the thumb at the end side while on.
defineProps<{ label?: string; disabled?: boolean }>();
const on = defineModel<boolean>({ required: true });
</script>

<template>
    <button
        type="button"
        class="switch"
        role="switch"
        :aria-checked="on"
        :aria-label="label"
        :disabled="disabled"
        @click="on = !on"
    >
        <span class="thumb" />
    </button>
</template>

<style scoped>
.switch {
    position: relative;
    flex: none;
    width: 44px;
    height: 22px;
    padding: 0;
    border: 1px solid var(--color-border-default);
    border-radius: 3px;
    background: var(--color-bg-subtle);
    cursor: pointer;
}

.switch[aria-checked='true'] {
    border-color: var(--color-accent-emphasis);
    background: var(--color-accent-emphasis);
}

.switch:disabled {
    opacity: 0.5;
    cursor: default;
}

.switch:focus-visible {
    outline: 2px solid var(--color-accent-emphasis);
    outline-offset: 2px;
}

.thumb {
    position: absolute;
    top: 2px;
    inset-inline-start: 2px;
    width: 16px;
    height: 16px;
    border-radius: 2px;
    background: var(--color-bg-default);
    box-shadow: 0 0 0 1px var(--color-border-muted);
    transition: inset-inline-start 0.12s;
}

.switch[aria-checked='true'] .thumb {
    inset-inline-start: 24px;
    box-shadow: none;
}
</style>
