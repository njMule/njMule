<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { PopoverArrow, PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger } from 'reka-ui';
import { ref } from 'vue';
import { t } from '../i18n';
import type { NetworkWords } from '../network';
import { current, networkPart, shortcut } from '../places';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// The networks' control: neutral, never the accent, the state in words beside its dot. Its popup connects or
// disconnects each network, stays open to show what follows, and leaves for the network or another server.
const props = defineProps<{ words: NetworkWords; compact: boolean }>();
const emit = defineEmits<{ ed2k: []; kad: []; both: [] }>();
const open = ref(false);
const trigger = ref<InstanceType<typeof PopoverTrigger> | null>(null);

function dot(): string {
    return props.words.up ? 'connected' : props.words.trying ? 'connecting' : '';
}

// What eD2K's state calls for, on its own line and on the button for both networks.
function ed2kAction(): string {
    return t(
        props.words.ed2kTone === 'connected'
            ? 'IDS_MAIN_BTN_DISCONNECT'
            : props.words.ed2kTone === 'connecting'
              ? 'IDS_MAIN_BTN_CANCEL'
              : 'IDS_MAIN_BTN_CONNECT',
    );
}

// The network's place, at the part a state tells of, or where it was left.
function show(part?: 'servers' | 'kad'): void {
    open.value = false;
    if (part) {
        networkPart.value = part;
    }
    current.value = 'network';
}
</script>

<template>
    <PopoverRoot v-model:open="open">
        <Tip :text="`${words.ed2k}\n${words.kad}`">
            <PopoverTrigger
                id="network-control"
                ref="trigger"
                class="button network-control"
                :aria-label="`${t('IDS_NETWORK')}: ${words.state}. ${words.detail}`"
            >
                <span class="network-dot" :class="dot()" />
                <span v-if="!compact" class="network-words">
                    <span class="network-state">{{ words.state }}</span>
                    <span v-if="words.detail" class="network-detail">{{ words.detail }}</span>
                </span>
                <Icon name="CHEVRON_DOWN" />
            </PopoverTrigger>
        </Tip>
        <PopoverPortal>
            <PopoverContent class="menu network-menu" align="end" :side-offset="2" :reference="trigger?.$el">
                <PopoverArrow class="popover-arrow" :width="24" :height="12" />
                <div class="network-row">
                    <div class="network-words">
                        <Tip :text="t('IDS_NETWORK')">
                            <button
                                type="button"
                                class="connection-chip"
                                :class="words.ed2kTone"
                                @click="show('servers')"
                            >
                                <span class="network-dot" />{{ words.ed2k }}
                            </button>
                        </Tip>
                        <span v-if="words.ed2kNote" class="network-note">{{ words.ed2kNote }}</span>
                    </div>
                    <button id="network-ed2k" type="button" class="button small" @click="emit('ed2k')">
                        {{ ed2kAction() }}
                    </button>
                </div>
                <div class="network-row">
                    <div class="network-words">
                        <Tip :text="t('IDS_NETWORK')">
                            <button type="button" class="connection-chip" :class="words.kadTone" @click="show('kad')">
                                <span class="network-dot" />{{ words.kad }}
                            </button>
                        </Tip>
                        <span v-if="words.kadNote" class="network-note">{{ words.kadNote }}</span>
                    </div>
                    <button id="network-kad" type="button" class="button small" @click="emit('kad')">
                        {{ t(words.kadRuns ? 'IDS_MAIN_BTN_DISCONNECT' : 'IDS_MAIN_BTN_CONNECT') }}
                    </button>
                </div>
                <div class="menu-separator" role="separator" />
                <button id="network-servers" type="button" class="network-item" @click="show('servers')">
                    {{ t('nj.network.otherServer') }}
                </button>
                <button id="network-open" type="button" class="network-item" @click="show()">
                    {{ t('IDS_NETWORK') }}<span class="network-keys shortcut-key">{{ shortcut(4) }}</span>
                </button>
                <button
                    id="connect"
                    type="button"
                    class="button both"
                    :class="{ accent: words.ed2kTone === '' }"
                    @click="emit('both')"
                >
                    <Icon :name="words.ed2kTone === '' ? 'LINK' : 'POWER'" />{{ ed2kAction() }}
                </button>
            </PopoverContent>
        </PopoverPortal>
    </PopoverRoot>
</template>

<style scoped>
.network-control {
    gap: 8px;
    padding: 0 8px 0 12px;
    border-radius: 8px;
    --nj-icon-color: var(--color-fg-muted);
}

.network-words {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    line-height: 1.15;
    min-width: 0;
}

.network-state {
    font-weight: bold;
}

.network-detail {
    color: var(--color-fg-muted);
    font-size: 0.85em;
}

.network-dot {
    width: 8px;
    height: 8px;
    flex: none;
    border-radius: 50%;
    background: var(--color-fg-subtle);
}

.network-dot.connected,
.connected .network-dot {
    background: var(--color-success-emphasis);
}

.network-dot.connecting,
.connecting .network-dot {
    background: var(--color-warning-emphasis);
}

:deep(.network-menu) {
    display: flex;
    flex-direction: column;
    gap: 6px;
    min-width: 320px;
    padding: 4px;
}

.network-row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 6px 4px;
}

.network-row .network-words {
    flex: 1;
    gap: 2px;
}

/* A network's state, which opens the network at its part. */
.connection-chip {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 0;
    border: 0;
    background: none;
    color: inherit;
    font: inherit;
    font-weight: bold;
    text-align: start;
    cursor: pointer;
}

.network-note {
    padding-inline-start: 16px;
    color: var(--color-fg-muted);
    font-size: 0.9em;
}

/* A line of the popup: a flat button its width, with its keys after the words. */
.network-item {
    display: flex;
    align-items: center;
    gap: 24px;
    padding: 6px 8px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: inherit;
    font: inherit;
    text-align: start;
    cursor: pointer;
}

.network-item:hover,
.network-item:focus-visible {
    background: var(--color-bg-subtle);
}

.network-keys {
    font-size: 0.85em;
}

.both {
    justify-content: center;
    width: 100%;
}
</style>
