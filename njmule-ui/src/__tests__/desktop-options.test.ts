// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { DESKTOP_DEFAULTS, type DesktopOption, isDesktopOption, usable } from '../desktop-options';
import type { DesktopSettings } from '../platform';

describe('the options only the desktop app applies', () => {
    let settings: DesktopSettings | null = null;
    let tray = false;
    let applies = false;

    it('should apply none in a browser', () => {
        givenABrowser();
        whenAskedWhetherItApplies('confirmExit');
        thenItApplies(false);
    });

    it('should not hide in a notification area the system lacks', () => {
        givenTheDesktopApp(false);
        whenAskedWhetherItApplies('minimizeToTray');
        thenItApplies(false);
    });

    it('should start minimised where there is no notification area', () => {
        givenTheDesktopApp(false);
        whenAskedWhetherItApplies('startMinimized');
        thenItApplies(true);
    });

    it('should hide in the notification area where there is one', () => {
        givenTheDesktopApp(true);
        whenAskedWhetherItApplies('minimizeToTray');
        thenItApplies(true);
    });

    it('should take the look of the window for an option of its own', () => {
        whenAskedWhetherTheShellKeeps('theme');
        thenItApplies(false);
    });

    it('should take watching the clipboard for an option of the shell', () => {
        whenAskedWhetherTheShellKeeps('watchClipboard');
        thenItApplies(true);
    });

    function givenABrowser(): void {
        settings = null;
        tray = false;
    }

    function givenTheDesktopApp(withTray: boolean): void {
        settings = { ...DESKTOP_DEFAULTS };
        tray = withTray;
    }

    function whenAskedWhetherItApplies(option: DesktopOption): void {
        applies = usable(option, settings, tray);
    }

    function whenAskedWhetherTheShellKeeps(local: string): void {
        applies = isDesktopOption(local);
    }

    function thenItApplies(expected: boolean): void {
        expect(applies).toBe(expected);
    }
});
