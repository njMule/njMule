<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { TooltipContent, TooltipPortal, TooltipRoot, TooltipTrigger } from 'reka-ui';

// A tooltip after a moment under the pointer, as the desktop app's, or on focus from the keyboard (not on a focus a popup
// gives its first control); nothing when the text is empty.
// A menu's trigger inside takes the tooltip's anchor, not its menu's (Reka anchors to the nearest popper): that menu's
// content needs its trigger as `reference`, or it never shows.
defineProps<{ text?: string }>();
</script>

<template>
    <TooltipRoot v-if="text" ignore-non-keyboard-focus>
        <TooltipTrigger as-child>
            <slot />
        </TooltipTrigger>
        <TooltipPortal>
            <TooltipContent class="tooltip" :side-offset="6">{{ text }}</TooltipContent>
        </TooltipPortal>
    </TooltipRoot>
    <slot v-else />
</template>
