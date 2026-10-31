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
    PopoverRoot,
    PopoverTrigger,
} from 'reka-ui';
import { onMounted, onUnmounted, ref } from 'vue';
import { showAbout } from '../about';
import { rate } from '../format';
import { t } from '../i18n';
import { rates, status } from '../model/engine';
import type { NetworkWords } from '../network';
import { current, withShortcutKey } from '../places';
import { guest } from '../role';
import { toggleDark } from '../theme';
import { checkNow } from '../updates';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';
import NetworkControl from './NetworkControl.vue';
import Sparkline from './Sparkline.vue';
import SpeedLimits from './SpeedLimits.vue';

// The top bar: the screen's title and summary, the field to search or paste links, the rates, the networks, More.
defineProps<{ title: string; summary: string; narrow: boolean; words: NetworkWords; canOpenFolders: boolean }>();
const emit = defineEmits<{
    palette: [typed: string];
    ed2k: [];
    kad: [];
    both: [];
    shortcuts: [];
    incoming: [];
    /** The bar cannot show all it holds at its width. */
    crowded: [];
    import: [];
    wizard: [];
}>();
const moreTrigger = ref<InstanceType<typeof DropdownMenuTrigger> | null>(null);
const ratesTrigger = ref<InstanceType<typeof PopoverTrigger> | null>(null);
const limitsOpen = ref(false);
const bar = ref<HTMLElement | null>(null);
const titleBox = ref<HTMLElement | null>(null);
// The title is never cut: the summary gives way first.
const titleWidth = ref(0);
let observer: ResizeObserver | null = null;

onMounted(() => {
    observer = new ResizeObserver(() => {
        titleWidth.value = Math.ceil(titleBox.value?.getBoundingClientRect().width ?? 0);
        const b = bar.value;
        if (b !== null && b.scrollWidth > b.clientWidth) {
            emit('crowded');
        }
    });
    for (const watched of [bar.value, titleBox.value, ...(bar.value?.children ?? [])]) {
        if (watched) {
            observer.observe(watched);
        }
    }
});
onUnmounted(() => observer?.disconnect());

// A letter typed on the field starts the text of the palette.
function typed(e: KeyboardEvent): void {
    if (e.key.length === 1 && e.key.trim() !== '' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        emit('palette', e.key);
        e.preventDefault();
    }
}
</script>

<template>
    <header ref="bar" class="top-bar">
        <div class="heading" :style="{ minWidth: `${titleWidth}px` }">
            <h1 ref="titleBox" class="screen-title display">{{ title }}</h1>
            <div v-if="summary && !narrow" class="screen-summary ellipsis">{{ summary }}</div>
        </div>
        <div class="spacer" />
        <Tip :text="`${t('nj.palette.prompt')}  ${withShortcutKey('K')}`">
            <button
                id="find"
                class="universal-field"
                :class="{ narrow }"
                :aria-label="t('nj.palette.prompt')"
                @click="emit('palette', '')"
                @keydown="typed"
            >
                <Icon name="SEARCH" />
                <span v-if="!narrow" class="prompt ellipsis">{{ t('nj.palette.prompt') }}</span>
                <span class="shortcut-key">{{ withShortcutKey('K') }}</span>
            </button>
        </Tip>
        <div class="spacer" />
        <!-- The rates open the limits, what one wants to change on seeing them. -->
        <PopoverRoot v-model:open="limitsOpen">
            <Tip :text="t('nj.tray.speed')">
                <PopoverTrigger
                    id="rates"
                    ref="ratesTrigger"
                    class="button flat rates"
                    :aria-label="`${t('nj.tray.speed')}: ↓ ${rate(status?.downloadBytesPerSecond ?? 0)}, ↑ ${rate(status?.uploadBytesPerSecond ?? 0)}`"
                >
                    <Sparkline v-if="!narrow" :rates="rates" />
                    <span class="rate-numbers">
                        <span class="rate-label rate-down"
                            ><Icon name="ARROW_DOWN" />{{ rate(status?.downloadBytesPerSecond ?? 0) }}</span
                        >
                        <span v-if="!narrow" class="rate-label rate-up"
                            ><Icon name="ARROW_UP" />{{ rate(status?.uploadBytesPerSecond ?? 0) }}</span
                        >
                    </span>
                </PopoverTrigger>
            </Tip>
            <SpeedLimits :open="limitsOpen" side="bottom" :reference="ratesTrigger?.$el" />
        </PopoverRoot>
        <NetworkControl :words="words" :compact="narrow" @ed2k="emit('ed2k')" @kad="emit('kad')" @both="emit('both')" />
        <DropdownMenuRoot>
            <Tip :text="t('IDS_MORE')">
                <DropdownMenuTrigger
                    id="more"
                    ref="moreTrigger"
                    class="button flat icon-only more"
                    :aria-label="t('IDS_MORE')"
                >
                    <Icon name="MORE" />
                </DropdownMenuTrigger>
            </Tip>
            <DropdownMenuPortal>
                <DropdownMenuContent class="menu" align="end" :side-offset="6" :reference="moreTrigger?.$el">
                    <DropdownMenuItem
                        id="incoming"
                        class="menu-item"
                        :disabled="!canOpenFolders"
                        @select="emit('incoming')"
                    >
                        <Icon name="FOLDER_OPEN" />{{ t('IDS_OPENINC') }}
                    </DropdownMenuItem>
                    <DropdownMenuItem id="options" class="menu-item" @select="current = 'settings'">
                        <Icon name="OPTIONS" />{{ t('IDS_EM_PREFS') }}
                    </DropdownMenuItem>
                    <DropdownMenuItem id="wizard" class="menu-item" :disabled="guest" @select="emit('wizard')">
                        <Icon name="WIZARD" />{{ t('nj.wizard.title') }}
                    </DropdownMenuItem>
                    <DropdownMenuItem id="import" class="menu-item" :disabled="guest" @select="emit('import')">
                        <Icon name="IMPORT" />{{ t('nj.import.title') }}
                    </DropdownMenuItem>
                    <DropdownMenuItem id="remote" class="menu-item" disabled>
                        <Icon name="LINK" />{{ t('nj.remote.title') }}
                    </DropdownMenuItem>
                    <DropdownMenuSeparator class="menu-separator" />
                    <DropdownMenuItem id="theme" class="menu-item" @select="toggleDark()">
                        <Icon name="THEME" />{{ t('nj.top.theme') }}
                    </DropdownMenuItem>
                    <DropdownMenuItem id="shortcuts" class="menu-item" @select="emit('shortcuts')">
                        <Icon name="KEYBOARD" />{{ t('nj.shortcuts.title') }}<span class="menu-end keys">F1</span>
                    </DropdownMenuItem>
                    <DropdownMenuItem id="updates" class="menu-item" @select="checkNow()">
                        <Icon name="REFRESH" />{{ t('IDS_CHECK4UPDATE') }}
                    </DropdownMenuItem>
                    <DropdownMenuItem id="about" class="menu-item" @select="showAbout()">
                        <Icon name="INFO" />{{ t('nj.about.title') }}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenuPortal>
        </DropdownMenuRoot>
    </header>
</template>

<style scoped>
.top-bar {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 60px;
    padding: 10px 20px;
    background: var(--color-bg-default);
    border-bottom: 1px solid var(--color-border-muted);
}

.heading {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;
    flex-shrink: 1;
}

/* As wide as its words, which give the heading its smallest width. */
.screen-title {
    width: max-content;
    margin: 0;
    font-size: 1.55em;
    line-height: 1.2;
    white-space: nowrap;
}

.screen-summary {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.spacer {
    flex: 1;
    min-width: 0;
}

/* A button drawn as a field: it opens the palette in its place. */
.universal-field {
    display: flex;
    align-items: center;
    gap: 8px;
    flex: 1 1 200px;
    max-width: 460px;
    min-width: 0;
    height: 36px;
    padding: 0 8px 0 10px;
    border: 1px solid var(--color-border-subtle);
    border-radius: 8px;
    background: var(--color-bg-subtle);
    color: var(--color-fg-muted);
    cursor: text;
    --nj-icon-color: var(--color-fg-muted);
}

.universal-field:hover {
    background: var(--color-bg-inset);
}

.universal-field.narrow {
    flex: none;
}

.universal-field .prompt {
    flex: 1;
    text-align: start;
}

.rates {
    gap: 10px;
    padding: 0 10px;
}

.rate-numbers {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    line-height: 1.2;
}

/* The rates in a box wide enough for 100 MB/s and aligned to its end: the bar does not shift as they change. */
.rate-label {
    display: inline-flex;
    align-items: center;
    justify-content: flex-end;
    gap: 2px;
    min-width: 6.5em;
    --nj-icon-size: 14px;
}

.rate-down {
    font-weight: bold;
    --nj-icon-color: var(--nj-graph-down);
}

.rate-up {
    color: var(--color-fg-muted);
    font-size: 0.92em;
}

.more {
    --nj-icon-size: 20px;
}
</style>
