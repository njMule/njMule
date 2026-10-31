<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts" generic="V extends string">
import { moveAlong } from './toggles';

// Rounded chips that narrow a list, each with how many rows it keeps; one is chosen at a time.
defineProps<{ items: { value: V; title: string; count: number }[]; label: string }>();
const value = defineModel<V>({ required: true });
</script>

<template>
    <div class="filter-chips" role="radiogroup" :aria-label="label" @keydown="moveAlong">
        <button
            v-for="item in items"
            :key="item.value"
            type="button"
            class="filter-chip"
            role="radio"
            :aria-checked="value === item.value"
            @click="value = item.value"
        >
            {{ item.title }}<span class="chip-count">{{ item.count }}</span>
        </button>
    </div>
</template>

<style scoped>
.filter-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 8px;
    min-width: 0;
}

.filter-chip {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    padding: 2px 9px;
    border: 1px solid var(--color-border-default);
    border-radius: 14px;
    background: var(--color-bg-default);
    font-size: 0.92em;
    white-space: nowrap;
    cursor: pointer;
}

.filter-chip:hover {
    background: var(--color-bg-subtle);
}

.filter-chip[aria-checked='true'] {
    border-color: transparent;
    background: var(--color-accent-muted);
}

.chip-count {
    color: var(--color-fg-muted);
    font-size: 0.9em;
}
</style>
