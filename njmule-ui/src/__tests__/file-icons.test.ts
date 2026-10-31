// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { extensionOf, iconPixels, type IconPixels } from '../file-icons';

describe('the system icons of the files', () => {
    let extension = '';
    let pixels: IconPixels | null = null;

    it('should take the extension after the last dot, in lower case', () => {
        whenTheExtensionOfIsTaken('Harbor.Lights.2024.MKV');
        thenTheExtensionIs('mkv');
    });

    it('should take no extension from a name without one', () => {
        whenTheExtensionOfIsTaken('README');
        thenTheExtensionIs('');
    });

    it('should take no extension made of other signs than letters and digits', () => {
        whenTheExtensionOfIsTaken('notes.tar-gz');
        thenTheExtensionIs('');
    });

    it('should turn the shell pixels into the order a canvas takes', () => {
        whenThePixelsAreRead([1, 0, 0, 0, 1, 0, 0, 0, 10, 20, 30, 255]);
        thenThePixelsAre(1, 1, [30, 20, 10, 255]);
    });

    it('should refuse pixels that do not fill the size they tell', () => {
        whenThePixelsAreRead([2, 0, 0, 0, 2, 0, 0, 0, 10, 20, 30, 255]);
        thenThereAreNoPixels();
    });

    function whenTheExtensionOfIsTaken(name: string): void {
        extension = extensionOf(name);
    }

    function whenThePixelsAreRead(bytes: number[]): void {
        pixels = iconPixels(new Uint8Array(bytes));
    }

    function thenTheExtensionIs(expected: string): void {
        expect(extension).toBe(expected);
    }

    function thenThePixelsAre(width: number, height: number, rgba: number[]): void {
        expect(pixels?.width).toBe(width);
        expect(pixels?.height).toBe(height);
        expect([...(pixels?.rgba ?? [])]).toEqual(rgba);
    }

    function thenThereAreNoPixels(): void {
        expect(pixels).toBeNull();
    }
});
