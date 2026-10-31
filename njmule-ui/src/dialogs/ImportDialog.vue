<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import type { ColumnDef } from '@tanstack/vue-table';
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, h, ref, shallowRef, watch } from 'vue';
import { answer, api, ApiError, endpoint } from '../api/client';
import { t } from '../i18n';
import { detail, emuleProfile, importButtons, OUTCOMES, reportSummary, type ImportItem } from '../import';
import { engineHere, type Platform } from '../platform';
import { isFullPath } from '../screens/settings/inputs';
import { problem } from '../toasts';
import DataTable from '../ui/DataTable.vue';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// Taking an eMule profile over: a check first shows what would be taken, then the import itself. eMule's folder, on the
// engine's computer, is only read.

const props = defineProps<{ platform: Platform }>();
const open = defineModel<boolean>('open', { required: true });

type Row = ImportItem & { n: string };

const directory = ref('');
const identity = ref(false);
const downloads = ref(false);
const rows = shallowRef<Row[]>([]);
const selected = ref<string[]>([]);
const summary = ref('');
const working = ref(false);
// Once a check showed what would happen, Import is the main action; after an import, none is.
const checked = ref(false);
const taken = ref(false);
const takeOff = ref(true);
const buttons = importButtons(navigator.platform);

// A folder of the engine's computer is chosen from the system's dialog only in the desktop app on that computer.
const canChoose = computed(() => props.platform.desktop && engineHere(endpoint().url));
const valid = computed(() => directory.value.trim() !== '' && isFullPath(directory.value.trim()));
const main = computed(() => (!checked.value ? 'check' : taken.value ? null : 'take'));

// Each opening starts afresh, from the eMule found on the engine's computer; also one asked as the window is drawn
// again, in another language.
watch(
    open,
    async (now) => {
        if (!now) {
            return;
        }
        directory.value = '';
        identity.value = false;
        downloads.value = false;
        rows.value = [];
        selected.value = [];
        summary.value = '';
        working.value = false;
        checked.value = false;
        taken.value = false;
        takeOff.value = true;
        const found = await emuleProfile();
        if (found !== null && open.value && directory.value === '') {
            directory.value = found;
        }
    },
    { immediate: true },
);

async function browse(): Promise<void> {
    const folder = await props.platform.chooseFolder(t('nj.import.directory'));
    if (folder !== null) {
        directory.value = folder;
    }
}

/** Checks ({@code dryRun}) or imports the profile in the folder field. */
async function run(dryRun: boolean): Promise<void> {
    const path = directory.value.trim();
    const before = { summary: summary.value, takeOff: takeOff.value };
    working.value = true;
    takeOff.value = true;
    summary.value = t('nj.import.working');
    try {
        const report = await answer(
            api().POST('/import/emule', {
                body: { path, dryRun, identity: identity.value, downloads: downloads.value },
            }),
        );
        rows.value = report.items.map((item, n) => ({ ...item, n: String(n) }));
        selected.value = [];
        summary.value = reportSummary(report, t);
        takeOff.value = !dryRun;
        checked.value = true;
        taken.value = !dryRun;
    } catch (e) {
        if (e instanceof ApiError && e.status === 400) {
            // No such folder on the engine's computer: nothing was tried.
            summary.value = before.summary;
            takeOff.value = before.takeOff;
            problem(t('nj.import.notADirectory', path));
        } else {
            // A check may be tried again; an import too, once it was checked.
            summary.value = t('nj.import.failed', (e as Error).message);
            takeOff.value = dryRun;
        }
    } finally {
        working.value = false;
    }
}

// Enter does what the main button does, unless a button has the focus.
function enter(e: KeyboardEvent): void {
    if (e.target instanceof HTMLButtonElement || e.isComposing) {
        return;
    }
    if (main.value === 'check' && valid.value && !working.value) {
        e.preventDefault();
        void run(true);
    } else if (main.value === 'take' && !takeOff.value && !working.value) {
        e.preventDefault();
        void run(false);
    }
}

const columns: ColumnDef<any, Row, any>[] = [
    {
        id: 'file',
        header: t('IDS_DL_FILENAME'),
        accessorFn: (r: Row) => r.file,
        size: 150,
        cell: ({ getValue }: any) => h('span', { class: 'ellipsis', dir: 'ltr' }, getValue() as string),
    },
    {
        id: 'detail',
        header: t('nj.import.detail'),
        accessorFn: (r: Row) => detail(r, t),
        size: 400,
        cell: ({ getValue }: any) => h('span', { class: 'ellipsis', dir: 'ltr' }, getValue() as string),
    },
    {
        id: 'outcome',
        header: t('nj.import.outcome'),
        accessorFn: (r: Row) => t(OUTCOMES[r.outcome].key),
        size: 170,
        cell: ({ row, getValue }: any) =>
            h('span', { class: ['state-chip', OUTCOMES[(row.original as Row).outcome].tone] }, [
                h(Icon, { name: OUTCOMES[(row.original as Row).outcome].icon }),
                getValue() as string,
            ]),
    },
];
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent
                class="dialog import-dialog"
                :aria-describedby="undefined"
                @pointer-down-outside.prevent
                @interact-outside.prevent
                @keydown.enter="enter"
            >
                <div class="frame-header">
                    <DialogTitle class="frame-title">{{ t('nj.import.heading') }}</DialogTitle>
                    <DialogClose class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')">
                        <Icon name="CLOSE" />
                    </DialogClose>
                </div>
                <div class="frame-body">
                    <p class="intro">{{ t('nj.import.intro') }}</p>
                    <div class="named">
                        <label class="dialog-label" for="import-directory">{{ t('nj.import.directory') }}</label>
                        <div class="directory-row">
                            <input
                                id="import-directory"
                                v-model="directory"
                                class="field directory"
                                dir="ltr"
                                spellcheck="false"
                                autocomplete="off"
                                :aria-invalid="directory.trim() !== '' && !valid"
                            />
                            <button type="button" class="button" :disabled="!canChoose" @click="browse">
                                <Icon name="FOLDER_OPEN" />{{ t('IDS_PW_BROWSE') }}
                            </button>
                        </div>
                    </div>
                    <!-- One option a card, each wrapping: their texts are long in every language. -->
                    <div class="options">
                        <label class="option"
                            ><input v-model="identity" type="checkbox" /><span>{{
                                t('nj.import.identity')
                            }}</span></label
                        >
                        <label class="option"
                            ><input v-model="downloads" type="checkbox" /><span>{{
                                t('nj.import.downloads')
                            }}</span></label
                        >
                    </div>
                    <strong class="card-heading">{{ t('nj.import.whatHappens') }}</strong>
                    <div class="report">
                        <DataTable
                            v-model:selected="selected"
                            name="importReport"
                            :rows="rows"
                            :columns="columns"
                            :row-id="(r: Row) => r.n"
                            :label="t('nj.import.whatHappens')"
                            :columns-label="t('nj.import.whatHappens')"
                        >
                            <template #empty>
                                <span class="empty">{{ t('nj.import.empty') }}</span>
                            </template>
                        </DataTable>
                    </div>
                </div>
                <div class="frame-footer">
                    <Tip :text="summary">
                        <span class="summary ellipsis" role="status">{{ summary }}</span>
                    </Tip>
                    <button
                        type="button"
                        class="button"
                        :class="{ accent: main === 'check', apart: buttons.apart }"
                        :disabled="!valid || working"
                        @click="run(true)"
                    >
                        <Icon :name="checked ? 'REFRESH' : 'SEARCH'" />{{
                            t(checked ? 'nj.import.checkAgain' : 'nj.import.check')
                        }}
                    </button>
                    <!-- Import and Close as wide as each other, in the platform's order. -->
                    <div class="uniform">
                        <template v-for="b in buttons.order" :key="b">
                            <button
                                v-if="b === 'take'"
                                type="button"
                                class="button"
                                :class="{ accent: main === 'take' }"
                                :disabled="takeOff || working"
                                @click="run(false)"
                            >
                                <Icon name="IMPORT" />{{ t('nj.import.run') }}
                            </button>
                            <DialogClose v-else class="button"
                                ><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose
                            >
                        </template>
                    </div>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
.import-dialog {
    width: min(800px, calc(100vw - 32px));
    height: min(580px, calc(100vh - 32px));
    padding: 0;
    gap: 0;
    overflow: hidden;
}

.frame-header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 12px 2px 18px;
}

.frame-title {
    flex: 1;
    min-width: 0;
    margin: 0;
    font-size: 1.15em;
    font-weight: bold;
}

.frame-body {
    display: flex;
    flex-direction: column;
    gap: 12px;
    flex: 1;
    min-height: 0;
    padding: 4px 18px 14px;
}

.intro {
    flex: none;
    margin: 0;
    color: var(--color-fg-muted);
}

.named {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.dialog-label {
    font-size: 0.92em;
    font-weight: bold;
    color: var(--color-fg-muted);
}

.directory-row {
    display: flex;
    gap: 8px;
}

.directory {
    flex: 1;
    min-width: 0;
}

.directory[aria-invalid='true'] {
    border-color: var(--color-danger-emphasis);
}

.options {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
}

.option {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    padding: 10px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
}

.option input {
    flex: none;
    width: 16px;
    height: 16px;
    margin: 1px 0 0;
}

.card-heading {
    flex: none;
}

.report {
    display: flex;
    flex: 1;
    min-height: 0;
}

.empty {
    font-size: 1.1em;
}

.frame-footer {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 18px;
    border-top: 1px solid var(--color-border-muted);
    background: var(--color-bg-subtle);
}

.summary {
    flex: 1;
    min-width: 0;
    color: var(--color-fg-muted);
}

/* The platform keeps the other actions apart from those that answer the dialog. */
.apart {
    margin-inline-end: 22px;
}

.uniform {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    gap: 10px;
}

.uniform .button {
    justify-content: center;
}
</style>
