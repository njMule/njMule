<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { type StatsEntry, treeKey, treeRows } from '../../stats';
import Icon from '../../ui/Icon.vue';

// The statistics as a tree: a line is chosen by a click or the arrows, a branch opens or closes by its arrow, a double
// click or the right and left keys. The branches keep their state while the figures change, as they are kept by their
// place; the main ones are open the first time.
const props = defineProps<{ entries: StatsEntry[]; label: string }>();

const opened = ref<Set<string>>(new Set());
const chosen = ref('');
const box = ref<HTMLElement | null>(null);

const rows = computed(() => treeRows(props.entries, opened.value));

let first = true;
watch(
    () => props.entries.length,
    (count) => {
        if (first && count > 0) {
            first = false;
            opened.value = new Set(props.entries.map((_, i) => String(i)));
        }
    },
    { immediate: true },
);

function toggle(key: string): void {
    const now = new Set(opened.value);
    if (!now.delete(key)) {
        now.add(key);
    }
    opened.value = now;
}

function key(e: KeyboardEvent): void {
    const move = treeKey(rows.value, chosen.value, e.key);
    if (move === null) {
        return;
    }
    e.preventDefault();
    chosen.value = move.chosen;
    if (move.toggle !== undefined) {
        toggle(move.toggle);
    }
    void nextTick(() => box.value?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }));
}

function id(key: string): string {
    return `stats-${key.replaceAll('.', '-')}`;
}
</script>

<template>
    <div
        ref="box"
        class="stats-tree"
        role="tree"
        tabindex="0"
        :aria-label="label"
        :aria-activedescendant="chosen ? id(chosen) : undefined"
        @keydown="key"
    >
        <div
            v-for="row in rows"
            :id="id(row.key)"
            :key="row.key"
            class="tree-row"
            role="treeitem"
            :aria-level="row.level"
            :aria-expanded="row.branch ? row.open : undefined"
            :aria-selected="row.key === chosen"
            :style="{ paddingInlineStart: `${6 + (row.level - 1) * 18}px` }"
            @click="chosen = row.key"
            @dblclick="row.branch && toggle(row.key)"
        >
            <span
                class="disclosure"
                :class="{ open: row.open }"
                aria-hidden="true"
                @click.stop="
                    chosen = row.key;
                    toggle(row.key);
                "
                ><Icon v-if="row.branch" name="CHEVRON_RIGHT"
            /></span>
            <span class="tree-text" dir="auto">{{ row.text }}</span>
        </div>
    </div>
</template>

<style scoped>
/* The figures read as a list: rows as dense as the logs', never selected as text. */
.stats-tree {
    height: 380px;
    overflow-y: auto;
    padding: 2px 0;
    border-top: 1px solid var(--color-border-muted);
    outline: none;
    user-select: none;
}

.stats-tree:focus-visible {
    box-shadow: inset 0 0 0 2px var(--color-accent-emphasis);
}

.tree-row {
    display: flex;
    align-items: center;
    gap: 2px;
    min-height: 1.9em;
    padding-inline-end: 6px;
    cursor: default;
}

.tree-row[aria-selected='true'] {
    background: var(--color-accent-muted);
}

.disclosure {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex: none;
    width: 18px;
    height: 18px;
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-fg-muted);
}

.disclosure.open .nj-icon {
    transform: rotate(90deg);
}

.tree-text {
    white-space: nowrap;
}
</style>
