// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ElMessageBox } from 'element-plus';
import { api, ApiError } from './api';
import { t } from './i18n';
import { failed } from './notify';

/**
 * Shares a folder of the computer njMule runs on; one that may hold personal or system files is shared only after a
 * second question. Answers whether it is shared.
 */
export async function shareFolder(path: string, subdirectories: boolean): Promise<boolean> {
    try {
        await api.post('/shared/directories', { path, subdirectories });
        return true;
    } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 409) {
            failed(e);
            return false;
        }
    }
    try {
        await ElMessageBox.confirm(
            `${path}\n\n${t('nj.shared.sensitiveText')}\n${t('nj.shared.asksAlso')}`,
            t('nj.shared.shareAllTitle'),
            {
                type: 'warning',
                confirmButtonText: t('nj.shared.shareAll'),
                cancelButtonText: t('nj.shared.chooseOther'),
                confirmButtonClass: 'el-button--danger',
            },
        );
    } catch {
        return false;
    }
    try {
        await api.post('/shared/directories', { path, subdirectories, everything: true });
        return true;
    } catch (e) {
        failed(e);
        return false;
    }
}
