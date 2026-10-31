// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { bestLanguage, keepLanguage, loadLanguage } from './i18n';

/**
 * Speaks {@code tag} from now on, empty for the best of ours for the system's languages ({@code wanted}): the
 * window's words at once, and the next windows too, the shell's small windows of the tray among them.
 */
export async function switchLanguage(tag: string, wanted: readonly string[] = navigator.languages): Promise<void> {
    keepLanguage(tag);
    await loadLanguage(tag === '' ? bestLanguage(wanted) : tag);
}
