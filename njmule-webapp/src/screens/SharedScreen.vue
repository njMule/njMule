<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    ElButton,
    ElCheckbox,
    ElDialog,
    ElDropdown,
    ElDropdownItem,
    ElDropdownMenu,
    ElInput,
    ElMessageBox,
    ElSwitch,
    ElTable,
    ElTableColumn,
} from 'element-plus';
import { computed, ref } from 'vue';
import { api } from '../api';
import { copy } from '../clipboard';
import CommentsDialog from '../components/CommentsDialog.vue';
import NjIcon from '../components/NjIcon.vue';
import { usePoll } from '../engine';
import { size } from '../format';
import { t } from '../i18n';
import { htmlLinks } from '../links';
import { attempt } from '../notify';
import { shareFolder } from '../sharing';

type Priority = 'very_low' | 'low' | 'normal' | 'high' | 'release' | 'auto';

interface Shared {
    hash: string;
    name: string;
    size: number;
    path: string;
    priority: Priority;
    link: string;
}

interface Folder {
    path: string;
    subdirectories: boolean;
}

const files = usePoll(() => api.get<Shared[]>('/shared'), 15000, [] as Shared[]);
const folders = usePoll(() => api.get<Folder[]>('/shared/directories'), 15000, [] as Folder[]);
const place = ref<string>('');
const filter = ref('');
const chosen = ref<Shared[]>([]);
const sharing = ref(false);
const newFolder = ref('');
const withSubfolders = ref(true);
const commenting = ref(false);

const PRIORITIES: { value: Priority; key: string }[] = [
    { value: 'very_low', key: 'IDS_PRIOVERYLOW' },
    { value: 'low', key: 'IDS_PRIOLOW' },
    { value: 'normal', key: 'IDS_PRIONORMAL' },
    { value: 'high', key: 'IDS_PRIOHIGH' },
    { value: 'release', key: 'IDS_PRIORELEASE' },
    { value: 'auto', key: 'IDS_PRIOAUTO' },
];

const folder = computed(() => folders.data.value.find((f) => f.path === place.value));

function within(file: Shared, dir: Folder): boolean {
    const parent = file.path.substring(0, Math.max(file.path.lastIndexOf('/'), file.path.lastIndexOf('\\')));
    return dir.subdirectories
        ? parent === dir.path || file.path.startsWith(dir.path + separator(dir.path))
        : parent === dir.path;
}

function separator(path: string): string {
    return path.includes('\\') ? '\\' : '/';
}

function inPlace(dir: Folder): Shared[] {
    return files.data.value.filter((f) => within(f, dir));
}

const visible = computed(() => {
    const words = filter.value
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    const here = folder.value ? inPlace(folder.value) : files.data.value;
    return here.filter((f) => words.every((w) => f.name.toLowerCase().includes(w)));
});

function priority(p: Priority): string {
    return t(PRIORITIES.find((x) => x.value === p)?.key ?? 'IDS_PRIOAUTO');
}

async function share(path: string, subdirectories: boolean): Promise<void> {
    await shareFolder(path, subdirectories);
    await folders.refresh();
    await files.refresh();
}

async function shareNew(): Promise<void> {
    const path = newFolder.value.trim();
    if (path === '') {
        return;
    }
    sharing.value = false;
    await share(path, withSubfolders.value);
    newFolder.value = '';
}

async function unshare(): Promise<void> {
    const dir = folder.value;
    if (dir === undefined) {
        return;
    }
    await attempt(() => api.delete(`/shared/directories?path=${encodeURIComponent(dir.path)}`));
    place.value = '';
    await folders.refresh();
    await files.refresh();
}

async function setSubfolders(on: boolean): Promise<void> {
    if (folder.value) {
        await share(folder.value.path, on);
    }
}

async function setPriority(value: Priority): Promise<void> {
    await attempt(async () => {
        for (const f of chosen.value) {
            await api.put(`/shared/${f.hash}/priority`, { priority: value });
        }
    });
    await files.refresh();
}

async function rename(): Promise<void> {
    const f = chosen.value[0];
    if (chosen.value.length !== 1 || f === undefined) {
        return;
    }
    try {
        const { value } = await ElMessageBox.prompt(t('nj.shared.renameHint'), t('nj.shared.renameTitle'), {
            inputValue: f.name,
            inputPlaceholder: t('nj.shared.newName'),
            confirmButtonText: t('IDS_RENAME'),
            cancelButtonText: t('IDS_CANCEL'),
        });
        await attempt(() => api.put(`/shared/${f.hash}/name`, { name: value }));
        await files.refresh();
    } catch {
        // Not renamed.
    }
}

function copyLinks(html: boolean): void {
    const links = chosen.value.map((f) => ({ link: f.link, name: f.name }));
    void copy(
        html ? htmlLinks(links) : links.map((l) => l.link).join('\n'),
        html ? t('IDS_DL_LINK2') : t('nj.copyLink'),
    );
}
</script>

<template>
    <div class="layout">
        <aside class="places">
            <el-button type="primary" class="share" @click="sharing = true">
                <NjIcon name="FOLDER_ADD" />{{ t('IDS_SHAREDIR') }}
            </el-button>
            <ul>
                <li>
                    <button type="button" :class="{ current: place === '' }" @click="place = ''">
                        <NjIcon name="SHARED" /><span>{{ t('nj.shared.all') }}</span>
                        <b>{{ files.data.value.length }}</b>
                    </button>
                </li>
                <li v-for="f in folders.data.value" :key="f.path">
                    <button
                        type="button"
                        :class="{ current: place === f.path }"
                        :title="f.path"
                        @click="place = f.path"
                    >
                        <NjIcon name="FOLDER_OPEN" /><span>{{ f.path }}</span>
                        <b>{{ inPlace(f).length }}</b>
                    </button>
                </li>
            </ul>
            <div v-if="folder" class="folder">
                <p class="path">{{ folder.path }}</p>
                <p>
                    {{
                        t(
                            'nj.shared.files',
                            inPlace(folder).length,
                            size(inPlace(folder).reduce((s, f) => s + f.size, 0)),
                        )
                    }}
                </p>
                <label class="switch">
                    <el-switch :model-value="folder.subdirectories" @change="(on) => setSubfolders(Boolean(on))" />
                    {{ t('IDS_SHAREDIRSUB') }}
                </label>
                <el-button type="danger" plain @click="unshare">{{ t('IDS_UNSHAREDIR') }}</el-button>
            </div>
            <p v-else class="hint">{{ t('nj.shared.incomingNote') }}</p>
        </aside>
        <section class="files">
            <div class="tools">
                <el-dropdown :disabled="chosen.length === 0" trigger="click" @command="setPriority">
                    <el-button :disabled="chosen.length === 0"
                        ><NjIcon name="PRIORITY" />{{ t('IDS_PRIORITY') }}</el-button
                    >
                    <template #dropdown>
                        <el-dropdown-menu>
                            <el-dropdown-item v-for="p in PRIORITIES" :key="p.value" :command="p.value">
                                {{ t(p.key) }}
                            </el-dropdown-item>
                        </el-dropdown-menu>
                    </template>
                </el-dropdown>
                <el-button :disabled="chosen.length === 0" @click="copyLinks(false)">
                    <NjIcon name="COPY" />{{ t('nj.copyLink') }}
                </el-button>
                <el-button :disabled="chosen.length === 0" @click="copyLinks(true)">{{ t('IDS_DL_LINK2') }}</el-button>
                <el-button :disabled="chosen.length !== 1" @click="rename">{{ t('IDS_RENAME') }}</el-button>
                <el-button :disabled="chosen.length !== 1" @click="commenting = true">{{ t('IDS_CMT_ADD') }}</el-button>
                <span class="grow"></span>
                <el-input v-model="filter" class="filter" clearable :placeholder="t('nj.shared.filter')" />
            </div>
            <el-table
                :data="visible"
                row-key="hash"
                :empty-text="t('nj.shared.none')"
                @selection-change="(rows: Shared[]) => (chosen = rows)"
            >
                <el-table-column type="selection" width="40" />
                <el-table-column
                    :label="t('IDS_DL_FILENAME')"
                    prop="name"
                    min-width="260"
                    sortable
                    show-overflow-tooltip
                />
                <el-table-column :label="t('IDS_DL_SIZE')" prop="size" width="110" sortable align="right">
                    <template #default="{ row }">{{ size(row.size) }}</template>
                </el-table-column>
                <el-table-column :label="t('IDS_PRIORITY')" width="110">
                    <template #default="{ row }">{{ priority(row.priority) }}</template>
                </el-table-column>
                <el-table-column :label="t('nj.shared.folder')" prop="path" min-width="200" show-overflow-tooltip />
            </el-table>
        </section>
    </div>

    <CommentsDialog
        v-if="chosen.length === 1 && chosen[0]"
        v-model="commenting"
        :hash="chosen[0].hash"
        :name="chosen[0].name"
        :download="false"
    />
    <el-dialog v-model="sharing" :title="t('IDS_SHAREDIR')" width="560px">
        <form @submit.prevent="shareNew">
            <label class="label" for="nj-folder">{{ t('nj.shared.folder') }}</label>
            <el-input id="nj-folder" v-model="newFolder" placeholder="/srv/share · D:\Share" />
            <p class="hint">{{ t('nj.shared.shareText') }}</p>
            <el-checkbox v-model="withSubfolders">{{ t('IDS_SHAREDIRSUB') }}</el-checkbox>
        </form>
        <template #footer>
            <el-button @click="sharing = false">{{ t('IDS_CANCEL') }}</el-button>
            <el-button type="primary" :disabled="newFolder.trim() === ''" @click="shareNew">
                {{ t('nj.shared.share') }}
            </el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.layout {
    display: grid;
    grid-template-columns: 280px minmax(0, 1fr);
    gap: 16px;
}
.places ul {
    margin: 12px 0;
    padding: 0;
    list-style: none;
}
.places li button {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 6px 8px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-primary);
    font: inherit;
    text-align: start;
    cursor: pointer;
}
.places li button span {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.places li button.current {
    background: var(--el-fill-color);
    font-weight: 600;
}
.share {
    width: 100%;
}
.el-button .nj-icon {
    width: 16px;
    height: 16px;
    margin-inline-end: 6px;
}
.folder {
    padding: 10px 12px;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
    background: var(--el-bg-color);
}
.folder p {
    margin: 0 0 6px;
}
.path {
    word-break: break-all;
    font-weight: 600;
}
.switch {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 10px;
}
.hint {
    color: var(--el-text-color-secondary);
    font-size: 13px;
}
.tools {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
}
.tools .el-button + .el-button {
    margin-left: 0;
}
.grow {
    flex: 1;
}
.filter {
    width: 220px;
}
.label {
    display: block;
    margin-bottom: 6px;
    font-weight: 600;
}
@media (max-width: 900px) {
    .layout {
        grid-template-columns: 1fr;
    }
}
</style>
