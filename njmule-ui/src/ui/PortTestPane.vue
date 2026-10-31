<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { answer, api } from '../api/client';
import { t } from '../i18n';
import { portLines, type PortTest } from '../network';
import { problem } from '../toasts';
import Icon from './Icon.vue';

// Tests our ports where they are chosen, and shows what the test finds as it goes: the router's answer through UPnP,
// then whether others reach the TCP and the UDP port. The last test the engine ran shows from the start.

const props = defineProps<{
    /** Applies the ports as the fields give them, before each test; without it the ports as set are tested. */
    apply?: () => Promise<void>;
    /** The ports chosen: a change while a test runs lets a new one start, on the ports then chosen. */
    ports?: unknown;
}>();

const POLL_MS = 1000;

const shown = ref<PortTest | null>(null);
const running = ref(false);
const startable = ref(true);
const lines = computed(() => (shown.value === null ? [] : portLines(shown.value)));
// Which reading the outcomes come from: one left for a newer test, or by a pane gone, stops.
let run = 0;

async function follow(mine: number, first: () => Promise<PortTest | null>): Promise<void> {
    try {
        let now = await first();
        while (mine === run && now !== null) {
            shown.value = now;
            if (now.done) {
                break;
            }
            running.value = true;
            await new Promise((resolve) => window.setTimeout(resolve, POLL_MS));
            if (mine !== run) {
                return;
            }
            now = await answer(api().GET('/ports/test'));
        }
    } catch (e) {
        if (mine === run) {
            problem((e as Error).message);
        }
    }
    if (mine === run) {
        running.value = false;
        startable.value = true;
    }
}

/** Applies the ports and starts the test, whose outcomes show as they come. */
async function test(): Promise<void> {
    const mine = ++run;
    running.value = true;
    startable.value = false;
    await follow(mine, async () => {
        await props.apply?.();
        return answer(api().POST('/ports/test'));
    });
}

watch(
    () => props.ports,
    () => {
        if (running.value) {
            startable.value = true;
        }
    },
    { deep: true },
);

onMounted(() => {
    if (run === 0) {
        void follow(++run, () => answer(api().GET('/ports/test')));
    }
});
onUnmounted(() => run++);

defineExpose({ test });
</script>

<template>
    <div class="port-test">
        <div class="start">
            <button class="button" :disabled="!startable" @click="test">
                <Icon name="PORT_TEST" />{{ t('IDS_STARTTEST') }}
            </button>
            <span v-if="running" class="spinner" role="progressbar" :aria-label="t('IDS_STARTTEST')" />
        </div>
        <!-- The room of the outcomes is kept from the start: the pane does not grow when they come. -->
        <div class="lines" :class="{ unseen: shown === null }">
            <div v-for="line in lines" :key="line.text" class="port-line" :class="`port-${line.kind}`">
                <Icon :name="line.icon" />{{ line.text }}
            </div>
            <template v-if="shown === null">
                <div v-for="i in 3" :key="i" class="port-line">&nbsp;</div>
            </template>
        </div>
    </div>
</template>

<style scoped>
.port-test {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.start {
    display: flex;
    align-items: center;
    gap: 10px;
}

/* A test running: a turning ring in the strong accent. */
.spinner {
    width: 16px;
    height: 16px;
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

.lines {
    display: flex;
    flex-direction: column;
    gap: 4px;
}

.lines.unseen {
    visibility: hidden;
}

/* The icon's tone tells the kind, the words say it. */
.port-line {
    display: flex;
    align-items: center;
    gap: 8px;
    --nj-icon-size: 16px;
    --nj-icon-color: var(--color-fg-muted);
}

.port-line .nj-icon {
    flex: none;
}

.port-open {
    --nj-icon-color: var(--color-success-fg);
}

.port-closed {
    --nj-icon-color: var(--color-danger-fg);
}

.port-warning {
    --nj-icon-color: var(--color-warning-fg);
}

.port-testing {
    --nj-icon-color: var(--nj-accent-strong);
}
</style>
