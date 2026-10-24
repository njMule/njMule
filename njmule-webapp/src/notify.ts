// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ElMessage } from 'element-plus';
import { ApiError } from './api';

/** A failure, shown until it is closed, as the desktop app's problems are. */
export function failed(e: unknown): void {
    const message = e instanceof ApiError || e instanceof Error ? e.message : String(e);
    ElMessage({ message, type: 'error', duration: 0, showClose: true });
}

/** A plain message, shown for a few seconds. */
export function told(message: string, type: 'success' | 'info' = 'info'): void {
    ElMessage({ message, type, duration: 6000 });
}

/** Runs {@code work}, showing its failure. */
export async function attempt(work: () => Promise<unknown>): Promise<boolean> {
    try {
        await work();
        return true;
    } catch (e) {
        failed(e);
        return false;
    }
}
