// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './theme.css';
import { createApp } from 'vue';
import App from './App.vue';
import { followStatus } from './engine';
import { bestLanguage, loadLanguage } from './i18n';
import { router } from './router';
import { followTheme } from './theme';

followTheme();
await loadLanguage(bestLanguage(navigator.languages));
followStatus();
createApp(App).use(router).mount('#app');
