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
    openFolder: (path) =>
        invoke('open_folder', { path }).then(
            () => true,
            () => false,
        ),
    // The shell fails with the system's words, which the window shows as they are.
    showFolder: async (path) => {
        try {
            await invoke('open_folder', { path });
        } catch (e) {
            throw new Error(String(e));
        }
    },
    localize: (words) => void invoke('tray_texts', words),
    // Once the window listens, the shell leaves closing to it.
    onCloseAsked: (handler) => {
        void listen('close-requested', () => handler()).then(() => invoke('window_ready'));
    },
    quit: () => void invoke('quit'),
};
