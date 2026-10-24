// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { onMounted, onUnmounted, ref, type Ref } from 'vue';
import { api, ApiError } from './api';
import type { Status } from './types';

/** The engine's state, read once a second for the top bar and every screen. */
export const status = ref<Status | null>(null);

/** Why the engine could not be read, empty while it answers. */
export const unreachable = ref('');

/** The rates of each second since the page opened, at most an hour of them. */
export const rates = ref<{ down: number; up: number }[]>([]);
const RATE_SAMPLES = 3600;

async function readStatus(): Promise<void> {
    try {
        status.value = await api.get<Status>('/status');
        unreachable.value = '';
        rates.value = [
            ...rates.value.slice(-(RATE_SAMPLES - 1)),
            { down: status.value.downloadBytesPerSecond, up: status.value.uploadBytesPerSecond },
        ];
    } catch (e) {
        unreachable.value = e instanceof ApiError ? e.message : String(e);
    }
}

export function followStatus(): void {
    void readStatus();
    window.setInterval(() => void readStatus(), 1000);
}

/** {@code load}'s answer, read again every {@code ms} while the component is shown. */
export function usePoll<T>(
    load: () => Promise<T>,
    ms: number,
    initial: T,
): { data: Ref<T>; refresh: () => Promise<void> } {
    const data = ref(initial) as Ref<T>;
    let timer = 0;
    const refresh = async () => {
        try {
            data.value = await load();
        } catch {
            // The top bar tells when the engine does not answer.
        }
    };
    onMounted(() => {
        void refresh();
        timer = window.setInterval(() => void refresh(), ms);
    });
    onUnmounted(() => window.clearInterval(timer));
    return { data, refresh };
}
