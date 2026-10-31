<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import {
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuPortal,
    DropdownMenuRoot,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from 'reka-ui';
import { ref } from 'vue';
import { t } from '../i18n';
import type { NetworkWords } from '../network';
import { current, shortcut } from '../places';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// The networks' control: neutral, never the accent, the state in words beside its dot; its menu connects each network.
const props = defineProps<{ words: NetworkWords; connecting: boolean; compact: boolean }>();
const emit = defineEmits<{ ed2k: []; kad: []; both: [] }>();
const trigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);

function dot(): string {
    return props.words.up ? 'connected' : props.words.trying ? 'connecting' : '';
}

function action(running: boolean): string {
    return t(running ? 'IDS_MAIN_BTN_DISCONNECT' : 'IDS_MAIN_BTN_CONNECT');
}
</script>

<template>
    <DropdownMenuRoot>
        <Tip :text="t('IDS_NETWORK')">
            <DropdownMenuTrigger
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
            </DropdownMenuTrigger>
        </Tip>
        <DropdownMenuPortal>
            <DropdownMenuContent class="menu network-menu" align="end" :side-offset="6" :reference="trigger?.$el">
                <div class="network-row">
                    <span class="connection-chip" :class="words.ed2kUp ? 'connected' : connecting ? 'connecting' : ''">
                        <span class="network-dot" />{{ words.ed2k }}
                    </span>
                    <DropdownMenuItem class="menu-item" @select="emit('ed2k')">
                        {{ connecting ? t('IDS_MAIN_BTN_CANCEL') : action(words.ed2kUp) }}
                    </DropdownMenuItem>
                </div>
                <div class="network-row">
                    <span
                        class="connection-chip"
                        :class="words.kadUp ? 'connected' : words.kadRuns ? 'connecting' : ''"
                    >
                        <span class="network-dot" />{{ words.kad }}
                    </span>
                    <DropdownMenuItem class="menu-item" @select="emit('kad')">{{
                        action(words.kadRuns)
                    }}</DropdownMenuItem>
                </div>
                <DropdownMenuSeparator class="menu-separator" />
                <DropdownMenuItem class="menu-item" @select="emit('both')">
                    <Icon :name="words.up || words.trying ? 'POWER' : 'LINK'" />
                    {{
                        t(
                            words.up
                                ? 'IDS_MAIN_BTN_DISCONNECT'
                                : words.trying
                                  ? 'IDS_MAIN_BTN_CANCEL'
                                  : 'IDS_MAIN_BTN_CONNECT',
                        )
                    }}
                </DropdownMenuItem>
                <DropdownMenuItem class="menu-item" @select="current = 'network'">
                    <Icon name="SERVER" />{{ t('nj.network.otherServer') }}
                </DropdownMenuItem>
                <DropdownMenuItem class="menu-item" @select="current = 'network'">
                    <Icon name="NETWORK" />{{ t('IDS_NETWORK') }}
                    <span class="menu-end shortcut-key">{{ shortcut(4) }}</span>
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenuPortal>
    </DropdownMenuRoot>
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

.network-menu {
    min-width: 320px;
}

.network-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 6px 4px 6px 10px;
}

.connection-chip {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    font-weight: bold;
}
</style>
