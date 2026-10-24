<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { ICONS, type IconName } from '../icons';

const props = defineProps<{ name: IconName; label?: string }>();
const icon = computed(() => ICONS[props.name] as { path: string; kind?: string });
</script>

<template>
    <svg
        class="nj-icon"
        :class="{ filled: icon.kind === 'filled', directional: icon.kind === 'directional' }"
        viewBox="0 0 24 24"
        :role="label ? 'img' : undefined"
        :aria-label="label"
        :aria-hidden="label ? undefined : 'true'"
    >
        <path :d="icon.path" />
    </svg>
</template>
