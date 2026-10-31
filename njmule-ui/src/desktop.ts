// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type { Endpoint } from './api/client';
import type { Platform, TrayChoice } from './platform';

// The desktop app's window: the shell started the engine (or found it running) and does what the system offers.

// A notification sound lasts a few seconds: a longer one is cut.
const SOUND_MS = 30_000;
let playing = false;

// One sound at a time: one asked for while another plays is left out, as is one that cannot be read or played.
async function play(path: string): Promise<void> {
    if (playing) {
        return;
    }
    playing = true;
    let url = '';
    try {
        const wav = await invoke<ArrayBuffer>('sound', { path });
        url = URL.createObjectURL(new Blob([wav], { type: 'audio/wav' }));
        const audio = new Audio(url);
        await new Promise<void>((done) => {
            const cut = window.setTimeout(() => {
                audio.pause();
                done();
            }, SOUND_MS);
            const end = () => {
                window.clearTimeout(cut);
                done();
            };
            audio.onended = end;
            audio.onerror = end;
            audio.play().catch(end);
        });
    } catch {
        // No sound then; the notification itself shows.
    } finally {
        URL.revokeObjectURL(url);
        playing = false;
    }
}

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
    localize: (words) => void invoke('tray_texts', { words }),
    onTray: (handler) => void listen<TrayChoice>('tray', (e) => handler(e.payload)),
    trayState: (state) => void invoke('tray_state', state),
    playSound: (path) => void play(path),
    // Once the window listens, the shell leaves closing to it.
    onCloseAsked: (handler) => {
        void listen('close-requested', () => handler()).then(() => invoke('window_ready'));
    },
    quit: () => void invoke('quit'),
};
