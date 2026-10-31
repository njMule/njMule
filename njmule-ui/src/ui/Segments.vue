<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts" generic="V extends string">
// One of a few lists or views, chosen by joined buttons that tell how many rows each has.
defineProps<{ items: { value: V; title: string; count?: number }[]; label: string }>();
const value = defineModel<V>({ required: true });
</script>

<template>
    <div class="segments" role="tablist" :aria-label="label">
        <button
            v-for="item in items"
            :key="item.value"
            type="button"
            class="segment"
            role="tab"
            :aria-selected="value === item.value"
            @click="value = item.value"
        >
            {{ item.title }}<span v-if="item.count !== undefined" class="segment-count">{{ item.count }}</span>
        </button>
    </div>
</template>

<style scoped>
.segments {
    display: inline-flex;
    flex: none;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    overflow: hidden;
}

.segment {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    height: 34px;
    padding: 0 12px;
    border: 0;
    background: var(--color-bg-default);
    white-space: nowrap;
    cursor: pointer;
}

.segment:hover {
    background: var(--color-bg-subtle);
}

/* A chosen segment is a pastel fill inside an accent outline, its text in the usual colour. */
.segment[aria-selected='true'] {
    background: var(--color-accent-muted);
    box-shadow: inset 0 0 0 1px var(--color-accent-emphasis);
}

.segment-count {
    color: var(--color-fg-muted);
    font-size: 0.9em;
}
</style>
