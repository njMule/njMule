<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import {
    ContextMenuItem,
    ContextMenuPortal,
    ContextMenuRadioGroup,
    ContextMenuRadioItem,
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
import { computed, h, onMounted, onUnmounted, ref, shallowRef } from 'vue';
import { answer, api, endpoint } from '../../api/client';
import { askRisky } from '../../ask';
import { count } from '../../format';
import { t } from '../../i18n';
import { connection, serverAddress, serverNotes, servers, type Server } from '../../model/engine';
import {
    hostPort,
    lastSaid,
    rememberUrl,
    serverLink,
    serverLinks,
    SUGGESTED_URLS,
    urlHistory,
    webAddress,
} from '../../network';
import { chooseListFile, choosesOnEngine } from '../../list-files';
import { currentPlatform, type Platform } from '../../platform';
import { message, problem } from '../../toasts';
import DataTable, { type ColumnMeta } from '../../ui/DataTable.vue';
import EmptyState from '../../ui/EmptyState.vue';
import Icon from '../../ui/Icon.vue';
import PopoverHeader from '../../ui/PopoverHeader.vue';
import Tip from '../../ui/Tip.vue';
import Country from '../transfers/Country.vue';

// The servers: the list narrowed by its words, the one in use in bold behind an accent bar, the one being tried in
// italics; the details of the one chosen beside the list, or over its end in a narrow window; servers added by address
// or from a server.met on the web or in a file.

const filter = ref('');
const selected = ref<string[]>([]);
const address = ref('');
// The lists that worked are offered again; the well-known one is filled in while none did.
const metHistory = ref(urlHistory('serverMet'));
const metUrl = ref<string>(metHistory.value.length === 0 ? SUGGESTED_URLS.serverMet : '');
// The ways to add servers, under their button, also opened from the empty list.
const adding = ref(false);
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const addTrigger = ref<InstanceType<typeof PopoverTrigger> | null>(null);
// Whether the clipboard holds server links, read as the menu opens; true when it cannot be read.
const pasteable = ref(true);

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
const countText = computed(() => {
    const all = servers.value.length;
    const visible = shown.value.length;
    return `${t('IDS_SV_SERVERLIST')} (${visible === all ? all : `${visible}/${all}`})`;
});
const chosen = computed(() => servers.value.filter((s) => selected.value.includes(s.address)));
const one = computed(() => (chosen.value.length === 1 ? chosen.value[0]! : null));
// The first server chosen, which Connect and Enter act on.
const first = computed(() => servers.value.find((s) => s.address === selected.value[0]) ?? null);
const addressValid = computed(() => hostPort(address.value) !== null);
const metValid = computed(() => webAddress(metUrl.value) !== null);

const PRIORITY: Record<Server['priority'], string> = {
    low: 'IDS_PRIOLOW',
    normal: 'IDS_PRIONORMAL',
    high: 'IDS_PRIOHIGH',
};
const PRIORITIES = ['low', 'normal', 'high'] as const;
// The priority all the chosen servers share, empty when they differ.
const sharedPriority = computed(() => {
    const kinds = new Set(chosen.value.map((s) => s.priority));
    return kinds.size === 1 ? [...kinds][0]! : '';
});

/** Whether {@code s} is the server we are on or trying. */
function isOurs(s: Server | null): boolean {
    return s !== null && connection.value !== 'disconnected' && s.address === serverAddress.value;
}

// A figure the server did not tell shows as nothing.
function known(n: number): string {
    return n > 0 ? count(n) : '';
}

// The server in use or tried says so in a tooltip over its row, so that the type is not the only cue.
function rowTip(s: Server): string {
    if (!isOurs(s)) {
        return '';
    }
    return t(connection.value === 'connected' ? 'nj.servers.current' : 'nj.servers.trying');
}

// A cell's words: names in their own direction, addresses and versions left to right.
function words(dir?: 'ltr' | 'auto') {
    return ({ getValue, row }: any) => {
        const tip = rowTip(row.original as Server);
        const text = h('span', { dir }, String(getValue()));
        return tip === '' ? text : h(Tip, { text: tip }, () => text);
    };
}

function figure(format: (n: number) => string = known) {
    return ({ getValue, row }: any) => words()({ getValue: () => format(getValue() as number), row });
}

// The priority sorts low to high and a yes after a no, whatever the words of the language.
const RANKS: Record<Server['priority'], number> = { low: 0, normal: 1, high: 2 };
function byPriority(a: any, b: any): number {
    return RANKS[(a.original as Server).priority] - RANKS[(b.original as Server).priority];
}
function byFlag(flag: (s: Server) => boolean) {
    return (a: any, b: any) => Number(flag(a.original as Server)) - Number(flag(b.original as Server));
}

const numeric: ColumnMeta = { numeric: true };

const columns: ColumnDef<any, Server, any>[] = [
    // First its flag and code, as the clients': known once the countries are shown.
    {
        id: 'country',
        header: t('nj.clients.country'),
        accessorFn: (s: Server) => s.country ?? '',
        size: 70,
        cell: ({ getValue }: any) => h(Country, { code: getValue() as string }),
    },
    {
        id: 'name',
        header: t('IDS_SL_SERVERNAME'),
        accessorFn: (s: Server) => s.name,
        size: 170,
        cell: words('auto'),
    },
    { id: 'address', header: t('IDS_IP'), accessorFn: (s: Server) => s.address, size: 150, cell: words('ltr') },
    {
        id: 'description',
        header: t('IDS_DESCRIPTION'),
        accessorFn: (s: Server) => s.description,
        size: 170,
        cell: words('auto'),
    },
    { id: 'ping', header: t('IDS_PING'), accessorFn: (s: Server) => s.ping, size: 75, meta: numeric, cell: figure() },
    {
        id: 'users',
        header: t('IDS_UUSERS'),
        accessorFn: (s: Server) => s.users,
        size: 90,
        meta: numeric,
        cell: figure(),
    },
    {
        id: 'maxUsers',
        header: t('IDS_MAXCLIENT'),
        accessorFn: (s: Server) => s.maxUsers,
        size: 120,
        meta: numeric,
        cell: figure(),
    },
    {
        id: 'files',
        header: t('IDS_PW_FILES'),
        accessorFn: (s: Server) => s.files,
        size: 95,
        meta: numeric,
        cell: figure(),
    },
    {
        id: 'priority',
        header: t('IDS_PRIORITY'),
        accessorFn: (s: Server) => t(PRIORITY[s.priority]),
        size: 80,
        sortFn: byPriority,
        cell: words(),
    },
    {
        id: 'failed',
        header: t('IDS_UFAILED'),
        accessorFn: (s: Server) => s.failed,
        size: 90,
        meta: numeric,
        cell: figure(String),
    },
    {
        id: 'static',
        header: t('IDS_STATICSERVER'),
        accessorFn: (s: Server) => t(s.static ? 'IDS_YES' : 'IDS_NO'),
        size: 70,
        sortFn: byFlag((s) => s.static),
        cell: words(),
    },
    {
        id: 'softFiles',
        header: t('IDS_SOFTFILES'),
        accessorFn: (s: Server) => s.softFiles,
        size: 175,
        meta: numeric,
        cell: figure(),
    },
    {
        id: 'hardFiles',
        header: t('IDS_HARDFILES'),
        accessorFn: (s: Server) => s.hardFiles,
        size: 210,
        meta: { numeric: true, hidden: true } satisfies ColumnMeta,
        cell: figure(),
    },
    {
        id: 'version',
        header: t('IDS_VERSION'),
        accessorFn: (s: Server) => s.version,
        size: 90,
        meta: { hidden: true } satisfies ColumnMeta,
        cell: words('ltr'),
    },
    {
        id: 'lowId',
        header: t('IDS_IDLOW'),
        accessorFn: (s: Server) => s.lowIdUsers,
        size: 85,
        meta: numeric,
        cell: figure(),
    },
    {
        id: 'obfuscation',
        header: t('IDS_OBFUSCATION'),
        accessorFn: (s: Server) => t(s.obfuscation ? 'IDS_YES' : 'IDS_NO'),
        size: 110,
        sortFn: byFlag((s) => s.obfuscation),
        cell: words(),
    },
];

function rowClass(s: Server): string {
    if (!isOurs(s)) {
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

/** Leaves the first server chosen when it is ours, else connects to it, or to any known one when none is chosen. */
function connectOrDisconnect(): Promise<void> {
    const s = first.value;
    if (isOurs(s)) {
        return run(() => answer(api().POST('/servers/disconnect')));
    }
    return s === null ? run(() => answer(api().POST('/servers/connect', { body: {} }))) : connectTo(s);
}

async function connectToAddress(): Promise<void> {
    const where = hostPort(address.value);
    if (where === null) {
        message(t('nj.servers.badAddress', address.value.trim()));
        return;
    }
    await run(async () => {
        try {
            await answer(api().POST('/servers', { body: { address: where } }));
        } catch {
            // Known already: connecting goes on all the same.
        }
        await answer(api().POST('/servers/connect', { body: { address: where } }));
    });
    address.value = '';
}

async function update(): Promise<void> {
    const url = metUrl.value.trim();
    const parsed = webAddress(url);
    if (parsed === null) {
        return;
    }
    message(t('IDS_DOWNLOADING_SERVERMET_FROM', url));
    await run(async () => {
        const { added } = await answer(api().POST('/servers/import', { body: { url } }));
        rememberUrl('serverMet', url);
        metHistory.value = urlHistory('serverMet');
        message(t('nj.servers.fromUrl', added, parsed.hostname));
    });
}

const platform = shallowRef<Platform | undefined>();
onMounted(() => void currentPlatform().then((p) => (platform.value = p)));

// A server.met of the engine's computer by its path in the desktop window beside it, else one of this computer sent.
async function addServerMet(): Promise<void> {
    const onEngine = choosesOnEngine(platform.value, endpoint().url);
    const chosen = await chooseListFile(onEngine, platform.value, t('nj.servers.addMet'), 'server.met', 'met');
    if (chosen !== undefined) {
        await run(() => answer(api().POST('/servers/import', { body: chosen.body })));
    }
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
        for (const s of chosen.value.filter((c) => c.static !== on)) {
            await answer(
                api().PUT('/servers/{address}/static', {
                    params: { path: { address: s.address } },
                    body: { static: on },
                }),
            );
        }
    });
}

async function remove(gone: Server[]): Promise<void> {
    if (gone.length === 0) {
        return;
    }
    await run(async () => {
        for (const s of gone) {
            await answer(api().DELETE('/servers/{address}', { params: { path: { address: s.address } } }));
        }
    });
}

async function removeAll(): Promise<void> {
    const all = servers.value;
    const yes = await askRisky(
        t('nj.servers.removeAllTitle', all.length),
        t('nj.servers.removeAllText'),
        [],
        t('IDS_REMOVEALL'),
        t('nj.servers.keepAll'),
    );
    if (!yes) {
        return;
    }
    // A server being tried goes too: the attempt stops with it.
    if (connection.value === 'connecting') {
        await run(() => answer(api().POST('/servers/disconnect')));
    }
    await remove(all);
}

async function copyLinks(): Promise<void> {
    if (chosen.value.length === 0) {
        return;
    }
    try {
        await navigator.clipboard.writeText(chosen.value.map((s) => serverLink(s.address)).join('\n'));
    } catch (e) {
        problem((e as Error).message);
    }
}

async function clipboard(): Promise<string | null> {
    try {
        return await navigator.clipboard.readText();
    } catch {
        return null;
    }
}

/** Adds the servers of the server links on the clipboard, and fetches the server lists linked there. */
async function pasteLinks(): Promise<void> {
    for (const link of serverLinks((await clipboard()) ?? '')) {
        if (link.kind === 'server') {
            await run(() => answer(api().POST('/servers', { body: { address: link.address } })));
        } else {
            metUrl.value = link.url;
            await update();
        }
    }
}

async function menuOpening(): Promise<void> {
    const text = await clipboard();
    pasteable.value = text === null || serverLinks(text).length > 0;
}

// Keys of the list, ahead of the table's own: Enter leaves the server in use, which the table would connect to again.
function key(e: KeyboardEvent): void {
    if ((e.target as HTMLElement).closest('button, input, a')) {
        return;
    }
    const command = e.ctrlKey || e.metaKey;
    if (e.key === 'Delete') {
        void remove(chosen.value);
    } else if (e.key === 'Enter') {
        void connectOrDisconnect();
    } else if (command && e.key.toLowerCase() === 'c') {
        void copyLinks();
    } else if (command && e.key.toLowerCase() === 'v') {
        void pasteLinks();
    } else {
        return;
    }
    e.preventDefault();
    e.stopPropagation();
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

const ours = computed(() => isOurs(one.value));

// What the server we are on or trying said since its session started.
const said = computed(() => (ours.value ? lastSaid(serverNotes.value).join('\n') : ''));

// The list keeps this much beside the details, else the details lie over its end while a server is chosen.
const LIST_WIDTH = 520;
const PANEL_WIDTH = 300;
const PANEL_GAP = 12;
const split = ref<HTMLElement | null>(null);
const room = ref(LIST_WIDTH + PANEL_GAP + PANEL_WIDTH);
const beside = computed(() => room.value >= LIST_WIDTH + PANEL_GAP + PANEL_WIDTH);
let observer: ResizeObserver | null = null;
onMounted(() => {
    observer = new ResizeObserver(() => (room.value = split.value?.clientWidth ?? room.value));
    if (split.value) {
        observer.observe(split.value);
    }
});
onUnmounted(() => observer?.disconnect());
</script>

<template>
    <div class="servers">
        <div class="tool-row">
            <strong>{{ countText }}</strong>
            <span class="spacer" />
            <label class="field filter">
                <Icon name="FILTER" />
                <input v-model="filter" :placeholder="t('nj.servers.filter')" @keydown.esc="filter = ''" />
            </label>
            <PopoverRoot v-model:open="adding">
                <Tip :text="t('nj.servers.add')">
                    <PopoverTrigger ref="addTrigger" class="button"
                        ><Icon name="ADD" />{{ t('nj.servers.add') }}</PopoverTrigger
                    >
                </Tip>
                <PopoverPortal>
                    <PopoverContent class="menu add-servers" align="end" :side-offset="2" :reference="addTrigger?.$el">
                        <PopoverHeader :title="t('nj.servers.add')" />
                        <div class="ways">
                            <label for="server-met">{{ t('IDS_SV_MET') }}</label>
                            <Tip :text="t('nj.input.url')">
                                <input
                                    id="server-met"
                                    v-model="metUrl"
                                    class="field"
                                    dir="ltr"
                                    list="server-met-history"
                                    placeholder="https://…/server.met"
                                    :aria-invalid="metUrl.trim() !== '' && !metValid"
                                    @keydown.enter="update"
                                />
                            </Tip>
                            <button class="button" :disabled="!metValid" @click="update">
                                <Icon name="REFRESH" />{{ t('IDS_SV_UPDATE') }}
                            </button>
                            <label for="server-address">{{ t('IDS_SV_ADDRESS') }}</label>
                            <Tip :text="t('nj.input.address')">
                                <input
                                    id="server-address"
                                    v-model="address"
                                    class="field"
                                    dir="ltr"
                                    :placeholder="t('nj.servers.addressPrompt')"
                                    :aria-invalid="address.trim() !== '' && !addressValid"
                                    @keydown.enter="connectToAddress"
                                />
                            </Tip>
                            <button class="button" :disabled="!addressValid" @click="connectToAddress">
                                <Icon name="ADD" />{{ t('nj.servers.addConnect') }}
                            </button>
                            <span />
                            <button class="button from-file" @click="addServerMet">
                                <Icon name="FOLDER_OPEN" />{{ t('nj.servers.addMet') }}
                            </button>
                        </div>
                        <datalist id="server-met-history">
                            <option v-for="u in metHistory" :key="u" :value="u" />
                        </datalist>
                    </PopoverContent>
                </PopoverPortal>
            </PopoverRoot>
        </div>
        <div ref="split" class="split">
            <div class="list" @keydown.capture="key" @contextmenu="menuOpening">
                <DataTable
                    fixed-widths
                    v-model:selected="selected"
                    name="servers"
                    :rows="shown"
                    :columns="columns"
                    :row-id="(s: Server) => s.address"
                    :row-class="rowClass"
                    :label="t('IDS_SV_SERVERLIST')"
                    @activate="connectTo"
                >
                    <template #empty>
                        <EmptyState icon="SERVER" :text="t('nj.servers.none')">
                            <button class="button" @click="update">
                                <Icon name="REFRESH" />{{ t('IDS_SV_UPDATE') }}
                            </button>
                            <button class="button" @click="adding = true">
                                <Icon name="ADD" />{{ t('nj.servers.add') }}
                            </button>
                        </EmptyState>
                    </template>
                    <template #menu>
                        <ContextMenuItem
                            class="menu-item default-item"
                            :disabled="chosen.length === 0"
                            @select="connectOrDisconnect"
                        >
                            <Icon name="LINK" /><span class="default-label">{{
                                t(isOurs(first) ? 'IDS_MAIN_BTN_DISCONNECT' : 'IDS_CONNECTTHIS')
                            }}</span>
                        </ContextMenuItem>
                        <ContextMenuSub>
                            <ContextMenuSubTrigger class="menu-item" :disabled="chosen.length === 0">
                                <Icon name="PRIORITY" />{{ t('IDS_PRIORITY')
                                }}<Icon class="menu-end" name="CHEVRON_RIGHT" />
                            </ContextMenuSubTrigger>
                            <ContextMenuPortal>
                                <ContextMenuSubContent class="menu">
                                    <ContextMenuRadioGroup :model-value="sharedPriority">
                                        <ContextMenuRadioItem
                                            v-for="p in PRIORITIES"
                                            :key="p"
                                            class="menu-item"
                                            :value="p"
                                            @select="prioritize(p)"
                                        >
                                            <span class="menu-check"
                                                ><Icon v-if="sharedPriority === p" name="CHECK" /></span
                                            >{{ t(PRIORITY[p]) }}
                                        </ContextMenuRadioItem>
                                    </ContextMenuRadioGroup>
                                </ContextMenuSubContent>
                            </ContextMenuPortal>
                        </ContextMenuSub>
                        <ContextMenuSeparator class="menu-separator" />
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="chosen.every((s) => s.static)"
                            @select="makeStatic(true)"
                        >
                            <Icon name="LIST_ADD" />{{ t('IDS_ADDTOSTATIC') }}
                        </ContextMenuItem>
                        <ContextMenuItem
                            class="menu-item"
                            :disabled="!chosen.some((s) => s.static)"
                            @select="makeStatic(false)"
                        >
                            <Icon name="REMOVE_CIRCLE" />{{ t('IDS_REMOVEFROMSTATIC') }}
                        </ContextMenuItem>
                        <ContextMenuSeparator class="menu-separator" />
                        <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="copyLinks">
                            <Icon name="COPY" />{{ t('IDS_DL_LINK1') }}
                        </ContextMenuItem>
                        <ContextMenuItem class="menu-item" :disabled="!pasteable" @select="pasteLinks">
                            <Icon name="PASTE" />{{ t('IDS_SW_DIRECTDOWNLOAD') }}
                        </ContextMenuItem>
                        <ContextMenuItem class="menu-item" :disabled="chosen.length === 0" @select="remove(chosen)">
                            <Icon name="DELETE" />{{ t('IDS_REMOVETHIS') }}
                        </ContextMenuItem>
                        <ContextMenuItem class="menu-item" :disabled="servers.length === 0" @select="removeAll">
                            <Icon name="DELETE_ALL" />{{ t('IDS_REMOVEALL') }}
                        </ContextMenuItem>
                    </template>
                </DataTable>
            </div>
            <aside v-if="beside || one !== null" class="server-details" :class="{ floating: !beside }">
                <p v-if="one === null" class="muted">{{ t('nj.servers.choose') }}</p>
                <template v-else>
                    <div class="details-top">
                        <span class="details-kind"><Icon name="SERVER" /></span>
                        <div class="details-title">
                            <strong class="details-name" dir="auto">{{ one.name || one.address }}</strong>
                            <span class="details-where muted"
                                ><span dir="ltr">{{ one.address }}</span
                                ><Country :code="one.country"
                            /></span>
                        </div>
                        <Tip v-if="!beside" :text="t('IDS_FD_CLOSE')">
                            <button
                                class="button flat icon-only"
                                :aria-label="t('IDS_FD_CLOSE')"
                                @click="selected = []"
                            >
                                <Icon name="CLOSE" />
                            </button>
                        </Tip>
                    </div>
                    <p v-if="one.description" class="muted" dir="auto">{{ one.description }}</p>
                    <div class="details-actions">
                        <div class="details-buttons">
                            <button class="button" @click="connectOrDisconnect">
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
                                                    v-for="p in PRIORITIES"
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
                                    <DropdownMenuItem class="menu-item" @select="remove(chosen)">
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
                        <p class="server-message" dir="ltr">{{ said }}</p>
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

:deep(.add-servers) {
    display: flex;
    flex-direction: column;
    width: 520px;
    max-width: calc(100vw - 32px);
    padding: 14px;
    border-radius: 6px;
}

/* The ways side by side as in a form: the names, the boxes that take the rest, their buttons. */
.ways {
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: 8px;
}

/* Under the field it goes with, as wide as its words. */
.from-file {
    justify-self: start;
}

.field[aria-invalid='true'] {
    border-color: var(--color-danger-emphasis);
}

.field-label {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

/* What a double click or Enter does, as the menu's first item, in bold. */
.default-label {
    font-weight: bold;
}

.split {
    position: relative;
    display: flex;
    gap: 12px;
    flex: 1;
    min-height: 0;
}

.list {
    display: flex;
    flex: 1;
    min-width: 0;
    min-height: 0;
}

.server-details {
    display: flex;
    flex-direction: column;
    gap: 14px;
    flex: 0 0 300px;
    padding: 16px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
    overflow-y: auto;
}

/* Too narrow for both side by side, the details lie over the end of the list while a server is chosen. */
.server-details.floating {
    position: absolute;
    inset-block: 0;
    inset-inline-end: 0;
    width: min(300px, 100%);
    box-shadow: 0 4px 18px var(--color-shadow-default);
    z-index: 2;
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
    flex: 1;
    min-width: 0;
}

.details-where {
    display: flex;
    align-items: center;
    gap: 10px;
}

.details-name {
    font-size: 1.05em;
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
    grid-template-columns: auto minmax(0, 1fr);
    gap: 6px 16px;
    margin: 0;
}

/* A value too long for its column ends in an ellipsis: a figure never breaks. */
dd {
    min-width: 0;
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.said {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

/* What the server said, on a tint of its own. */
.server-details .server-message {
    padding: 8px 10px;
    border-radius: 6px;
    background: var(--color-bg-subtle);
    font-size: 0.92em;
    white-space: pre-line;
    overflow-wrap: anywhere;
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
