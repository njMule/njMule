<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { ICONS, type Icon, type IconName } from '../icons';

// One of the desktop app's line icons; decorative unless a label says what it means.
const props = defineProps<{ name: IconName; label?: string }>();
const icon = computed<Icon>(() => ICONS[props.name]);
</script>

<template>
    <svg
        class="nj-icon"
        :class="icon.kind"
        viewBox="0 0 24 24"
        :role="label ? 'img' : undefined"
        :aria-label="label"
        :aria-hidden="label ? undefined : 'true'"
    >
        <path :d="icon.path" />
    </svg>
</template>
