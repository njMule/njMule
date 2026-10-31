<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { DialogClose, DialogContent, DialogOverlay, DialogPortal, DialogRoot, DialogTitle } from 'reka-ui';
import { ref, watch } from 'vue';
import { t } from '../i18n';
import type { Platform } from '../platform';
import { connectable, remoteApi } from '../remote';
import { actsFirst } from '../transfers';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// Connects to another njMule through its API: the address and the token are tried, remembered if asked, and the
// desktop app opens on that njMule in a window of its own.

const props = defineProps<{ platform: Platform }>();
const open = defineModel<boolean>('open', { required: true });

const address = ref('');
const token = ref('');
const remember = ref(true);
const state = ref('');
const connecting = ref(false);
const remembered = ref<string[]>([]);
const first = actsFirst();

watch(open, (now) => {
    if (!now) {
        return;
    }
    address.value = '';
    token.value = '';
    remember.value = true;
    state.value = '';
    remembered.value = [];
    props.platform.remoteConnections().then(
        (found) => (remembered.value = found),
        () => {},
    );
});

// Tried first, so that a wrong address or token is said here rather than by a window that does not open.
async function connect(): Promise<void> {
    const api = remoteApi(address.value);
    if (!connectable(api, token.value, remembered.value)) {
        state.value = t('nj.remote.missing');
        return;
    }
    connecting.value = true;
    state.value = t('nj.remote.connecting');
    try {
        await props.platform.openRemote(api, token.value.trim(), remember.value);
        open.value = false;
    } catch (e) {
        state.value = t('nj.remote.failed', (e as Error).message);
    } finally {
        connecting.value = false;
    }
}
</script>

<template>
    <DialogRoot v-model:open="open">
        <DialogPortal>
            <DialogOverlay class="scrim" />
            <DialogContent class="dialog remote" :aria-describedby="undefined">
                <div class="header">
                    <DialogTitle class="dialog-title display">{{ t('nj.remote.title') }}</DialogTitle>
                    <Tip :text="t('IDS_FD_CLOSE')">
                        <DialogClose class="button flat icon-only" :aria-label="t('IDS_FD_CLOSE')">
                            <Icon name="CLOSE" />
                        </DialogClose>
                    </Tip>
                </div>
                <div class="body">
                    <p class="intro">{{ t('nj.remote.intro') }}</p>
                    <label class="named">
                        <span class="label">{{ t('nj.remote.address') }}</span>
                        <span class="field"
                            ><input
                                id="remote-address"
                                v-model="address"
                                list="remote-addresses"
                                dir="ltr"
                                placeholder="http://nas.local:4711/api/v1"
                        /></span>
                    </label>
                    <datalist id="remote-addresses">
                        <option v-for="a in remembered" :key="a" :value="a" />
                    </datalist>
                    <label class="named">
                        <span class="label">{{ t('nj.remote.token') }}</span>
                        <span class="field"
                            ><input id="remote-token" v-model="token" type="password" dir="ltr" autocomplete="off"
                        /></span>
                    </label>
                    <label class="check"
                        ><input v-model="remember" type="checkbox" />{{ t('nj.remote.remember') }}</label
                    >
                </div>
                <div class="footer">
                    <span class="state muted">{{ state }}</span>
                    <button v-if="first" class="button accent" :disabled="connecting" @click="connect">
                        <Icon name="LINK" />{{ t('nj.remote.connect') }}
                    </button>
                    <DialogClose class="button"><Icon name="CLOSE" />{{ t('IDS_FD_CLOSE') }}</DialogClose>
                    <button v-if="!first" class="button accent" :disabled="connecting" @click="connect">
                        <Icon name="LINK" />{{ t('nj.remote.connect') }}
                    </button>
                </div>
            </DialogContent>
        </DialogPortal>
    </DialogRoot>
</template>

<style scoped>
/* As the desktop app's dialogs: the title and its close button, the fields, then a band with the state and buttons. */
.remote {
    width: min(560px, calc(100vw - 32px));
    padding: 0;
    gap: 0;
    overflow: hidden;
}

.header {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 16px 18px 6px;
}

.header .dialog-title {
    flex: 1;
    min-width: 0;
}

.body {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 4px 18px 14px;
}

.intro {
    margin: 0;
}

.named {
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.label {
    font-size: 0.92em;
    font-weight: bold;
    color: var(--color-fg-muted);
}

.check {
    display: flex;
    align-items: center;
    gap: 8px;
}

.footer {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 12px 18px;
    border-top: 1px solid var(--color-border-muted);
    background: var(--color-bg-subtle);
}

.state {
    flex: 1;
    min-width: 0;
}
</style>
