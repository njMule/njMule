<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    ElButton,
    ElDialog,
    ElInput,
    ElInputNumber,
    ElOption,
    ElSegmented,
    ElSelect,
    ElStep,
    ElSteps,
    ElSwitch,
} from 'element-plus';
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import { t } from '../i18n';
import { attempt, told } from '../notify';
import { shareFolder } from '../sharing';
import { mode, setMode, type Mode } from '../theme';
import PortTest from './PortTest.vue';

const open = defineModel<boolean>({ required: true });
const emit = defineEmits<{ done: [] }>();

const STEPS = ['nj.wizard.stepStart', 'nj.wizard.stepNick', 'nj.wizard.stepFolders', 'nj.wizard.stepConnection'];

// The usual lines, download and upload in Mbit/s.
const LINES: [number, number][] = [
    [20, 1],
    [100, 20],
    [300, 50],
    [1000, 300],
    [2500, 1000],
];

const step = ref(0);
const prefs = ref<Record<string, string>>({});
const nick = ref('');
const incoming = ref('');
const folders = ref<{ path: string }[]>([]);
const newFolder = ref('');
const down = ref(100);
const up = ref(20);
const tcp = ref(4662);
const udp = ref(4672);
const upnp = ref(true);

const MODES = computed(() => [
    { value: 'system', label: t('nj.settings.systemLanguage') },
    { value: 'light', label: t('nj.mode.light') },
    { value: 'dark', label: t('nj.mode.dark') },
]);

// Mbit/s as the KiB/s the preferences keep.
function kib(mbit: number): number {
    return Math.max(1, Math.round((mbit * 1_000_000) / 8 / 1024));
}

function mbit(kibps: number): number {
    return Math.max(1, Math.round((kibps * 1024 * 8) / 1_000_000));
}

watch(open, async (now) => {
    if (!now) {
        return;
    }
    step.value = 0;
    await attempt(async () => {
        prefs.value = await api.get<Record<string, string>>('/preferences');
        folders.value = await api.get<{ path: string }[]>('/shared/directories');
    });
    nick.value = prefs.value['user.nick'] ?? '';
    incoming.value = prefs.value['dirs.incoming'] ?? '';
    tcp.value = Number(prefs.value['net.tcpPort'] ?? 4662);
    udp.value = Number(prefs.value['net.udpPort'] ?? 4672);
    upnp.value = prefs.value['net.upnp'] !== 'false';
    down.value = mbit(Number(prefs.value['limits.downloadCapacityKiBps'] ?? 10240));
    up.value = mbit(Number(prefs.value['limits.uploadCapacityKiBps'] ?? 1024));
});

function pickLine(index: number): void {
    const line = LINES[index];
    if (line) {
        [down.value, up.value] = line;
    }
}

async function addFolder(): Promise<void> {
    const path = newFolder.value.trim();
    if (path !== '' && (await shareFolder(path, true))) {
        folders.value = [...folders.value, { path }];
        newFolder.value = '';
    }
}

async function removeFolder(path: string): Promise<void> {
    if (await attempt(() => api.delete(`/shared/directories?path=${encodeURIComponent(path)}`))) {
        folders.value = folders.value.filter((f) => f.path !== path);
    }
}

// The ports and UPnP are saved before the test, which tries them as they are saved.
async function savePorts(): Promise<void> {
    await attempt(() =>
        api.patch('/preferences', {
            'net.tcpPort': String(tcp.value),
            'net.udpPort': String(udp.value),
            'net.upnp': String(upnp.value),
        }),
    );
}

async function finish(): Promise<void> {
    const changes: Record<string, string> = {
        'user.nick': nick.value.trim(),
        'net.tcpPort': String(tcp.value),
        'net.udpPort': String(udp.value),
        'net.upnp': String(upnp.value),
        'limits.downloadCapacityKiBps': String(kib(down.value)),
        'limits.uploadCapacityKiBps': String(kib(up.value)),
    };
    if (incoming.value.trim() !== (prefs.value['dirs.incoming'] ?? '')) {
        changes['dirs.incoming'] = incoming.value.trim();
    }
    if (await attempt(() => api.patch('/preferences', changes))) {
        told(t('nj.settings.saved'), 'success');
        open.value = false;
        emit('done');
    }
}
</script>

<template>
    <el-dialog v-model="open" :title="t('nj.wizard.title')" width="680px" @closed="emit('done')">
        <el-steps :active="step" finish-status="success" simple>
            <el-step v-for="s in STEPS" :key="s" :title="t(s)" />
        </el-steps>
        <section class="page">
            <template v-if="step === 0">
                <h3>{{ t('nj.wizard.welcome') }}</h3>
                <p>{{ t('nj.wizard.intro') }}</p>
                <label class="field">
                    {{ t('nj.settings.theme') }}
                    <el-segmented :model-value="mode" :options="MODES" @change="(m) => setMode(m as Mode)" />
                </label>
            </template>
            <template v-else-if="step === 1">
                <p class="hint">{{ t('nj.wizard.nickHint') }}</p>
                <label class="field">{{ t('IDS_PW_NICK') }}<el-input v-model="nick" maxlength="50" /></label>
            </template>
            <template v-else-if="step === 2">
                <p class="hint">{{ t('nj.wizard.foldersHint') }}</p>
                <label class="field"
                    >{{ t('IDS_PW_INCOMING') }}
                    <el-input v-model="incoming" :placeholder="t('nj.settings.defaultDir')" />
                    <small>{{ t('nj.wizard.incomingHint') }}</small>
                </label>
                <h4>{{ t('IDS_SHAREDFOLDERS') }}</h4>
                <ul class="folders">
                    <li v-for="f in folders" :key="f.path">
                        <span>{{ f.path }}</span>
                        <el-button size="small" :title="t('nj.wizard.unshare', f.path)" @click="removeFolder(f.path)">
                            ×
                        </el-button>
                    </li>
                </ul>
                <p v-if="folders.length === 0" class="hint">{{ t('nj.wizard.noFolders') }}</p>
                <form class="add" @submit.prevent="addFolder">
                    <el-input v-model="newFolder" placeholder="/srv/share · D:\Share" />
                    <el-button native-type="submit">{{ t('IDS_SHAREDIR') }}</el-button>
                </form>
                <p class="hint">{{ t('nj.wizard.asksFirst') }}</p>
            </template>
            <template v-else>
                <p class="hint">{{ t('nj.wizard.portsHint') }}</p>
                <label class="field"
                    >{{ t('nj.wizard.line') }}
                    <el-select :placeholder="t('nj.wizard.linePick')" @change="pickLine">
                        <el-option
                            v-for="(l, i) in LINES"
                            :key="i"
                            :value="i"
                            :label="t('nj.wizard.mbits', String(l[0]), String(l[1]))"
                        />
                    </el-select>
                </label>
                <div class="pair">
                    <label class="field"
                        >{{ t('nj.settings.downloadCapacity') }} (Mbit/s)<el-input-number
                            v-model="down"
                            :min="1"
                            :max="100000"
                    /></label>
                    <label class="field"
                        >{{ t('nj.settings.uploadCapacity') }} (Mbit/s)<el-input-number
                            v-model="up"
                            :min="1"
                            :max="100000"
                    /></label>
                </div>
                <div class="pair">
                    <label class="field"
                        >{{ t('IDS_PW_CLIENTPORT') }} (TCP)<el-input-number v-model="tcp" :min="1" :max="65535"
                    /></label>
                    <label class="field">UDP<el-input-number v-model="udp" :min="1" :max="65535" /></label>
                </div>
                <label class="switch"><el-switch v-model="upnp" />{{ t('nj.settings.upnp') }}</label>
                <p class="hint">{{ t('nj.wizard.upnpHint') }}</p>
                <div @click.capture="savePorts"><PortTest /></div>
            </template>
        </section>
        <p class="hint">{{ t('nj.wizard.stepOf', step + 1, STEPS.length) }} · {{ t('nj.wizard.later') }}</p>
        <template #footer>
            <el-button v-if="step < STEPS.length - 1" @click="open = false">{{ t('nj.wizard.skip') }}</el-button>
            <el-button v-if="step > 0" @click="step--">{{ t('nj.wizard.back') }}</el-button>
            <el-button
                v-if="step < STEPS.length - 1"
                type="primary"
                :disabled="step === 1 && nick.trim() === ''"
                @click="step++"
            >
                {{ t('nj.wizard.next') }}
            </el-button>
            <el-button v-else type="primary" @click="finish">{{ t('nj.wizard.finish') }}</el-button>
        </template>
    </el-dialog>
</template>

<style scoped>
.page {
    min-height: 240px;
    padding: 16px 4px 0;
}
.field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin-bottom: 10px;
}
.field small,
.hint {
    color: var(--el-text-color-secondary);
}
.folders {
    margin: 0;
    padding: 0;
    list-style: none;
}
.folders li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 4px 0;
    word-break: break-all;
}
.add,
.pair {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
}
.switch {
    display: flex;
    align-items: center;
    gap: 8px;
}
</style>
