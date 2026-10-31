<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, h, ref, shallowRef, watch } from 'vue';
import { answer, api } from '../api/client';
import { size } from '../format';
import { t } from '../i18n';
import { addLinks } from '../transfers-actions';
import { actsFirst, byName } from '../transfers';
import DataTable from '../ui/DataTable.vue';
import Icon from '../ui/Icon.vue';

// The files another client shares, as it lists them when asked; the chosen ones can be downloaded.

interface ClientFile {
    hash: string;
    name: string;
    size: number;
    type?: string;
    link: string;
}

/** The client asked, at its address (host:port), and the name it is shown by. */
const props = defineProps<{ client: { address: string; name: string } | null }>();
const open = defineModel<boolean>('open', { required: true });

const files = shallowRef<ClientFile[]>([]);
const state = ref('');
const selected = ref<string[]>([]);
const first = actsFirst();

watch(open, async (now) => {
    const client = props.client;
    if (!now || client === null) {
        return;
    }
    files.value = [];
    selected.value = [];
    state.value = t('nj.browse.asking');
    try {
        const found = await answer(
            api().POST('/clients/{address}/files', { params: { path: { address: client.address } } }),
        );
        files.value = found;
        state.value = found.length === 0 ? t('nj.browse.none') : t('nj.browse.count', found.length);
    } catch (e) {
        state.value = t('nj.browse.failed', (e as Error).message);
    }
});

function byText(a: any, b: any, id: string): number {
    return byName(String(a.getValue(id)), String(b.getValue(id)));
}

const columns: ColumnDef<any, ClientFile, any>[] = [
    {
        id: 'name',
        header: t('IDS_DL_FILENAME'),
        accessorFn: (f: ClientFile) => f.name,
        size: 380,
        sortFn: byText,
        cell: ({ getValue }: any) => h('span', { class: 'ellipsis', dir: 'auto' }, getValue()),
    },
    {
        id: 'size',
        header: t('IDS_DL_SIZE'),
        accessorFn: (f: ClientFile) => f.size,
        size: 100,
        cell: ({ getValue }: any) => h('span', { dir: 'ltr' }, size(getValue())),
    },
    {
        id: 'type',
        header: t('IDS_TYPE'),
        accessorFn: (f: ClientFile) => f.type ?? '',
        size: 90,
        sortFn: byText,
        cell: ({ getValue }: any) => h('span', { dir: 'ltr' }, getValue()),
    },
];

const chosen = computed(() => files.value.filter((f) => selected.value.includes(f.hash)));

// The window stays open: more files can be chosen.
async function download(): Promise<void> {
    await addLinks(chosen.value.map((f) => f.link).join('\n'), undefined, undefined, false);
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog client-files" :aria-describedby="undefined">
                <DialogTitle class="dialog-title display">{{
                    `${t('IDS_VIEWFILES')}: ${client?.name ?? ''}`
                }}</DialogTitle>
                <span class="muted">{{ state }}</span>
                <div class="table">
                    <DataTable
                        v-model:selected="selected"
                        name="clientFiles"
                        :rows="files"
                        :columns="columns"
                        :row-id="(f: ClientFile) => f.hash"
                        :label="t('IDS_VIEWFILES')"
                    >
                        <template #empty>
                            <span class="muted">{{ t('nj.browse.asking') }}</span>
                        </template>
                    </DataTable>
                </div>
                <div class="dialog-buttons">
                    <button v-if="first" class="button accent" :disabled="chosen.length === 0" @click="download">
                        <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                    </button>
                    <DialogClose class="button"><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose>
                    <button v-if="!first" class="button accent" :disabled="chosen.length === 0" @click="download">
                        <Icon name="DOWNLOAD" />{{ t('IDS_DOWNLOAD') }}
                    </button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.client-files {
    width: min(680px, calc(100vw - 32px));
    height: min(460px, calc(100vh - 64px));
}

.table {
    display: flex;
    flex: 1;
    min-height: 0;
}
</style>
