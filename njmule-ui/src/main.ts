// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import '@generated/themes.css';
import './styles/njmule.css';
import { createApp } from 'vue';
import App from './App.vue';
import { connect } from './api/client';
import { loadLanguage, windowLanguage } from './i18n';
import { trayWords } from './language';
import { follow } from './model/engine';
import { followChats } from './chats';
import { currentPlatform } from './platform';
import { followTheme } from './theme';

// The same interface in the desktop app's window and as the web interface: the platform says where the engine
// answers and how to reach it.
const platform = await currentPlatform();
followTheme();
connect(await platform.endpoint(), () => {
    // A browser's session ended: log in again. The desktop shell's token does not expire.
    if (!platform.desktop) {
        window.location.assign('login');
    }
});
await loadLanguage(windowLanguage(navigator.languages));
platform.localize(trayWords());
follow();
followChats();
createApp(App, { platform }).mount('#app');
