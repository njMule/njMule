// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { bestLanguage, keepLanguage, loadLanguage, t } from './i18n';
import type { Platform, TrayWords } from './platform';

/** The words of the shell's tray menu, in the interface's language. */
export function trayWords(): TrayWords {
    return {
        fullSpeed: t('nj.tray.fullSpeed'),
        slowDown: t('nj.tray.slowDown'),
        show: t('nj.tray.show'),
        options: t('IDS_EM_PREFS'),
        quit: t('nj.exit.quit'),
    };
}

/**
 * Speaks {@code tag} from now on, empty for the best of ours for the system's languages ({@code wanted}): the
 * window's words at once, the shell's tray with them, and the next windows too.
 */
export async function switchLanguage(
    tag: string,
    platform: Platform,
    wanted: readonly string[] = navigator.languages,
): Promise<void> {
    keepLanguage(tag);
    await loadLanguage(tag === '' ? bestLanguage(wanted) : tag);
    platform.localize(trayWords());
}
