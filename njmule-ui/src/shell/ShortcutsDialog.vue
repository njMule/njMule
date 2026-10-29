<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { t } from '../i18n';
import { PLACES, shortcut, withShortcutKey } from '../places';
import Icon from '../ui/Icon.vue';

// The keys that do something, with what they do.
const open = defineModel<boolean>('open', { required: true });
const transfers = `${t('IDS_EM_TRANS')}: `;
const rows = [
    ...PLACES.map((p, i) => [shortcut(i), t(p.title)]),
    [withShortcutKey('F'), transfers + t('nj.transfers.filter')],
    ['Alt+Enter', transfers + t('IDS_DETAILS')],
    [withShortcutKey('K'), t('nj.palette.prompt')],
    [withShortcutKey('B'), `${t('nj.sidebar.collapse')} / ${t('nj.sidebar.expand')}`],
    ['F1', t('nj.shortcuts.title')],
];
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog shortcuts" :aria-describedby="undefined">
                <DialogTitle class="dialog-title display">{{ t('nj.shortcuts.title') }}</DialogTitle>
                <dl>
                    <template v-for="[keys, what] in rows" :key="keys">
                        <dt>
                            <span class="shortcut-key">{{ keys }}</span>
                        </dt>
                        <dd>{{ what }}</dd>
                    </template>
                </dl>
                <div class="dialog-buttons">
                    <DialogClose class="button accent"><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.shortcuts {
    width: min(480px, calc(100vw - 32px));
}

dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 8px 16px;
    margin: 0;
}

dd {
    margin: 0;
}
</style>
