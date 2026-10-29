<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { t } from '../i18n';
import { PLACES, type Place } from '../places';
import Icon from '../ui/Icon.vue';

// A place the spike does not bring over yet.
const props = defineProps<{ place: Place }>();
const info = computed(() => PLACES.find((p) => p.id === props.place)!);
</script>

<template>
    <div class="empty-state">
        <Icon :name="info.icon" />
        <p>{{ t(info.title) }}</p>
    </div>
</template>

<style scoped>
.empty-state {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    color: var(--color-fg-muted);
    --nj-icon-size: 48px;
}
</style>
