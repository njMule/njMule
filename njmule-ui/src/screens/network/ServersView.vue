<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import {
    ContextMenuItem,
    ContextMenuPortal,
    ContextMenuSeparator,
    ContextMenuSub,
    ContextMenuSubContent,
    ContextMenuSubTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuRoot,
    DropdownMenuSeparator,
    DropdownMenuSub,
    DropdownMenuSubContent,
    DropdownMenuSubTrigger,
    DropdownMenuTrigger,
    PopoverContent,
    PopoverPortal,
    PopoverRoot,
    PopoverTrigger,
} from 'reka-ui';
import { computed, ref } from 'vue';
import { answer, api } from '../../api/client';
import { askRisky } from '../../ask';
import { count } from '../../format';
import { t } from '../../i18n';
import { connection, serverNotes, servers, status, type Server } from '../../model/engine';
import { message, problem, success } from '../../toasts';
import DataTable, { type ColumnMeta } from '../../ui/DataTable.vue';
import Icon from '../../ui/Icon.vue';
import Tip from '../../ui/Tip.vue';

// The servers: the list narrowed by its words, the one in use in bold behind an accent bar, the one being tried in
// italics; the details of the one chosen beside; servers added by address or from a server.met on the web.

const filter = ref('');
const selected = ref<string[]>([]);
const address = ref('');
const metUrl = ref('https://upd.emule-security.org/server.met');
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);

const inUse = computed(() => status.value?.server?.address ?? '');
const shown = computed(() => {
    const words = filter.value
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    return servers.value.filter((s) =>
        words.every((w) => `${s.name} ${s.address} ${s.description} ${s.version}`.toLowerCase().includes(w)),
    );
});
const chosen = computed(() => servers.value.filter((s) => selected.value.includes(s.address)));
const one = computed(() => (chosen.value.length === 1 ? chosen.value[0]! : null));

const PRIORITY: Record<Server['priority'], string> = {
    low: 'IDS_PRIOLOW',
    normal: 'IDS_PRIONORMAL',
    high: 'IDS_PRIOHIGH',
};

// A figure the server did not tell shows as nothing.
function known(n: number): string {
    return n > 0 ? count(n) : '';
}

const numeric: ColumnMeta = { numeric: true };

const columns: ColumnDef<any, Server, any>[] = [
    { id: 'name', header: t('IDS_SL_SERVERNAME'), accessorFn: (s: Server) => s.name, size: 170 },
    { id: 'address', header: t('IDS_IP'), accessorFn: (s: Server) => s.address, size: 150 },
    { id: 'description', header: t('IDS_DESCRIPTION'), accessorFn: (s: Server) => s.description, size: 170 },
    {
        id: 'ping',
        header: t('IDS_PING'),
        accessorFn: (s: Server) => s.ping,
        size: 75,
        meta: numeric,
        cell: ({ getValue }: any) => known(getValue()),
    },
    {
        id: 'users',
        header: t('IDS_UUSERS'),
        accessorFn: (s: Server) => s.users,
        size: 90,
        meta: numeric,
        cell: ({ getValue }: any) => known(getValue()),
    },
    {
        id: 'maxUsers',
        header: t('IDS_MAXCLIENT'),
        accessorFn: (s: Server) => s.maxUsers,
        size: 120,
        meta: numeric,
        cell: ({ getValue }: any) => known(getValue()),
    },
    {
        id: 'files',
        header: t('IDS_PW_FILES'),
        accessorFn: (s: Server) => s.files,
        size: 95,
        meta: numeric,
        cell: ({ getValue }: any) => known(getValue()),
    },
    { id: 'priority', header: t('IDS_PRIORITY'), accessorFn: (s: Server) => t(PRIORITY[s.priority]), size: 80 },
    { id: 'failed', header: t('IDS_UFAILED'), accessorFn: (s: Server) => s.failed, size: 90, meta: numeric },
    {
        id: 'static',
        header: t('IDS_STATICSERVER'),
        accessorFn: (s: Server) => t(s.static ? 'IDS_YES' : 'IDS_NO'),
        size: 70,
    },
    {
        id: 'softFiles',
        header: t('IDS_SOFTFILES'),
        accessorFn: (s: Server) => s.softFiles,
        size: 175,
        meta: numeric,
        cell: ({ getValue }: any) => known(getValue()),
    },
    {
        id: 'hardFiles',
        header: t('IDS_HARDFILES'),
        accessorFn: (s: Server) => s.hardFiles,
        size: 210,
        meta: { numeric: true, hidden: true } satisfies ColumnMeta,
        cell: ({ getValue }: any) => known(getValue()),
    },
    {
        id: 'version',
        header: t('IDS_VERSION'),
        accessorFn: (s: Server) => s.version,
        size: 90,
        meta: { hidden: true } satisfies ColumnMeta,
    },
    {
        id: 'lowId',
        header: t('IDS_IDLOW'),
        accessorFn: (s: Server) => s.lowIdUsers,
        size: 85,
        meta: numeric,
        cell: ({ getValue }: any) => known(getValue()),
    },
    {
        id: 'obfuscation',
        header: t('IDS_OBFUSCATION'),
        accessorFn: (s: Server) => t(s.obfuscation ? 'IDS_YES' : 'IDS_NO'),
        size: 110,
    },
];

function rowClass(s: Server): string {
    if (s.address !== inUse.value) {
        return '';
    }
    return connection.value === 'connected' ? 'current-server' : 'trying-server';
}

async function run(call: () => Promise<unknown>): Promise<void> {
    try {
        await call();
        servers.value = await answer(api().GET('/servers'));
    } catch (e) {
        problem((e as Error).message);
    }
}

function connectTo(s: Server): Promise<void> {
    return run(() => answer(api().POST('/servers/connect', { body: { address: s.address } })));
}

async function add(connect: boolean): Promise<void> {
    const where = address.value.trim();
    if (!/^[^\s:]+:\d{1,5}$/.test(where)) {
        message(t('nj.servers.badAddress', where));
        return;
    }
    await run(async () => {
        try {
            await answer(api().POST('/servers', { body: { address: where } }));
        } catch {
            // Known already: connecting goes on all the same.
        }
        if (connect) {
            await answer(api().POST('/servers/connect', { body: { address: where } }));
        }
    });
    address.value = '';
}

async function update(): Promise<void> {
    const url = metUrl.value.trim();
    message(t('IDS_DOWNLOADING_SERVERMET_FROM', url));
    await run(async () => {
        const { added } = await answer(api().POST('/servers/import', { body: { url } }));
        success(t('nj.servers.fromUrl', added, url));
    });
}

function prioritize(priority: Server['priority']): Promise<void> {
    return run(async () => {
        for (const s of chosen.value) {
            await answer(
                api().PUT('/servers/{address}/priority', {
                    params: { path: { address: s.address } },
                    body: { priority },
                }),
            );
        }
    });
}

function makeStatic(on: boolean): Promise<void> {
    return run(async () => {
        for (const s of chosen.value) {
            await answer(
                api().PUT('/servers/{address}/static', {
                    params: { path: { address: s.address } },
                    body: { static: on },
                }),
            );
        }
    });
}

async function remove(all: boolean): Promise<void> {
    const gone = all ? servers.value : chosen.value;
    if (
        all &&
        !(await askRisky(
            t('nj.servers.removeAllTitle', gone.length),
            t('nj.servers.removeAllText'),
            [],
            t('IDS_REMOVEALL'),
            t('nj.servers.keepAll'),
        ))
    ) {
        return;
    }
    await run(async () => {
        for (const s of gone) {
            await answer(api().DELETE('/servers/{address}', { params: { path: { address: s.address } } }));
        }
    });
}

async function copyLinks(): Promise<void> {
    try {
        await navigator.clipboard.writeText(
            chosen.value.map((s) => `ed2k://|server|${s.address.replace(':', '|')}|/`).join('\n'),
        );
    } catch (e) {
        problem((e as Error).message);
    }
}

// The figures the server told, those it did not left out.
const facts = computed(() => {
    const s = one.value;
    if (s === null) {
        return [];
    }
    return [
        [t('IDS_UUSERS'), known(s.users)],
        [t('IDS_PW_FILES'), known(s.files)],
        [t('IDS_SOFTFILES'), known(s.softFiles)],
        [t('IDS_HARDFILES'), known(s.hardFiles)],
        [t('IDS_IDLOW'), known(s.lowIdUsers)],
        [t('IDS_PING'), s.ping > 0 ? `${s.ping} ms` : ''],
        [t('IDS_VERSION'), s.version],
        [t('IDS_UFAILED'), s.failed > 0 ? String(s.failed) : ''],
        [t('IDS_PRIORITY'), t(PRIORITY[s.priority])],
    ]
        .map(([name, value]) => [name!.trim().replace(/:$/, ''), value!])
        .filter(([, value]) => value !== '');
});

const ours = computed(() => one.value !== null && one.value.address === inUse.value);

// The last lines the server we are on said.
const said = computed(() => {
    const s = one.value;
    if (s === null || !ours.value) {
        return '';
    }
    return serverNotes.value
        .filter((n) => n.server === s.name || n.server === s.address)
        .slice(-4)
        .map((n) => n.text)
        .join('\n');
});

function connectOrDisconnect(s: Server): Promise<void> {
    return ours.value ? run(() => answer(api().POST('/servers/disconnect'))) : connectTo(s);
}
</script>

<template>
    <div class="servers">
        <div class="tool-row">
            <strong>{{ t('IDS_SV_SERVERLIST') }} ({{ servers.length }})</strong>
            <span class="spacer" />
            <label class="field filter">
                <Icon name="FILTER" />
                <input v-model="filter" :placeholder="t('nj.servers.filter')" @keydown.esc="filter = ''" />
            </label>
            <PopoverRoot>
                <PopoverTrigger class="button"><Icon name="ADD" />{{ t('nj.servers.add') }}</PopoverTrigger>
                <PopoverPortal>
                    <PopoverContent class="menu add-servers" align="end" :side-offset="6">
                        <label class="field-label" for="server-address">{{ t('IDS_SV_ADDRESS') }}</label>
                        <div class="row">
                            <input
                                id="server-address"
                                v-model="address"
                                class="field"
                                dir="ltr"
                                :placeholder="t('nj.servers.addressPrompt')"
                            />
                            <button class="button" @click="add(false)">{{ t('nj.servers.add') }}</button>
                            <button class="button accent" @click="add(true)">{{ t('nj.servers.addConnect') }}</button>
                        </div>
                        <label class="field-label" for="server-met">{{ t('IDS_SV_MET') }}</label>
                        <div class="row">
                            <input id="server-met" v-model="metUrl" class="field" dir="ltr" />
                            <button class="button" @click="update">
                                <Icon name="REFRESH" />{{ t('IDS_SV_UPDATE') }}
                            </button>
                        </div>
                    </PopoverContent>
                </PopoverPortal>
            </PopoverRoot>
        </div>
        <div class="split">
            <DataTable
                fixed-widths
                v-model:selected="selected"
                name="servers"
                :rows="shown"
                :columns="columns"
                :row-id="(s: Server) => s.address"
                :row-class="rowClass"
                :label="t('IDS_SV_SERVERLIST')"
                :columns-label="t('IDS_SL_SERVERNAME')"
                @activate="connectTo"
            >
                <template #empty>
                    <span class="muted">{{ t('nj.servers.none') }}</span>
                </template>
                <template #menu>
                    <ContextMenuItem
                        class="menu-item default-item"
                        :disabled="one === null"
                        @select="one && connectTo(one)"
                    >
                        <Icon name="LINK" />{{ t('IDS_CONNECTTHIS') }}
                    </ContextMenuItem>
                    <ContextMenuSub>
                        <ContextMenuSubTrigger class="menu-item" :disabled="chosen.length === 0">
                            <Icon name="PRIORITY" />{{ t('IDS_PRIORITY')
                            }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                        </ContextMenuSubTrigger>
                        <ContextMenuPortal>
                            <ContextMenuSubContent class="menu">
                                <ContextMenuItem
                                    v-for="p in ['low', 'normal', 'high'] as const"
                                    :key="p"
                                    class="menu-item"
                                    @select="prioritize(p)"
                                >
                                    {{ t(PRIORITY[p]) }}
                                </ContextMenuItem>
                            </ContextMenuSubContent>
                        </ContextMenuPortal>
                    </ContextMenuSub>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="makeStatic(true)">
                        <Icon name="STAR" />{{ t('IDS_ADDTOSTATIC') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="makeStatic(false)">
                        <Icon name="CLOSE" />{{ t('IDS_REMOVEFROMSTATIC') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks">
                        <Icon name="COPY" />{{ t('IDS_DL_LINK1') }}
                    </ContextMenuItem>
                    <ContextMenuSeparator class="menu-separator" />
                    <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="remove(false)">
                        <Icon name="DELETE" />{{ t('IDS_REMOVETHIS') }}
                    </ContextMenuItem>
                    <ContextMenuItem class="menu-item" :disabled="servers.length === 0" @select="remove(true)">
                        <Icon name="CLEAR_ALL" />{{ t('IDS_REMOVEALL') }}
                    </ContextMenuItem>
                </template>
            </DataTable>
            <aside class="server-details">
                <p v-if="one === null" class="muted">{{ t('nj.servers.choose') }}</p>
                <template v-else>
                    <div class="details-top">
                        <span class="details-kind"><Icon name="SERVER" /></span>
                        <div class="details-title">
                            <strong class="details-name" dir="auto">{{ one.name || one.address }}</strong>
                            <span class="muted" dir="ltr">{{ one.address }}</span>
                        </div>
                    </div>
                    <p v-if="one.description" class="muted" dir="auto">{{ one.description }}</p>
                    <div class="details-actions">
                        <div class="details-buttons">
                            <button class="button" @click="connectOrDisconnect(one)">
                                <Icon :name="ours ? 'LINK_OFF' : 'LINK'" />{{
                                    t(ours ? 'IDS_MAIN_BTN_DISCONNECT' : 'IDS_MAIN_BTN_CONNECT')
                                }}
                            </button>
                            <button class="button flat" @click="makeStatic(!one.static)">
                                <Icon :name="one.static ? 'REMOVE_CIRCLE' : 'LIST_ADD'" />{{
                                    t(one.static ? 'IDS_REMOVEFROMSTATIC' : 'IDS_ADDTOSTATIC')
                                }}
                            </button>
                        </div>
                        <DropdownMenuRoot>
                            <Tip :text="t('IDS_MORE')">
                                <DropdownMenuTrigger
                                    ref="moreTrigger"
                                    class="button icon-only"
                                    :aria-label="t('IDS_MORE')"
                                >
                                    <Icon name="MORE" />
                                </DropdownMenuTrigger>
                            </Tip>
                            <DropdownMenuPortal>
                                <DropdownMenuContent class="menu" align="end" :reference="moreTrigger?.$el">
                                    <DropdownMenuSub>
                                        <DropdownMenuSubTrigger class="menu-item">
                                            <Icon name="PRIORITY" />{{ t('IDS_PRIORITY')
                                            }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                                        </DropdownMenuSubTrigger>
                                        <DropdownMenuPortal>
                                            <DropdownMenuSubContent class="menu">
                                                <DropdownMenuItem
                                                    v-for="p in ['low', 'normal', 'high'] as const"
                                                    :key="p"
                                                    class="menu-item"
                                                    @select="prioritize(p)"
                                                >
                                                    <Icon name="PRIORITY" />{{ t(PRIORITY[p]) }}
                                                </DropdownMenuItem>
                                            </DropdownMenuSubContent>
                                        </DropdownMenuPortal>
                                    </DropdownMenuSub>
                                    <DropdownMenuItem class="menu-item" @select="copyLinks">
                                        <Icon name="COPY" />{{ t('IDS_DL_LINK1') }}
                                    </DropdownMenuItem>
                                    <DropdownMenuSeparator class="menu-separator" />
                                    <DropdownMenuItem class="menu-item" @select="remove(false)">
                                        <Icon name="DELETE" />{{ t('IDS_REMOVETHIS') }}
                                    </DropdownMenuItem>
                                </DropdownMenuContent>
                            </DropdownMenuPortal>
                        </DropdownMenuRoot>
                    </div>
                    <dl>
                        <template v-for="[name, value] in facts" :key="name">
                            <dt class="muted">{{ name }}</dt>
                            <dd dir="auto">{{ value }}</dd>
                        </template>
                    </dl>
                    <div v-if="said" class="said">
                        <span class="field-label">{{ t('IDS_SV_SERVERINFO') }}</span>
                        <p dir="ltr">{{ said }}</p>
                    </div>
                </template>
            </aside>
        </div>
    </div>
</template>

<style scoped>
.servers {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-height: 0;
    min-width: 0;
}

.tool-row {
    display: flex;
    align-items: center;
    gap: 8px;
}

.spacer {
    flex: 1;
}

.filter {
    flex: 0 1 224px;
}

.add-servers {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 460px;
    padding: 14px;
}

.field-label {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.row {
    display: flex;
    gap: 8px;
}

.row .field {
    flex: 1;
    min-width: 0;
}

.split {
    display: flex;
    gap: 12px;
    flex: 1;
    min-height: 0;
}

.server-details {
    display: flex;
    flex-direction: column;
    gap: 14px;
    flex: 0 0 300px;
    padding: 16px;
    border: 1px solid var(--color-border-default);
    border-radius: 8px;
    overflow-y: auto;
}

.server-details p {
    margin: 0;
}

.details-top {
    display: flex;
    align-items: center;
    gap: 12px;
}

.details-kind {
    display: inline-flex;
    padding: 10px;
    border-radius: 8px;
    background: var(--color-bg-subtle);
    --nj-icon-size: 20px;
    --nj-icon-color: var(--color-fg-muted);
}

.details-title {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
}

.details-name {
    font-size: 1.1em;
    overflow-wrap: anywhere;
}

.details-actions {
    display: flex;
    align-items: flex-start;
    gap: 8px;
}

.details-buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    flex: 1;
    min-width: 0;
}

dl {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 6px 16px;
    margin: 0;
}

dd {
    margin: 0;
    overflow-wrap: anywhere;
}

.said {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.said p {
    white-space: pre-line;
}

/* The server in use: bold behind an accent bar; the one being tried: italic. */
.servers :deep(.current-server) {
    font-weight: bold;
    box-shadow: inset 3px 0 0 var(--nj-accent-strong);
}

.servers :deep(.trying-server) {
    font-style: italic;
}
</style>
