// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Endpoint } from './api/client';
import type { Glance } from './tray';

// What differs between the desktop app's window, where the shell (Tauri) does what the system offers, and a browser
// page of the web interface, which keeps to what a page may do.

/** What the tray's menu and MiniMule leave to the window. */
export type TrayChoice = 'full-speed' | 'slow-down' | 'connect' | 'options' | 'quit' | 'incoming';

/** The options of the desktop app that only its shell applies. */
export interface DesktopSettings {
    startMinimized: boolean;
    minimizeToTray: boolean;
    confirmExit: boolean;
    watchClipboard: boolean;
}

/** What the application menu of macOS leaves to the window, and the words of its items. */
export type AppMenuChoice = 'about' | 'settings' | 'quit';
export type AppMenuWords = Record<AppMenuChoice, string>;

export interface Platform {
    /** Whether the interface runs in the desktop app's window. */
    readonly desktop: boolean;
    /** Where the engine answers and how to prove this interface to it. */
    endpoint(): Promise<Endpoint>;
    /** A folder of the computer the engine runs on, chosen by the user; null when none is or none can be. */
    chooseFolder(title: string): Promise<string | null>;
    /**
     * A file of the computer the engine runs on, chosen by the user among those named {@code kind} with the extension
     * {@code extension}; null when none is or none can be.
     */
    chooseFile(title: string, kind: string, extension: string): Promise<string | null>;
    /** eD2K links the system hands over, such as a link clicked in a browser. */
    onLinks(handler: (links: string[]) => void): void;
    /** Opens a folder in the system's file manager; false where it cannot. */
    openFolder(path: string): Promise<boolean>;
    /** Opens a folder in the system's file manager, or fails with the system's reason. */
    showFolder(path: string): Promise<void>;
    /** Runs {@code handler} with what was chosen in the tray's menu or MiniMule that the window does itself. */
    onTray(handler: (chosen: TrayChoice) => void): void;
    /** The tray icon's tooltip, and njMule at a glance for its menu and MiniMule. */
    trayState(state: { tooltip: string; glance: Glance }): void;
    /** Plays the WAV file at {@code path} of this computer, unless a sound plays already. */
    playSound(path: string): void;
    /** Runs {@code handler} instead of closing when the window is closed, so that it asks first. */
    onCloseAsked(handler: () => void): void;
    /** Ends njMule: the engine saves and stops with the window. */
    quit(): void;
    /** Brings the window back, from the notification area too. */
    showWindow(): void;
    /** Shows {@code text} as a notification of the system while the window hides in the notification area. */
    notify(text: string): void;
    /** The options only the shell applies, and whether there is a notification area; null where none apply. */
    desktopSettings(): Promise<{ settings: DesktopSettings; tray: boolean } | null>;
    /** Keeps the options only the shell applies. */
    setDesktopSettings(settings: DesktopSettings): Promise<void>;
    /**
     * Runs {@code handler} with each new text of the clipboard while the options ask for it: whole when it starts with
     * an eD2K file link, empty otherwise.
     */
    onClipboard(handler: (text: string) => void): void;
    /** The API addresses of the remote njMules remembered on this computer, in order. */
    remoteConnections(): Promise<string[]>;
    /**
     * Opens njMule on the remote one whose API is at {@code url}, in a window of its own once that reached it, and
     * remembers the connection when asked; fails with the reason. An empty token is the one remembered.
     */
    openRemote(url: string, token: string, remember: boolean): Promise<void>;
    /** Starts the {@code player} of the options, with its {@code args}, on the data file of a download. */
    preview(player: string, args: string, file: string): Promise<void>;
    /** Opens a file of this computer with the program of its kind; false when it is not there. */
    openFile(path: string): Promise<boolean>;
    /** Shows the folder of a file of this computer, the file selected where the file manager can; false when gone. */
    revealFile(path: string): Promise<boolean>;
    /** Runs {@code handler} with what was chosen in the application menu of macOS, whose items say {@code words}. */
    onAppMenu(words: AppMenuWords, handler: (chosen: AppMenuChoice) => void): void;
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
    chooseFile: async () => null,
    onLinks: () => {},
    openFolder: async () => false,
    showFolder: async () => {
        throw new Error('');
    },
    onTray: () => {},
    trayState: () => {},
    // A page reaches no file of the engine's computer.
    playSound: () => {},
    // A page closes as the browser's tabs do; the engine goes on serving it.
    onCloseAsked: () => {},
    quit: () => {},
    showWindow: () => {},
    notify: () => {},
    // A page has no notification area, starts as the browser does and reads no clipboard on its own.
    desktopSettings: async () => null,
    setDesktopSettings: async () => {},
    onClipboard: () => {},
    // A page is already on its engine, and opens no program or file of the computer.
    remoteConnections: async () => [],
    openRemote: async () => {
        throw new Error('');
    },
    preview: async () => {
        throw new Error('');
    },
    openFile: async () => false,
    revealFile: async () => false,
    onAppMenu: () => {},
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
