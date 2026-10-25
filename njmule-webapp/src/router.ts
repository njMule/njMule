// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { createRouter, createWebHashHistory } from 'vue-router';
import { allowed } from './role';
import MessagesScreen from './screens/MessagesScreen.vue';
import NetworkScreen from './screens/NetworkScreen.vue';
import SearchScreen from './screens/SearchScreen.vue';
import SettingsScreen from './screens/SettingsScreen.vue';
import SharedScreen from './screens/SharedScreen.vue';
import StatisticsScreen from './screens/StatisticsScreen.vue';
import TransfersScreen from './screens/TransfersScreen.vue';

// Routes after the #: the page itself stays at the address a reverse proxy may give it.
export const router = createRouter({
    history: createWebHashHistory(),
    routes: [
        { path: '/', redirect: '/transfers' },
        { path: '/transfers', component: TransfersScreen, meta: { title: 'IDS_EM_TRANS' } },
        { path: '/search', component: SearchScreen, meta: { title: 'IDS_EM_SEARCH' } },
        { path: '/shared', component: SharedScreen, meta: { title: 'IDS_EM_FILES' } },
        { path: '/messages', component: MessagesScreen, meta: { title: 'IDS_EM_MESSAGES' } },
        { path: '/network/:part?', component: NetworkScreen, meta: { title: 'IDS_NETWORK' } },
        { path: '/statistics', component: StatisticsScreen, meta: { title: 'IDS_EM_STATISTIC' } },
        { path: '/settings', component: SettingsScreen, meta: { title: 'IDS_EM_PREFS' } },
    ],
});

// A guest's page opens only the places it may look at.
router.beforeEach((to) => (allowed(to.path) ? true : '/transfers'));
