<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { PopoverAnchor, PopoverContent, PopoverPortal, PopoverRoot } from 'reka-ui';
import { computed, nextTick, ref } from 'vue';
import { answer, api } from '../api/client';
import { t } from '../i18n';
import { connection, preferences, serverName, status } from '../model/engine';
import { networkCards, networkWords } from '../network';
import { current, networkPart } from '../places';
import { problem } from '../toasts';
import Icon from '../ui/Icon.vue';
import PortTestPane from '../ui/PortTestPane.vue';
import Tip from '../ui/Tip.vue';
import KadView from './network/KadView.vue';
import LogView from './network/LogView.vue';
import ServersView from './network/ServersView.vue';

// The network: the state of eD2K, Kad and the ports on three cards, in plain words with what to do, then one of its
// parts at a time: the servers, Kad and the log.

const emit = defineEmits<{ ed2k: []; kad: [] }>();

const cards = computed(() => networkCards(connection.value, status.value, serverName.value, preferences.value));
const words = computed(() => networkWords(connection.value, status.value, serverName.value));
const testShown = ref(false);
const portTest = ref<InstanceType<typeof PortTestPane> | null>(null);

const PARTS = [
    { value: 'servers' as const, key: 'IDS_EM_SERVER', icon: 'SERVER' as const },
    { value: 'kad' as const, key: 'IDS_EM_KADEMLIA', icon: 'KAD' as const },
    { value: 'log' as const, key: 'IDS_SV_LOG', icon: 'LOG' as const },
];

async function recheck(): Promise<void> {
    try {
        await answer(api().POST('/kad/recheck'));
    } catch (e) {
        problem((e as Error).message);
    }
}

// The test of the ports in a popup under its button, its outcomes coming in as they are known; the button shows it
// again and tests anew, never closes it.
async function testPorts(): Promise<void> {
    testShown.value = true;
    await nextTick();
    await portTest.value?.test();
}
</script>

<template>
    <div class="screen">
        <div class="network-cards">
            <section class="network-card" :class="cards.ed2k.tone">
                <div class="card-head">
                    <span class="card-tile"><Icon name="SERVER" /></span>
                    <div>
                        <div class="card-title">eD2K</div>
                        <div class="card-headline">
                            <Icon v-if="cards.ed2k.icon" :name="cards.ed2k.icon" />{{ cards.ed2k.headline }}
                        </div>
                    </div>
                </div>
                <div class="card-detail">
                    <div v-for="line in cards.ed2k.detail" :key="line" dir="auto">{{ line }}</div>
                </div>
                <div class="card-actions">
                    <button class="button small" @click="emit('ed2k')">
                        <Icon :name="connection === 'disconnected' ? 'LINK' : 'LINK_OFF'" />{{
                            t(
                                connection === 'disconnected'
                                    ? 'IDS_MAIN_BTN_CONNECT'
                                    : connection === 'connecting'
                                      ? 'IDS_MAIN_BTN_CANCEL'
                                      : 'IDS_MAIN_BTN_DISCONNECT',
                            )
                        }}
                    </button>
                    <button class="button flat small card-other" @click="networkPart = 'servers'">
                        <Icon name="SERVER" />{{ t('nj.network.otherServer') }}
                    </button>
                </div>
            </section>
            <section class="network-card" :class="cards.kad.tone">
                <div class="card-head">
                    <span class="card-tile"><Icon name="KAD" /></span>
                    <div>
                        <div class="card-title">Kad</div>
                        <div class="card-headline">
                            <Icon v-if="cards.kad.icon" :name="cards.kad.icon" />{{ cards.kad.headline }}
                        </div>
                    </div>
                </div>
                <div class="card-detail">
                    <div v-for="line in cards.kad.detail" :key="line">{{ line }}</div>
                </div>
                <div class="card-actions">
                    <button class="button small" @click="emit('kad')">
                        <Icon :name="words.kadRuns ? 'LINK_OFF' : 'LINK'" />{{
                            t(words.kadRuns ? 'IDS_MAIN_BTN_DISCONNECT' : 'IDS_MAIN_BTN_CONNECT')
                        }}
                    </button>
                    <button class="button flat small card-other" :disabled="!words.kadRuns" @click="recheck">
                        <Icon name="SHIELD" />{{ t('IDS_KAD_RECHECKFW') }}
                    </button>
                </div>
            </section>
            <section class="network-card">
                <div class="card-head">
                    <span class="card-tile"><Icon name="PORT_TEST" /></span>
                    <div>
                        <div class="card-title">{{ t('nj.network.ports') }}</div>
                        <div class="card-headline" dir="ltr">{{ cards.ports.headline }}</div>
                    </div>
                </div>
                <div class="card-detail">
                    <div v-for="line in cards.ports.detail" :key="line">{{ line }}</div>
                </div>
                <div class="card-actions">
                    <PopoverRoot v-model:open="testShown">
                        <PopoverAnchor as-child>
                            <button class="button small" @click="testPorts">
                                <Icon name="PORT_TEST" />{{ t('IDS_STARTTEST') }}
                            </button>
                        </PopoverAnchor>
                        <PopoverPortal>
                            <PopoverContent class="menu port-test-popup" align="start" :side-offset="6">
                                <strong>{{ t('IDS_STARTTEST') }}</strong>
                                <PortTestPane ref="portTest" />
                            </PopoverContent>
                        </PopoverPortal>
                    </PopoverRoot>
                    <button class="button flat small card-other" @click="current = 'settings'">
                        <Icon name="OPTIONS" />{{ t('IDS_EM_PREFS') }}
                    </button>
                </div>
            </section>
        </div>
        <div class="segments" role="tablist" :aria-label="t('IDS_NETWORK')">
            <Tip v-for="p in PARTS" :key="p.value" :text="t(p.key)">
                <button
                    class="segment"
                    role="tab"
                    :aria-selected="networkPart === p.value"
                    @click="networkPart = p.value"
                >
                    <Icon :name="p.icon" />{{ t(p.key) }}
                </button>
            </Tip>
        </div>
        <ServersView v-if="networkPart === 'servers'" />
        <KadView v-else-if="networkPart === 'kad'" />
        <LogView v-else />
    </div>
</template>

<style scoped>
.screen {
    display: flex;
    flex-direction: column;
    gap: 10px;
    flex: 1;
    min-height: 0;
    min-width: 0;
    padding: 12px 16px;
}

/* The state of eD2K, Kad and the ports on three cards above the network's parts. */
.network-cards {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 12px;
}

.network-card {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 14px;
    border: 1px solid var(--color-border-muted);
    border-radius: 8px;
    background: var(--color-bg-default);
}

.network-card.success {
    border-color: var(--color-success-emphasis) var(--color-border-muted) var(--color-border-muted)
        var(--color-success-emphasis);
    border-inline-start-width: 3px;
}

.network-card.warning {
    border-color: var(--color-warning-emphasis) var(--color-border-muted) var(--color-border-muted)
        var(--color-warning-emphasis);
    border-inline-start-width: 3px;
}

.card-head {
    display: flex;
    align-items: center;
    gap: 14px;
}

.card-tile {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    flex: none;
    border-radius: 8px;
    background: var(--color-bg-subtle);
    --nj-icon-size: 18px;
    --nj-icon-color: var(--color-fg-muted);
}

.card-title {
    color: var(--color-fg-muted);
    font-size: 0.85em;
}

.card-headline {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 1.1em;
    font-weight: bold;
    --nj-icon-color: var(--color-fg-muted);
}

.success .card-headline {
    --nj-icon-color: var(--color-success-fg);
}

.warning .card-headline {
    --nj-icon-color: var(--color-warning-fg);
}

.connecting .card-headline {
    --nj-icon-color: var(--color-accent-fg);
}

.card-detail {
    flex: 1;
    color: var(--color-fg-muted);
    font-size: 0.92em;
    line-height: 1.45;
}

.card-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
}

.segments {
    display: inline-flex;
    align-self: flex-start;
    border: 1px solid var(--color-border-default);
    border-radius: 6px;
    overflow: hidden;
}

.segment {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 3px 12px;
    border: 0;
    background: var(--color-bg-default);
    cursor: pointer;
}

.segment[aria-selected='true'] {
    background: var(--color-accent-muted);
    box-shadow: inset 0 0 0 1px var(--color-accent-emphasis);
}

:deep(.port-test-popup) {
    display: flex;
    flex-direction: column;
    gap: 10px;
    max-width: 440px;
    padding: 12px;
}
</style>
