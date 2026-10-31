<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { IconName } from '../icons';
import Icon from './Icon.vue';

// What an empty list shows: an icon, what is missing and how to get it, and the buttons that do it.
defineProps<{ icon: IconName; text: string }>();
</script>

<template>
    <div class="empty-state">
        <Icon class="empty-icon" :name="icon" />
        <p class="empty-text">{{ text }}</p>
        <div v-if="$slots.default" class="empty-actions"><slot /></div>
    </div>
</template>

<style scoped>
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;
    padding: 16px;
}

.empty-icon {
    --nj-icon-size: 40px;
    --nj-icon-color: var(--color-fg-subtle);
}

/* Centred on its own lines too, which stop short of a long reading width. */
.empty-text {
    max-width: 420px;
    margin: 0;
    text-align: center;
    font-size: 1.05em;
}

.empty-actions {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
}
</style>
