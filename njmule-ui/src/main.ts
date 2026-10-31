// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import '@generated/themes.css';
import './styles/njmule.css';
import { createApp } from 'vue';
import App from './App.vue';
import { connect } from './api/client';
import { loadLanguage, t, windowLanguage } from './i18n';
import { follow } from './model/engine';
import { followChats } from './chats';
import { currentPlatform } from './platform';
import { followTheme } from './theme';

// The same interface in the desktop app's window and as the web interface: the platform says where the engine
// answers and how to reach it.
const platform = await currentPlatform();
followTheme();
// The desktop app's window has no menu of the web view's own (back, reload, inspect…): text fields keep theirs, as the
// desktop app's do, and the lists that have a menu open it themselves before this.
if (platform.desktop) {
    document.addEventListener('contextmenu', (e) => {
        if (!(e.target instanceof Element && e.target.closest('input, textarea, [contenteditable="true"]'))) {
            e.preventDefault();
        }
    });
}
connect(await platform.endpoint(), () => {
    // A browser's session ended: log in again. The desktop shell's token does not expire.
    if (!platform.desktop) {
        window.location.assign('login');
    }
});
await loadLanguage(windowLanguage(navigator.languages));
platform.localize({ show: t('nj.tray.show'), exit: t('IDS_EXIT') });
follow();
followChats();
createApp(App, { platform }).mount('#app');
