<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogOverlay,
    DialogPortal,
    DialogRoot,
    DialogTitle,
} from 'reka-ui';
import { ref, watch } from 'vue';
import { answer, api } from '../../api/client';
import { t } from '../../i18n';
import { problem } from '../../toasts';
import { webAddresses } from './inputs';

// The addresses of the server.met lists the update at start takes, one per line, as the desktop app asks for them.

const open = defineModel<boolean>('open', { required: true });
const text = ref('');

watch(open, async (now) => {
    if (!now) {
        return;
    }
    text.value = '';
    try {
        text.value = (await answer(api().GET('/servers/lists'))).addresses.join('\n');
    } catch (e) {
        problem((e as Error).message);
    }
});

// Only the http and https addresses are kept; any other line is left out.
async function save(): Promise<void> {
    open.value = false;
    try {
        await answer(api().PUT('/servers/lists', { body: { addresses: webAddresses(text.value) } }));
    } catch (e) {
        problem((e as Error).message);
    }
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog">
                <DialogTitle class="dialog-title display">{{ t('nj.settings.addressesTitle') }}</DialogTitle>
                <DialogDescription>{{ t('nj.settings.serverListAddresses') }}</DialogDescription>
                <textarea
                    v-model="text"
                    class="addresses"
                    dir="ltr"
                    rows="8"
                    placeholder="https://…/server.met"
                    :aria-label="t('nj.settings.addressesTitle')"
                />
                <div class="dialog-buttons">
                    <DialogClose class="button">{{ t('IDS_CANCEL') }}</DialogClose>
                    <button class="button accent" @click="save">{{ t('IDS_SAVE') }}</button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.addresses {
    width: 100%;
    padding: 8px;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
    color: inherit;
    font: inherit;
    resize: vertical;
}
</style>
