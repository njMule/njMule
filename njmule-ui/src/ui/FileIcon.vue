<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { systemIconOf } from '../file-icons';
import type { IconName } from '../icons';
import { kindIcon, kindOf } from '../transfers';
import Icon from './Icon.vue';

// The icon beside a file's name: the system's for its kind in the desktop window, as the desktop app's lists show
// it, or else the app's own icon of the kind.
const props = defineProps<{ name: string; kind?: IconName }>();
const program = computed(() => systemIconOf(props.name));
</script>

<template>
    <img v-if="program" class="file-kind program" :src="program" alt="" width="16" height="16" />
    <Icon v-else class="file-kind" :name="kind ?? kindIcon(kindOf(name))" />
</template>

<style scoped>
.program {
    flex: none;
    width: 16px;
    height: 16px;
}
</style>
