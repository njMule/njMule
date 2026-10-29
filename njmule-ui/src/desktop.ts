// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type { Endpoint } from './api/client';
import type { Platform } from './platform';

// The desktop app's window: the shell started the engine (or found it running) and does what the system offers.

export const desktop: Platform = {
    desktop: true,
    endpoint: () => invoke<Endpoint>('endpoint'),
    chooseFolder: (title) => invoke<string | null>('choose_folder', { title }),
    onLinks: (handler) => {
        void listen<string[]>('links', (e) => handler(e.payload));
        // Links the shell received before the window listened.
        void invoke<string[]>('pending_links').then((links) => {
            if (links.length > 0) {
                handler(links);
            }
        });
    },
    openFolder: (path) => invoke<boolean>('open_folder', { path }),
    localize: (words) => void invoke('tray_texts', words),
};
