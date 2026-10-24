// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { createRouter, createWebHashHistory } from 'vue-router';
import SearchScreen from './screens/SearchScreen.vue';
import TransfersScreen from './screens/TransfersScreen.vue';

// Routes after the #: the page itself stays at the address a reverse proxy may give it.
export const router = createRouter({
    history: createWebHashHistory(),
    routes: [
        { path: '/', redirect: '/transfers' },
        { path: '/transfers', component: TransfersScreen, meta: { title: 'IDS_EM_TRANS' } },
        { path: '/search', component: SearchScreen, meta: { title: 'IDS_EM_SEARCH' } },
    ],
});
