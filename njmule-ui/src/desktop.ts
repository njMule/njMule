// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type { Endpoint } from './api/client';
import { t } from './i18n';
import type { DesktopSettings, Platform, TrayChoice } from './platform';
import { unreachable } from './remote';
import { message } from './toasts';

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

// The shell fails with its own or the system's words, which the window shows as they are.
function saying<T>(call: Promise<T>): Promise<T> {
    return call.catch((e: unknown) => {
        throw new Error(String(e));
    });
}

// Whether the window shows another computer's njMule, whose folders this computer's dialogs cannot choose.
let elsewhere = false;

// A window on another computer's njMule shows once it reached it; otherwise the shell tells why and ends.
async function reached(): Promise<Endpoint> {
    let found: Endpoint & { remote: boolean };
    try {
        found = await invoke<Endpoint & { remote: boolean }>('endpoint');
    } catch (e) {
        void invoke('connected', { problem: String(e) });
        throw e;
    }
    elsewhere = found.remote;
    const problem = found.remote ? await unreachable(found) : null;
    void invoke('connected', { problem });
    if (problem !== null) {
        throw new Error(problem);
    }
    return { url: found.url, token: found.token };
}

export const desktop: Platform = {
    desktop: true,
    endpoint: reached,
    chooseFolder: async (title) => {
        if (elsewhere) {
            message(t('nj.remote.elsewhere'));
            return null;
        }
        return invoke<string | null>('choose_folder', { title });
    },
    chooseFile: (title, kind, extension) => invoke<string | null>('choose_file', { title, kind, extension }),
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
    onTray: (handler) => void listen<TrayChoice>('tray', (e) => handler(e.payload)),
    trayState: (state) => void invoke('tray_state', state),
    playSound: (path) => void play(path),
    // Once the window listens, the shell leaves closing to it.
    onCloseAsked: (handler) => {
        void listen('close-requested', () => handler()).then(() => invoke('window_ready'));
    },
    quit: () => void invoke('quit'),
    showWindow: () => void invoke('show_main'),
    notify: (text) => void invoke('notify', { text }),
    desktopSettings: () => invoke<{ settings: DesktopSettings; tray: boolean }>('desktop_settings'),
    setDesktopSettings: (settings) => invoke('set_desktop_settings', { settings }),
    onClipboard: (handler) => void listen<string>('clipboard', (e) => handler(e.payload)),
    remoteConnections: () => invoke<string[]>('remote_connections'),
    openRemote: (url, token, remember) => saying(invoke<void>('open_remote', { url, token, remember })),
};
