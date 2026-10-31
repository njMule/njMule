<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import logo from '../assets/njmule-icon.svg';
import { t } from '../i18n';
import { current, GROUPS, PLACES, shortcut, withShortcutKey, type Place } from '../places';
import Icon from '../ui/Icon.vue';
import Tip from '../ui/Tip.vue';

// The places down the side of the window: the logo and the name, a row for each place with what waits there, and the
// options at the bottom. It folds to its icons with Ctrl+B, and by itself in a narrow window.

const props = defineProps<{
    collapsed: boolean;
    counts: Partial<Record<Place, number>>;
    network: { up: boolean; trying: boolean; state: string };
}>();
const emit = defineEmits<{ toggle: [] }>();

// Where whoever likes njMule can support it.
const COFFEE = 'https://buymeacoffee.com/scoppolap';
const COFFEE_HOST = 'buymeacoffee.com/scoppolap';
const MAX_BADGE = 99;

function badge(place: Place): string {
    const value = props.counts[place] ?? 0;
    return value > MAX_BADGE ? `${MAX_BADGE}+` : String(value);
}

function tip(place: Place, title: string): string {
    const keys = shortcut(PLACES.findIndex((p) => p.id === place));
    return place === 'network' ? `${props.network.state}\n${keys}` : `${title}  ${keys}`;
}

// What waits on a place, said to a screen reader: the downloads running, the new messages, the networks' state.
function help(place: Place): string | undefined {
    const value = props.counts[place] ?? 0;
    if (place === 'network') {
        return props.network.state;
    }
    if (value === 0) {
        return undefined;
    }
    return place === 'transfers' ? t('nj.rail.active', value) : t('nj.rail.unread', value);
}

function dot(): string {
    return props.network.up ? 'connected' : props.network.trying ? 'connecting' : '';
}
</script>

<template>
    <nav class="nav-rail" :class="{ collapsed }">
        <div class="rail-head">
            <img class="rail-logo" :src="logo" alt="" width="28" height="28" />
            <template v-if="!collapsed">
                <span class="wordmark rail-wordmark">njMule</span>
                <Tip :text="`${t('nj.sidebar.collapse')}  ${withShortcutKey('B')}`">
                    <button
                        id="sidebar-collapse"
                        class="button flat icon-only rail-fold"
                        :aria-label="t('nj.sidebar.collapse')"
                        @click="emit('toggle')"
                    >
                        <Icon name="SIDEBAR" />
                    </button>
                </Tip>
            </template>
        </div>
        <Tip v-if="collapsed" :text="`${t('nj.sidebar.expand')}  ${withShortcutKey('B')}`">
            <button
                id="sidebar-expand"
                class="button flat icon-only rail-fold"
                :aria-label="t('nj.sidebar.expand')"
                @click="emit('toggle')"
            >
                <Icon name="SIDEBAR" />
            </button>
        </Tip>
        <div v-for="(group, g) in GROUPS" :key="g" class="rail-group" :class="{ bottom: g === GROUPS.length - 1 }">
            <!-- The last group keeps to the bottom, apart from the places of every day, after the coffee. -->
            <Tip v-if="g === GROUPS.length - 1" :text="COFFEE_HOST">
                <a id="rail-coffee" class="rail-item" :href="COFFEE" target="_blank" rel="noopener">
                    <span class="rail-graphic"><Icon name="COFFEE" /></span>
                    <span v-if="!collapsed" class="rail-title">Buy me a coffee</span>
                </a>
            </Tip>
            <div v-if="g > 0 && g < GROUPS.length - 1" class="rail-separator" />
            <Tip v-for="place in group" :key="place.id" :text="tip(place.id, t(place.title))">
                <button
                    :id="`rail-${place.id}`"
                    class="rail-item"
                    :class="{ selected: current === place.id }"
                    :aria-current="current === place.id ? 'page' : undefined"
                    :aria-label="collapsed ? t(place.title) : undefined"
                    :aria-description="help(place.id)"
                    @click="current = place.id"
                >
                    <span class="rail-graphic">
                        <Icon :name="place.icon" />
                        <span v-if="collapsed && (counts[place.id] ?? 0) > 0" class="rail-pip" />
                        <span v-if="collapsed && place.id === 'network'" class="network-dot corner" :class="dot()" />
                    </span>
                    <template v-if="!collapsed">
                        <span class="rail-title">{{ t(place.title) }}</span>
                        <span v-if="(counts[place.id] ?? 0) > 0" class="rail-badge">{{ badge(place.id) }}</span>
                        <span v-if="place.id === 'network'" class="network-dot" :class="dot()" />
                    </template>
                </button>
            </Tip>
        </div>
    </nav>
</template>

<style scoped>
.nav-rail {
    display: flex;
    flex-direction: column;
    gap: 2px;
    width: 200px;
    flex: none;
    padding: 12px 10px;
    background: var(--color-bg-subtle);
    border-inline-end: 1px solid var(--color-border-muted);
}

.nav-rail.collapsed {
    width: 56px;
    padding: 10px 8px;
    align-items: center;
}

.rail-head {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 2px 0 14px 6px;
}

.collapsed .rail-head {
    padding: 2px 0 6px;
}

.rail-wordmark {
    flex: 1;
    font-size: 1.3em;
}

.rail-fold {
    --nj-icon-color: var(--color-fg-muted);
}

.collapsed .rail-fold {
    margin-bottom: 8px;
}

.rail-item {
    position: relative;
    display: flex;
    align-items: center;
    gap: 12px;
    width: 100%;
    height: 36px;
    padding: 0 10px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--color-fg-default);
    text-decoration: none;
    text-align: start;
    cursor: pointer;
    --nj-icon-color: var(--color-fg-muted);
    --nj-icon-size: 20px;
}

.collapsed .rail-item {
    width: 40px;
    padding: 0;
    justify-content: center;
}

.rail-item:hover {
    background: var(--color-bg-inset);
}

/* The place shown: a raised surface, bold, its icon in the strong orange and an orange mark along the start edge. */
.rail-item.selected {
    background: var(--color-bg-default);
    box-shadow: 0 1px 3px var(--color-shadow-default);
    font-weight: bold;
    --nj-icon-color: var(--nj-accent-strong);
}

.rail-item.selected::before {
    content: '';
    position: absolute;
    inset-inline-start: -10px;
    top: 8px;
    bottom: 8px;
    width: 3px;
    border-radius: 0 3px 3px 0;
    background: var(--nj-brand-accent);
}

.collapsed .rail-item.selected::before {
    inset-inline-start: -8px;
}

.rail-graphic {
    position: relative;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 22px;
    min-height: 22px;
}

.rail-title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

/* A count on a place: running downloads, unread messages. */
.rail-badge {
    min-width: 20px;
    height: 18px;
    padding: 0 6px;
    border-radius: 9px;
    background: var(--nj-brand-accent);
    color: var(--nj-on-accent);
    font-size: 0.78em;
    font-weight: bold;
    display: inline-flex;
    align-items: center;
    justify-content: center;
}

/* The count while folded: a dot, the number in the tooltip. */
.rail-pip {
    position: absolute;
    top: 0;
    inset-inline-end: 0;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--nj-brand-accent);
    border: 1.5px solid var(--color-bg-subtle);
}

.rail-separator {
    height: 1px;
    margin: 6px 8px;
    background: var(--color-border-muted);
    align-self: stretch;
}

.rail-group {
    display: flex;
    flex-direction: column;
    gap: 2px;
    align-items: inherit;
}

.rail-group.bottom {
    margin-top: auto;
}

/* Whether a network is up; the tooltip says it in words. */
.network-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--color-fg-subtle);
    margin-inline-end: 0;
}

.network-dot.connected {
    background: var(--color-success-emphasis);
}

.network-dot.connecting {
    background: var(--color-warning-emphasis);
}

.network-dot.corner {
    position: absolute;
    top: 0;
    inset-inline-end: 0;
    border: 1.5px solid var(--color-bg-subtle);
    width: 9px;
    height: 9px;
}
</style>
