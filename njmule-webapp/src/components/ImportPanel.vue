<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { CUT_HINT } from '../tables';
import { ElButton, ElCheckbox, ElInput, ElTable, ElTableColumn } from 'element-plus';
import { computed, ref } from 'vue';
import { api } from '../api';
import { t } from '../i18n';

interface Report {
    dryRun: boolean;
    startAtLogin: boolean;
    items: Item[];
}

interface Item {
    file: string;
    outcome: Outcome;
    detail: string;
    phrases: { key: string; args: (string | number)[] }[];
}

// The report's phrases, written out so that the build takes their texts into the translations.
const PHRASES = new Set([
    'nj.import.d.text',
    'nj.import.d.insideEmule',
    'nj.import.d.shortLived',
    'nj.import.d.noSection',
    'nj.import.d.preferences',
    'nj.import.d.startAtLogin',
    'nj.import.d.portsOurs',
    'nj.import.d.servers',
    'nj.import.d.added',
    'nj.import.d.bootstrapNodes',
    'nj.import.d.kadContacts',
    'nj.import.d.knownFiles',
    'nj.import.d.aichTrees',
    'nj.import.d.leftOut',
    'nj.import.d.noSharedFolder',
    'nj.import.d.folders',
    'nj.import.d.notShared',
    'nj.import.d.singleFiles',
    'nj.import.d.ranges',
    'nj.import.d.ourOwn',
    'nj.import.d.nextStart',
    'nj.import.d.files',
    'nj.import.d.friends',
    'nj.import.d.searches',
    'nj.import.d.spamMarkers',
    'nj.import.d.comments',
    'nj.import.d.addresses',
    'nj.import.d.entries',
    'nj.import.d.sections',
    'nj.import.d.noCategory',
    'nj.import.d.categories',
    'nj.import.d.withoutPatterns',
    'nj.import.d.ownIdentity',
    'nj.import.d.closeEmule',
    'nj.import.d.userHash',
    'nj.import.d.identityKey',
    'nj.import.d.together',
    'nj.import.d.credits',
    'nj.import.d.kadId',
    'nj.import.d.notAsked',
    'nj.import.d.noDownloads',
    'nj.import.d.noDataFile',
    'nj.import.d.pauseFirst',
    'nj.import.d.download',
    'nj.import.d.copied',
]);

// The detail in the interface's language; in English, as njMule wrote it, if a phrase is not known here.
function detail(item: Item): string {
    if (item.phrases.some((p) => !PHRASES.has(p.key))) {
        return item.detail;
    }
    return item.phrases.map((p) => t(p.key, ...p.args)).join('; ');
}

type Outcome = 'imported' | 'would_import' | 'skipped' | 'missing' | 'failed';

const OUTCOMES: Record<Outcome, { key: string; tone: string }> = {
    imported: { key: 'nj.import.outcome.imported', tone: 'ok' },
    would_import: { key: 'nj.import.outcome.would_import', tone: 'busy' },
    skipped: { key: 'nj.import.outcome.skipped', tone: '' },
    missing: { key: 'nj.import.outcome.missing', tone: '' },
    failed: { key: 'nj.import.outcome.failed', tone: 'error' },
};

// eMule's folder on the computer njMule runs on, which it only reads.
const path = ref('');
const identity = ref(false);
const downloads = ref(false);
const report = ref<Report | null>(null);
const said = ref('');
const working = ref(false);

const checked = computed(() => report.value !== null && report.value.dryRun);
const unreadable = computed(() => report.value?.items.filter((i) => i.outcome === 'failed').length ?? 0);

async function run(dryRun: boolean): Promise<void> {
    working.value = true;
    said.value = t('nj.import.working');
    try {
        report.value = await api.post<Report>('/import/emule', {
            path: path.value.trim(),
            dryRun,
            identity: identity.value,
            downloads: downloads.value,
        });
        const files = report.value.items.length;
        said.value = dryRun
            ? t('nj.import.checked', files, unreadable.value)
            : t('nj.import.done', files, unreadable.value);
    } catch (e) {
        said.value = t('nj.import.failed', e instanceof Error ? e.message : String(e));
    } finally {
        working.value = false;
    }
}
</script>

<template>
    <p class="hint">{{ t('nj.import.intro') }}</p>
    <label class="field" for="nj-emule">
        {{ t('nj.import.directory') }}
        <el-input id="nj-emule" v-model="path" placeholder="C:\Users\…\AppData\Local\eMule\config" />
    </label>
    <el-checkbox v-model="identity">{{ t('nj.import.identity') }}</el-checkbox>
    <el-checkbox v-model="downloads">{{ t('nj.import.downloads') }}</el-checkbox>
    <h3>{{ t('nj.import.whatHappens') }}</h3>
    <el-table :tooltip-options="CUT_HINT" :data="report?.items ?? []" :empty-text="t('nj.import.empty')" height="260">
        <el-table-column :label="t('IDS_DL_FILENAME')" prop="file" min-width="160" />
        <el-table-column :label="t('nj.import.detail')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ detail(row as Item) }}</template>
        </el-table-column>
        <el-table-column :label="t('nj.import.outcome')" width="170">
            <template #default="{ row }">
                <span class="nj-chip" :class="OUTCOMES[row.outcome as Outcome].tone">
                    {{ t(OUTCOMES[row.outcome as Outcome].key) }}
                </span>
            </template>
        </el-table-column>
    </el-table>
    <p class="said">{{ said }}</p>
    <div class="buttons">
        <el-button :disabled="path.trim() === '' || working" @click="run(true)">
            {{ checked ? t('nj.import.checkAgain') : t('nj.import.check') }}
        </el-button>
        <el-button type="primary" :disabled="!checked || working" @click="run(false)">{{
            t('nj.import.run')
        }}</el-button>
    </div>
</template>

<style scoped>
.hint,
.said {
    color: var(--el-text-color-secondary);
}
.field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin: 10px 0;
}
.buttons {
    display: flex;
    gap: 8px;
}
</style>
