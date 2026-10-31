<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogTitle } from 'reka-ui';
import { t } from '../i18n';
import Icon from './Icon.vue';

// The head of a dialog: the title in bold, a cross that closes it.
// It goes first inside a DialogContent; the slot holds a title with more than words.
defineProps<{ title?: string }>();
</script>

<template>
    <div class="dialog-header">
        <DialogTitle class="dialog-title"
            ><slot>{{ title }}</slot></DialogTitle
        >
        <DialogClose class="button flat icon-only dialog-close" :aria-label="t('IDS_FD_CLOSE')"
            ><Icon name="CLOSE"
        /></DialogClose>
    </div>
</template>

<style scoped>
.dialog-header {
    display: flex;
    align-items: center;
    gap: 10px;
}

.dialog-header .dialog-title {
    flex: 1;
    min-width: 0;
}

.dialog-close {
    flex: none;
    width: 30px;
    height: 30px;
}
</style>
