// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ElMessage, ElMessageBox } from 'element-plus';
import { t } from './i18n';

/**
 * Puts {@code text} in the clipboard. Browsers allow it only on secure pages (https or this computer); elsewhere the
 * text is shown to be copied by hand.
 */
export async function copy(text: string, title: string): Promise<void> {
    try {
        await navigator.clipboard.writeText(text);
        ElMessage({ message: title, type: 'success' });
    } catch {
        await ElMessageBox.alert(text, title, { customClass: 'nj-copy-box', confirmButtonText: t('IDS_FD_CLOSE') });
    }
}
