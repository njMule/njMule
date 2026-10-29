<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { TooltipContent, TooltipPortal, TooltipRoot, TooltipTrigger } from 'reka-ui';

// A tooltip after a moment under the pointer, as the desktop app's; nothing when the text is empty.
defineProps<{ text?: string }>();
</script>

<template>
    <TooltipRoot v-if="text">
        <TooltipTrigger as-child>
            <slot />
        </TooltipTrigger>
        <TooltipPortal>
            <TooltipContent class="tooltip" :side-offset="6">{{ text }}</TooltipContent>
        </TooltipPortal>
    </TooltipRoot>
    <slot v-else />
</template>
