// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Endpoint } from './api/client';

// What differs between the desktop app's window, where the shell (Tauri) does what the system offers, and a browser
// page of the web interface, which keeps to what a page may do.

export interface Platform {
    /** Whether the interface runs in the desktop app's window. */
    readonly desktop: boolean;
    /** Where the engine answers and how to prove this interface to it. */
    endpoint(): Promise<Endpoint>;
    /** A folder of the computer the engine runs on, chosen by the user; null when none is or none can be. */
    chooseFolder(title: string): Promise<string | null>;
    /** eD2K links the system hands over, such as a link clicked in a browser. */
    onLinks(handler: (links: string[]) => void): void;
    /** Opens a folder in the system's file manager; false where it cannot. */
    openFolder(path: string): Promise<boolean>;
    /** Opens a folder in the system's file manager, or fails with the system's reason. */
    showFolder(path: string): Promise<void>;
    /** Words the shell shows itself, such as its tray's menu, in the interface's language. */
    localize(words: { show: string; exit: string }): void;
    /** Runs {@code handler} instead of closing when the window is closed, so that it asks first. */
    onCloseAsked(handler: () => void): void;
    /** Ends njMule: the engine saves and stops with the window. */
    quit(): void;
}

const browser: Platform = {
    desktop: false,
    endpoint: async () =>
        // A development server may reach an engine with a token instead of a login session.
        import.meta.env.VITE_NJMULE_TOKEN
            ? { url: 'api/v1', token: import.meta.env.VITE_NJMULE_TOKEN as string }
            : {
                  url: 'api/v1',
                  csrf: document.querySelector<HTMLMetaElement>('meta[name="njmule-csrf"]')?.content ?? '',
              },
    // A page reaches no folder: the user types the path of the engine's computer.
    chooseFolder: async () => null,
    onLinks: () => {},
    openFolder: async () => false,
    showFolder: async () => {
        throw new Error('');
    },
    localize: () => {},
    // A page closes as the browser's tabs do; the engine goes on serving it.
    onCloseAsked: () => {},
    quit: () => {},
};

/** Whether the engine at the API address {@code url} runs on this computer: its folders are ours to open. */
export function engineHere(url: string): boolean {
    try {
        const host = new URL(url, window.location.href).hostname;
        return host === '127.0.0.1' || host === 'localhost' || host === '[::1]';
    } catch {
        return false;
    }
}

/** Whether the page runs in the desktop shell's window. */
export function inDesktopShell(): boolean {
    return '__TAURI_INTERNALS__' in window;
}

/** The platform the interface runs on; the shell's own module is loaded only there. */
export async function currentPlatform(): Promise<Platform> {
    if (!inDesktopShell()) {
        return browser;
    }
    const { desktop } = await import('./desktop');
    return desktop;
}
