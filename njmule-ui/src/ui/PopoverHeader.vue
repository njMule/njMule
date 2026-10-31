<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { PopoverArrow, PopoverClose } from 'reka-ui';
import { t } from '../i18n';
import Icon from './Icon.vue';

// The head of a popover with a title, as the desktop app draws one: the arrow toward what opened it, the title in the
// middle and a cross that closes it. It goes first inside a PopoverContent.
defineProps<{ title: string }>();
</script>

<template>
    <PopoverArrow class="popover-arrow" :width="24" :height="12" />
    <div class="popover-header">
        <span class="popover-title">{{ title }}</span>
        <PopoverClose class="popover-close" :aria-label="t('IDS_FD_CLOSE')"><Icon name="CLOSE" /></PopoverClose>
    </div>
</template>

<style scoped>
/* The title centred over the whole width, the cross at the end. */
.popover-header {
    display: grid;
    grid-template-columns: 1fr auto 1fr;
    align-items: center;
    padding-bottom: 1em;
}

.popover-title {
    grid-column: 2;
    font-size: 1.25em;
    text-align: center;
    white-space: nowrap;
}

.popover-close {
    grid-column: 3;
    justify-self: end;
    display: inline-flex;
    padding: 2px;
    border: none;
    border-radius: 4px;
    background: none;
    cursor: default;
    --nj-icon-size: 14px;
    --nj-icon-color: var(--color-fg-default);
}

.popover-close:hover {
    background: var(--color-bg-subtle);
}

.popover-close:focus-visible {
    outline: 2px solid var(--color-accent-emphasis);
}
</style>
