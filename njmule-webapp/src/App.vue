<!--
SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
SPDX-License-Identifier: GPL-3.0-or-later
-->
<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import NjIcon from './components/NjIcon.vue';
import { status, unreachable } from './engine';
import { rate } from './format';
import { t } from './i18n';
import type { IconName } from './icons';
import { mode, setMode } from './theme';

interface Place {
    title: string;
    icon: IconName;
    // A route of the app, or a page of the web interface not moved into the app yet.
    route?: string;
    page?: string;
}

const groups: Place[][] = [
    [
        { title: 'IDS_EM_TRANS', icon: 'DOWNLOAD', route: '/transfers' },
        { title: 'IDS_EM_SEARCH', icon: 'SEARCH', page: 'search' },
        { title: 'IDS_EM_FILES', icon: 'SHARED', page: 'shared' },
    ],
    [
        { title: 'IDS_NETWORK', icon: 'NETWORK', page: 'servers' },
        { title: 'IDS_EM_STATISTIC', icon: 'STATISTICS', page: 'statistics' },
    ],
    [{ title: 'IDS_EM_PREFS', icon: 'OPTIONS', page: 'settings' }],
];

const route = useRoute();
const title = computed(() => t(String(route.meta.title ?? 'IDS_EM_TRANS')));

const connected = computed(
    () => status.value !== null && (status.value.server !== null || !!status.value.kad?.connected),
);

function nextMode(): void {
    setMode(mode.value === 'system' ? 'dark' : mode.value === 'dark' ? 'light' : 'system');
}
</script>

<template>
    <div class="shell">
        <nav class="sidebar">
            <div class="brand">
                <img :src="'favicon.svg'" alt="" width="28" height="28" />
                <span>njMule</span>
            </div>
            <ul v-for="(group, i) in groups" :key="i">
                <li v-for="place in group" :key="place.title">
                    <router-link v-if="place.route" :to="place.route" class="place" active-class="current">
                        <NjIcon :name="place.icon" />{{ t(place.title) }}
                    </router-link>
                    <a v-else :href="place.page" class="place"><NjIcon :name="place.icon" />{{ t(place.title) }}</a>
                </li>
            </ul>
            <form method="post" action="logout" class="logout">
                <input type="hidden" name="csrf" :value="csrfToken" />
                <button class="place" type="submit"><NjIcon name="EXIT" />{{ t('IDS_WEB_LOGOUT') }}</button>
            </form>
        </nav>
        <div class="frame">
            <header class="topbar">
                <h1>{{ title }}</h1>
                <span class="grow"></span>
                <span v-if="status" class="rates" :title="t('nj.tray.speed')">
                    <NjIcon name="ARROW_DOWN" />{{ rate(status.downloadBytesPerSecond) }} <NjIcon name="ARROW_UP" />{{
                        rate(status.uploadBytesPerSecond)
                    }}
                </span>
                <span class="nj-chip" :class="connected ? 'ok' : 'warn'">
                    <NjIcon name="DOT" />{{ connected ? t('IDS_CONNECTED') : t('IDS_NOTCONNECTED') }}
                </span>
                <button class="mode" type="button" :title="t('nj.top.theme')" @click="nextMode">
                    <NjIcon name="THEME" :label="t('nj.top.theme')" />
                </button>
            </header>
            <p v-if="unreachable" class="unreachable" role="alert">{{ unreachable }}</p>
            <main>
                <router-view />
            </main>
        </div>
    </div>
</template>

<script lang="ts">
const csrfToken = document.querySelector<HTMLMetaElement>('meta[name="njmule-csrf"]')?.content ?? '';
</script>

<style scoped>
.shell {
    display: grid;
    grid-template-columns: 216px minmax(0, 1fr);
    min-height: 100vh;
}
.sidebar {
    position: sticky;
    top: 0;
    height: 100vh;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 14px 10px;
    background: var(--el-bg-color);
    border-right: 1px solid var(--el-border-color);
}
.brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 2px 10px 12px;
    color: var(--nj-brand);
    font-weight: 700;
    font-size: 18px;
}
.sidebar ul {
    list-style: none;
    margin: 0;
    padding: 0 0 6px;
    border-bottom: 1px solid var(--el-border-color);
}
.place {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    width: 100%;
    padding: 7px 10px;
    margin: 1px 0;
    border: 0;
    border-radius: 8px;
    background: none;
    color: var(--el-text-color-primary);
    font: inherit;
    text-align: start;
    text-decoration: none;
    cursor: pointer;
}
.place:hover {
    background: var(--nj-subtle);
}
.place.current {
    background: var(--el-fill-color);
    font-weight: 600;
}
.place.current::before {
    content: '';
    position: absolute;
    inset-inline-start: -10px;
    top: 6px;
    bottom: 6px;
    width: 3px;
    border-radius: 2px;
    background: var(--nj-accent);
}
.logout {
    margin-top: auto;
}
.frame {
    display: flex;
    flex-direction: column;
    min-width: 0;
}
.topbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px 18px;
    padding: 12px 20px;
    background: var(--el-bg-color);
    border-bottom: 1px solid var(--el-border-color);
}
.topbar h1 {
    margin: 0;
    font-size: 20px;
}
.grow {
    flex: 1;
}
.rates {
    display: flex;
    align-items: center;
    gap: 4px;
    color: var(--el-text-color-secondary);
    font-variant-numeric: tabular-nums;
}
.mode {
    display: flex;
    padding: 4px;
    border: 0;
    border-radius: 6px;
    background: none;
    color: var(--el-text-color-primary);
    cursor: pointer;
}
.unreachable {
    margin: 12px 20px 0;
    padding: 10px 14px;
    border-radius: 8px;
    background: var(--nj-error-bg);
    color: var(--nj-error-fg);
}
main {
    padding: 18px 20px 28px;
}
@media (max-width: 760px) {
    .shell {
        grid-template-columns: 1fr;
    }
    .sidebar {
        position: static;
        height: auto;
    }
}
</style>
