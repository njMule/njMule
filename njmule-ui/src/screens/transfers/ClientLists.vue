<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { ContextMenuItem } from 'reka-ui';
import { computed, h, ref } from 'vue';
import { answer, api } from '../../api/client';
import ClientFilesDialog from '../../dialogs/ClientFilesDialog.vue';
import { duration, rate, size } from '../../format';
import { language, t } from '../../i18n';
import { bans, known, uploads, type Ban, type KnownClient, type Upload } from '../../model/engine';
import { guest } from '../../role';
import { problem } from '../../toasts';
import { byName, withoutColon } from '../../transfers';
import DataTable, { type ColumnMeta } from '../../ui/DataTable.vue';
import FileIcon from '../../ui/FileIcon.vue';
import Icon from '../../ui/Icon.vue';
import Country from './Country.vue';

// The clients we upload to, those waiting for a slot, the banned and the clients met: one list at a time.

const props = defineProps<{ shelf: 'uploads' | 'queue' | 'banned' | 'known' }>();
const selected = ref<string[]>([]);
const numeric: ColumnMeta = { numeric: true };

// Words in the order of their characters.
function byText(a: any, b: any, id: string): number {
    return byName(String(a.getValue(id)), String(b.getValue(id)));
}

// A name in its own direction; an address or a program left to right.
function named(text: string) {
    return h('span', { class: 'ellipsis', dir: 'auto' }, text);
}

function leftToRight(text: string) {
    return h('span', { class: 'ellipsis', dir: 'ltr' }, text);
}

// The date and time of a moment given in seconds.
function dateTime(seconds: number): string {
    return new Date(seconds * 1000).toLocaleString(language.value, {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    });
}

const country = {
    id: 'country',
    header: t('nj.clients.country'),
    size: 70,
    sortFn: byText,
    cell: ({ getValue }: any) => h(Country, { code: getValue() as string }),
};

const uploadColumns: ColumnDef<any, Upload, any>[] = [
    {
        id: 'client',
        header: t('IDS_QL_USERNAME'),
        accessorFn: (u: Upload) => u.nick ?? u.address,
        size: 180,
        sortFn: byText,
        cell: ({ getValue }: any) => named(getValue()),
    },
    { ...country, accessorFn: (u: Upload) => u.country ?? '' },
    {
        id: 'software',
        header: t('IDS_CD_CSOFT'),
        accessorFn: (u: Upload) => u.software,
        size: 130,
        sortFn: byText,
        cell: ({ getValue }: any) => leftToRight(getValue()),
    },
    {
        id: 'file',
        header: t('IDS_DL_FILENAME'),
        accessorFn: (u: Upload) => u.file,
        size: 260,
        sortFn: byText,
        cell: ({ getValue }: any) =>
            h('span', { class: 'name-cell' }, [h(FileIcon, { name: getValue() }), named(getValue())]),
    },
    {
        id: 'speed',
        header: t('IDS_DL_SPEED'),
        accessorFn: (u: Upload) => u.bytesPerSecond,
        size: 90,
        meta: numeric,
        cell: ({ getValue }: any) => rate(getValue()),
    },
    {
        id: 'transferred',
        header: t('IDS_DL_TRANSF'),
        accessorFn: (u: Upload) => u.transferred,
        size: 90,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'waited',
        header: t('nj.transfers.waited'),
        accessorFn: (u: Upload) => u.waitingSeconds,
        size: 90,
        meta: numeric,
        cell: ({ getValue }: any) => duration(getValue()),
    },
];

const banColumns: ColumnDef<any, Ban, any>[] = [
    {
        id: 'address',
        header: t('IDS_IP'),
        accessorFn: (b: Ban) => b.address,
        size: 200,
        sortFn: byText,
        cell: ({ getValue }: any) => leftToRight(getValue()),
    },
    {
        id: 'left',
        header: t('nj.transfers.banLeft'),
        accessorFn: (b: Ban) => b.secondsLeft,
        size: 120,
        meta: numeric,
        cell: ({ getValue }: any) => duration(getValue()),
    },
];

// The clients met in the last 90 days, never their addresses; the totals are those of the credits.
const knownColumns: ColumnDef<any, KnownClient, any>[] = [
    {
        id: 'client',
        header: t('IDS_QL_USERNAME'),
        accessorFn: (k: KnownClient) => (k.nick === '' ? k.userHash : k.nick),
        size: 180,
        sortFn: byText,
        cell: ({ getValue }: any) => named(getValue()),
    },
    { ...country, accessorFn: (k: KnownClient) => k.country ?? '' },
    {
        id: 'software',
        header: t('IDS_CD_CSOFT'),
        accessorFn: (k: KnownClient) => k.software,
        size: 150,
        sortFn: byText,
        cell: ({ getValue }: any) => leftToRight(getValue()),
    },
    {
        id: 'firstSeen',
        header: t('nj.clients.firstSeen'),
        accessorFn: (k: KnownClient) => k.firstSeen,
        size: 150,
        cell: ({ getValue }: any) => dateTime(getValue()),
    },
    {
        id: 'lastSeen',
        header: withoutColon(t('IDS_LASTSEEN')),
        accessorFn: (k: KnownClient) => k.lastSeen,
        size: 150,
        cell: ({ getValue }: any) => dateTime(getValue()),
    },
    {
        id: 'uploaded',
        header: withoutColon(t('IDS_CD_TUP')),
        accessorFn: (k: KnownClient) => k.uploaded,
        size: 110,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
    {
        id: 'downloaded',
        header: withoutColon(t('IDS_CD_TDOWN')),
        accessorFn: (k: KnownClient) => k.downloaded,
        size: 110,
        meta: numeric,
        cell: ({ getValue }: any) => size(getValue()),
    },
];

const uploading = computed(() => uploads.value.filter((u) => u.state === 'uploading'));
const queued = computed(() => uploads.value.filter((u) => u.state === 'queued'));
const shownUploads = computed(() => (props.shelf === 'uploads' ? uploading.value : queued.value));
const chosenUploads = computed(() =>
    shownUploads.value.filter((u) => selected.value.includes(`${u.userHash}/${u.address}`)),
);
const browsing = ref(false);
const browsed = ref<{ address: string; name: string } | null>(null);

// View Shared Files: the client's list in a window of its own.
function viewFiles(): void {
    const u = chosenUploads.value[0];
    if (chosenUploads.value.length === 1 && u !== undefined) {
        browsed.value = { address: u.address, name: u.nick ?? u.address };
        browsing.value = true;
    }
}

async function unban(): Promise<void> {
    const outcomes = await Promise.allSettled(
        selected.value.map((address) => answer(api().DELETE('/bans/{address}', { params: { path: { address } } }))),
    );
    for (const o of outcomes) {
        if (o.status === 'rejected') {
            problem((o.reason as Error).message);
        }
    }
    try {
        bans.value = await answer(api().GET('/bans'));
    } catch {
        // The next reading shows them.
    }
}

const label = computed(
    () =>
        ({
            uploads: t('IDS_TW_UPLOADS'),
            queue: t('IDS_ONQUEUE'),
            banned: t('IDS_BANNED'),
            known: t('IDS_CLIENTLIST'),
        })[props.shelf],
);
</script>

<template>
    <DataTable
        v-if="shelf === 'uploads' || shelf === 'queue'"
        :key="shelf"
        v-model:selected="selected"
        :name="shelf"
        :rows="shownUploads"
        :columns="uploadColumns"
        :row-id="(u: Upload) => `${u.userHash}/${u.address}`"
        :label="label"
    >
        <template #empty>
            <span class="muted">{{ t('nj.transfers.noUploads') }}</span>
        </template>
        <template #menu>
            <!-- Asking a client for its files is not for a guest. -->
            <ContextMenuItem class="menu-item" :disabled="guest || chosenUploads.length !== 1" @select="viewFiles">
                <Icon name="FOLDER_OPEN" />{{ t('IDS_VIEWFILES') }}
            </ContextMenuItem>
        </template>
    </DataTable>
    <DataTable
        v-else-if="shelf === 'banned'"
        v-model:selected="selected"
        name="bans"
        :rows="bans"
        :columns="banColumns"
        :row-id="(b: Ban) => b.address"
        :label="label"
    >
        <template #empty>
            <span class="muted">{{ t('nj.transfers.noBans') }}</span>
        </template>
        <template #menu>
            <ContextMenuItem class="menu-item" :disabled="selected.length === 0" @select="unban">
                <Icon name="UNLOCK" />{{ t('IDS_UNBAN') }}
            </ContextMenuItem>
        </template>
    </DataTable>
    <DataTable
        v-else
        v-model:selected="selected"
        name="known"
        :rows="known"
        :columns="knownColumns"
        :row-id="(k: KnownClient) => k.userHash"
        :label="label"
    >
        <template #empty>
            <span class="muted">{{ t('nj.clients.none') }}</span>
        </template>
    </DataTable>
    <ClientFilesDialog v-model:open="browsing" :client="browsed" />
</template>

<style scoped>
/* A file's name beside the icon of its kind. */
:deep(.name-cell) {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    min-width: 0;
    max-width: 100%;
}
</style>
