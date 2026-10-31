// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowReactive } from 'vue';
import type { Platform } from './platform';

// The icon the system shows for a kind of file, which the program that opens it gives (a film shows the player's),
// beside every file name of the desktop window; read once for each extension. A page, and a system that gives none,
// keep the app's own icon of the kind.

/** The longest extension asked for. */
const LONGEST = 16;

/** The extension the system's icons go by: after the last dot, letters and digits only, lower case; empty for none. */
export function extensionOf(name: string): string {
    const dot = name.lastIndexOf('.');
    const extension = dot < 0 ? '' : name.substring(dot + 1);
    return extension.length > LONGEST || !/^[\p{L}\p{N}]+$/u.test(extension) ? '' : extension.toLowerCase();
}

/** An icon of the shell: its size, and its pixels as a canvas takes them (red, green, blue, alpha). */
export interface IconPixels {
    width: number;
    height: number;
    rgba: Uint8ClampedArray<ArrayBuffer>;
}

/**
 * The pixels of an icon as the shell gives them (its width and height as two 32-bit numbers, then blue, green, red,
 * alpha for each pixel) in a canvas's order; null when they do not add up.
 */
export function iconPixels(data: Uint8Array): IconPixels | null {
    if (data.length < 8) {
        return null;
    }
    const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
    const width = view.getUint32(0, true);
    const height = view.getUint32(4, true);
    if (width === 0 || height === 0 || data.length !== 8 + width * height * 4) {
        return null;
    }
    const rgba = new Uint8ClampedArray(width * height * 4);
    for (let i = 0; i < rgba.length; i += 4) {
        rgba[i] = data[8 + i + 2]!;
        rgba[i + 1] = data[8 + i + 1]!;
        rgba[i + 2] = data[8 + i]!;
        rgba[i + 3] = data[8 + i + 3]!;
    }
    return { width, height, rgba };
}

// The icons read, by extension; an extension asked for and not there yet, or with none, is not in it.
const icons = shallowReactive(new Map<string, string>());
const asked = new Set<string>();
let platform: Platform | null = null;

/** Reads the system's icons through {@code shell}, in the desktop window only. */
export function readSystemIcons(shell: Platform): void {
    platform = shell.desktop ? shell : null;
}

/** The image of the program that opens files named as {@code name}, once read; null until then, and where there is none. */
export function systemIconOf(name: string): string | null {
    const extension = extensionOf(name);
    if (platform === null || extension === '') {
        return null;
    }
    if (!asked.has(extension)) {
        asked.add(extension);
        const reader = platform;
        void reader.fileIcon(extension).then((url) => {
            if (url !== null) {
                icons.set(extension, url);
            }
        });
    }
    return icons.get(extension) ?? null;
}
