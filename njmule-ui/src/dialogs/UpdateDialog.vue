<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { computed, ref } from 'vue';
import { size } from '../format';
import { t } from '../i18n';
import type { IconName } from '../icons';
import { logTime } from '../network';
import type { Platform } from '../platform';
import { problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import {
    bandOrder,
    checkForUpdate,
    downloadLabel,
    downloadUpdate,
    installUpdate,
    skipUpdate,
    update,
    updatesOpen,
    updateView,
    type Text,
    type UpdateAction,
    type UpdateButton,
    type UpdateView,
} from '../updates';

// What is known of new versions of njMule, following the status while it shows. It leaves the window usable behind it:
// a download can run while one goes on with the transfers.
const props = defineProps<{ platform: Platform; canOpenFolders: boolean }>();

const content = ref<InstanceType<typeof DialogContent> | null>(null);

const view = computed<UpdateView>(() =>
    update.value === null
        ? {
              title: null,
              critical: false,
              published: null,
              notes: '',
              downloading: false,
              words: [],
              checking: false,
              failure: null,
              newest: null,
              lastCheck: null,
              buttons: [
                  { action: 'check', role: 'ok' },
                  { action: 'close', role: 'close' },
              ],
          }
        : updateView(update.value),
);
const band = computed(() => bandOrder(view.value.buttons));
const progress = computed(() => update.value?.download ?? null);
const percent = computed(() => {
    const p = progress.value;
    return p === null ? 0 : p.size === 0 ? 100 : Math.min(100, (p.received * 100) / p.size);
});

interface Look {
    text: string;
    icon: IconName;
    accent?: boolean;
}

const LOOKS: Record<Exclude<UpdateAction, 'close' | 'changes'>, () => Look> = {
    restart: () => ({ text: 'nj.update.restart', icon: 'REFRESH', accent: true }),
    atExit: () => ({ text: 'nj.update.atExit', icon: 'CLOCK' }),
    folder: () => ({ text: 'IDS_OPENFOLDER', icon: 'FOLDER_OPEN' }),
    download: () => ({
        text: update.value === null ? '' : downloadLabel(update.value),
        icon: 'DOWNLOAD',
        accent: true,
    }),
    check: () => ({ text: 'IDS_CHECK4UPDATE', icon: 'REFRESH' }),
    skip: () => ({ text: 'nj.update.skip', icon: 'BLOCK' }),
};

function look(b: UpdateButton): Look {
    return LOOKS[b.action as keyof typeof LOOKS]();
}

function text([key, ...args]: Text): string {
    return t(key, ...args);
}

function act(action: UpdateAction): void {
    const version = update.value?.available?.version ?? '';
    switch (action) {
        case 'restart':
            void installUpdate(true);
            break;
        case 'atExit':
            void installUpdate(false);
            break;
        case 'folder':
            void reveal();
            break;
        case 'download':
            void downloadUpdate();
            break;
        case 'check':
            void checkForUpdate();
            break;
        case 'skip':
            void skipUpdate(version);
            updatesOpen.value = false;
            break;
        default:
            break;
    }
}

// The folder the package is in, on the engine's computer.
async function reveal(): Promise<void> {
    const file = update.value?.readyFile ?? '';
    const folder = file.substring(0, Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')));
    try {
        await props.platform.showFolder(folder || file);
    } catch (e) {
        problem((e as Error).message);
    }
}

// Escape closes it while it has the focus, as a window of its own; elsewhere the window behind takes the key.
function escape(e: KeyboardEvent): void {
    const element = content.value?.$el as HTMLElement | undefined;
    if (!element?.contains(document.activeElement)) {
        e.preventDefault();
    }
}
</script>

<template>
    <DialogRoot v-model:open="updatesOpen" :modal="false">
        <DialogPortal>
            <DialogContent
                ref="content"
                class="dialog updates"
                :aria-describedby="undefined"
                @interact-outside.prevent
                @escape-key-down="escape"
            >
                <div class="update-header">
                    <DialogTitle class="update-heading">{{ t('nj.update.title') }}</DialogTitle>
                    <DialogClose class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')">
                        <Icon name="CLOSE" />
                    </DialogClose>
                </div>
                <div class="update-body">
                    <p v-if="view.title" id="update-title" class="update-title">{{ text(view.title) }}</p>
                    <p v-if="view.critical" class="update-critical">
                        <Icon name="WARNING" />{{ t('nj.update.critical') }}
                    </p>
                    <p v-if="view.published" class="muted">{{ t('nj.update.published', logTime(view.published)) }}</p>
                    <textarea
                        v-if="view.notes"
                        id="update-notes"
                        class="update-notes"
                        :value="view.notes"
                        rows="10"
                        readonly
                        dir="ltr"
                    />
                    <div v-if="view.downloading && progress" class="update-progress">
                        <div
                            id="update-progress"
                            class="thin-bar"
                            role="progressbar"
                            :aria-label="t('nj.update.downloadInstall')"
                            :aria-valuenow="Math.round(percent)"
                            aria-valuemin="0"
                            aria-valuemax="100"
                        >
                            <div class="thin-bar-fill accent" :style="{ width: `${percent}%` }" />
                        </div>
                        <span class="muted">{{
                            t('nj.update.downloading', size(progress.received), size(progress.size))
                        }}</span>
                    </div>
                    <p v-for="(words, i) in view.words" :key="i">{{ text(words) }}</p>
                    <div v-if="view.checking" class="update-checking">
                        <span class="spinner" aria-hidden="true" />{{ t('nj.update.checking') }}
                    </div>
                    <p v-else-if="view.failure" class="update-failure">{{ t('nj.update.failed', view.failure) }}</p>
                    <p v-else-if="view.newest" class="update-newest">{{ t('nj.update.newest', view.newest) }}</p>
                    <p v-if="view.lastCheck" class="muted">{{ t('nj.update.lastCheck', logTime(view.lastCheck)) }}</p>
                </div>
                <!-- The band says what the check sends, above the buttons that the state of the update offers. -->
                <div class="update-band">
                    <p class="update-note">{{ t('nj.update.note') }}</p>
                    <div class="update-buttons">
                        <template v-for="(b, i) in band" :key="b?.action ?? `grow-${i}`">
                            <span v-if="b === null" class="grow" />
                            <DialogClose v-else-if="b.action === 'close'" class="button">
                                <Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}
                            </DialogClose>
                            <a
                                v-else-if="b.action === 'changes'"
                                class="button"
                                :href="update?.available?.page"
                                target="_blank"
                                rel="noopener"
                            >
                                <Icon name="OPEN_EXTERNAL" />{{ t('nj.update.changes') }}
                            </a>
                            <button
                                v-else
                                :id="`update-${b.action === 'atExit' ? 'at-exit' : b.action}`"
                                type="button"
                                class="button"
                                :class="{ accent: look(b).accent }"
                                :disabled="b.action === 'folder' && !canOpenFolders"
                                @click="act(b.action)"
                            >
                                <Icon :name="look(b).icon" />{{ t(look(b).text) }}
                            </button>
                        </template>
                    </div>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
/* The header, the body and the band of a dialog. It is as wide as the buttons the state offers need on one line, and no
   narrower than the body: the words wrap within that. */
.updates {
    width: max-content;
    min-width: min(560px, calc(100vw - 32px));
    max-width: calc(100vw - 32px);
    gap: 0;
    padding: 0;
    overflow: hidden;
}

.update-body,
.update-note {
    width: 0;
    min-width: 100%;
}

.update-header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 16px 18px 6px;
}

.update-heading {
    flex: 1;
    margin: 0;
    font-size: 1.15em;
    font-weight: bold;
}

.update-body {
    display: flex;
    flex-direction: column;
    gap: 10px;
    min-height: 0;
    padding: 4px 18px 14px;
    overflow-y: auto;
}

.update-body p {
    margin: 0;
    /* A path or a command longer than the line breaks rather than widens the window. */
    overflow-wrap: anywhere;
}

.update-title {
    font-size: 1.5em;
    font-weight: bold;
}

.update-critical {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--color-warning-fg);
    --nj-icon-color: var(--color-warning-fg);
}

.update-notes {
    width: 100%;
    padding: 6px 8px;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    background: var(--color-bg-default);
    color: var(--color-fg-default);
    font: inherit;
    resize: none;
}

.update-progress {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.update-progress .thin-bar {
    flex: none;
}

.update-checking {
    display: flex;
    align-items: center;
    gap: 8px;
}

/* A check running: a turning ring in the strong accent. */
.spinner {
    width: 18px;
    height: 18px;
    border: 2.5px solid var(--color-border-muted);
    border-top-color: var(--nj-accent-strong);
    border-radius: 50%;
    animation: turn 0.8s linear infinite;
}

@keyframes turn {
    to {
        transform: rotate(360deg);
    }
}

.update-failure {
    color: var(--color-danger-fg);
}

.update-newest {
    font-size: 1.25em;
}

.update-band {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 18px;
    border-top: 1px solid var(--color-border-muted);
    background: var(--color-bg-subtle);
}

.update-note {
    margin: 0;
    font-size: 0.92em;
    color: var(--color-fg-muted);
}

.update-buttons {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.update-buttons .button {
    min-width: 64px;
    justify-content: center;
    text-decoration: none;
}

.grow {
    flex: 1;
}
</style>
