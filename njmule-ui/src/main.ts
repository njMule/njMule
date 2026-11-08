// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import './styles/themes.css';
import './styles/njmule.css';
import { createApp } from 'vue';
import App from './App.vue';
import { connect } from './api/client';
import { loadLanguage, windowLanguage } from './i18n';
import { follow } from './model/engine';
import { followChats } from './chats';
import { readSystemIcons } from './file-icons';
import { currentPlatform } from './platform';
import { guest } from './role';
import { mountOrFail, sayFailed, sayStarting } from './startup';
import { followTheme } from './theme';

const place = document.getElementById('app')!;

// The same interface in the desktop app's window and as the web interface: the platform says where the engine
// answers and how to reach it. Until it shows, the window says that njMule is starting, or why it cannot show.
followTheme();
try {
    const platform = await currentPlatform();
    await loadLanguage(windowLanguage(navigator.languages));
    // The shell's small windows of the notification area's icon show one view of the interface.
    const view = new URLSearchParams(window.location.search).get('view');
    const started = view === null ? sayStarting(place) : () => {};
    const endpoint = await platform.endpoint().finally(started);
    connect(endpoint, () => {
        // A browser's session ended: log in again. The desktop shell's token does not expire.
        if (!platform.desktop) {
            window.location.assign('login');
        }
    });
    if (view === null) {
        follow();
        // A guest may not read the chats.
        if (!guest) {
            followChats();
        }
        readSystemIcons(platform);
        mountOrFail(createApp(App, { platform }), place);
    } else {
        const { mountPopup } = await import('./popups/mount');
        mountPopup(view);
    }
} catch (e) {
    console.error(e);
    sayFailed(place, e);
}
