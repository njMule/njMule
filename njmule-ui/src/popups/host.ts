// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { watch } from 'vue';
import { completedCount, connection, follow, rates, status } from '../model/engine';
import { inDesktopShell, type TrayChoice } from '../platform';
import { glanceOf, type Glance } from '../tray';

// The shell around the small windows of the notification area's icon: it opens them where they belong and hands on
// what the main window tells of njMule and what is chosen in them. In a browser they show as a preview, reading the
// engine themselves.

/** What a small window of the icon may ask, and what it hears. */
export interface PopupHost {
    /** njMule at a glance, as the main window last told it; null until it did. */
    glance(): Promise<Glance | null>;
    /** Runs {@code handler} at each change while the window shows. */
    onGlance(handler: (glance: Glance) => void): void;
    /** Runs {@code handler} at once and each time the window is to open: the page then draws what it shows now. */
    onShow(handler: () => void): void;
    /** The page drew itself, {@code width} × {@code height} CSS pixels: the window opens where it was asked for. */
    ready(width: number, height: number): void;
    /** The window goes: Escape, or an item of the menu chosen. */
    done(): void;
    /** What was chosen: the main window shown, or what it does itself. */
    choose(choice: TrayChoice | 'show'): void;
    /** Shows {@code text} as a notification of the system, where the user looks while the main window hides. */
    notify(text: string): void;
}

const shell: PopupHost = {
    glance: () => invoke<Glance | null>('glance'),
    onGlance: (handler) => void listen<Glance>('glance', (e) => handler(e.payload)),
    onShow: (handler) => {
        void listen('popup-show', () => handler());
        handler();
    },
    // The pixels of the screen each CSS pixel takes, which the system's text size grows too.
    ready: (width, height) => void invoke('popup_ready', { width, height, ratio: window.devicePixelRatio }),
    done: () => void invoke('popup_done'),
    choose: (choice) => void invoke('tray_choice', { choice }),
    notify: (text) => void invoke('notify', { text }),
};

function current(): Glance {
    const online = connection.value === 'connected' || status.value?.kad?.connected === true;
    return glanceOf(connection.value, online, status.value, completedCount.value, rates.value);
}

// A page of its own in a browser, to look at the views: it reads the engine as the window does, and acts on nothing.
const preview: PopupHost = {
    glance: async () => current(),
    onGlance: (handler) => {
        follow();
        watch([connection, status, completedCount, rates], () => handler(current()));
    },
    onShow: (handler) => handler(),
    ready: () => {},
    done: () => {},
    choose: () => {},
    notify: () => {},
};

export function popupHost(): PopupHost {
    return inDesktopShell() ? shell : preview;
}
