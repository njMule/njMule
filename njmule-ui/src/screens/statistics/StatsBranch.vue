<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { StatsEntry } from '../../stats';
import Icon from '../../ui/Icon.vue';

// Lines of the statistics tree, a branch open or closed by its line; the branches keep their state while the figures
// change, as they are kept by their place.
defineProps<{ entries: StatsEntry[]; open?: boolean }>();
</script>

<template>
    <ul class="branch">
        <li v-for="(e, i) in entries" :key="i">
            <details v-if="e.children.length > 0" :open="open">
                <summary><Icon name="CHEVRON_RIGHT" class="chevron" />{{ e.text }}</summary>
                <StatsBranch :entries="e.children" />
            </details>
            <span v-else class="leaf" dir="auto">{{ e.text }}</span>
        </li>
    </ul>
</template>

<style scoped>
.branch {
    margin: 0;
    padding: 0;
    list-style: none;
}

.branch .branch {
    padding-inline-start: 20px;
}

/* The figures read as a list: rows as dense as the logs'. */
summary,
.leaf {
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 1.9em;
    padding: 0 6px;
}

summary {
    list-style: none;
    cursor: pointer;
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-fg-muted);
}

summary::-webkit-details-marker {
    display: none;
}

.leaf {
    padding-inline-start: 24px;
}

details[open] > summary .chevron {
    transform: rotate(90deg);
}

details:not([open]) > summary .chevron:dir(rtl) {
    transform: scaleX(-1);
}
</style>
