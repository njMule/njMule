// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ElNotification } from 'element-plus';
import type { Router } from 'vue-router';
import { api } from './api';
import { t } from './i18n';

// The engine's events the app tells of as the desktop app does: a download finished, a message while elsewhere.
const TYPES = 'download.completed,message.received,preferences.changed';

let sound = false;

async function readSound(): Promise<void> {
    try {
        sound = (await api.get<Record<string, string>>('/preferences'))['notify.sound'] === 'true';
    } catch {
        // Kept as it was.
    }
}

/** A short two-note chime, made by the browser: the sound file of the preferences lies on njMule's computer. */
export function chime(): void {
    try {
        const audio = new AudioContext();
        [880, 1320].forEach((frequency, i) => {
            const tone = audio.createOscillator();
            const volume = audio.createGain();
            const start = audio.currentTime + i * 0.15;
            tone.frequency.value = frequency;
            volume.gain.setValueAtTime(0.15, start);
            volume.gain.exponentialRampToValueAtTime(0.001, start + 0.3);
            tone.connect(volume).connect(audio.destination);
            tone.start(start);
            tone.stop(start + 0.3);
        });
    } catch {
        // A browser that plays nothing yet, before the page was used.
    }
}

function tell(message: string, type: 'success' | 'info'): void {
    ElNotification({ message, type, duration: 6000 });
    if (sound) {
        chime();
    }
}

/** Follows the engine's events for the notices; the browser reconnects by itself. */
export function followNotices(router: Router): void {
    void readSound();
    const source = new EventSource(`api/v1/events?types=${TYPES}`);
    source.addEventListener('download.completed', (e) => {
        const data = JSON.parse((e as MessageEvent<string>).data) as { download?: { name?: string } };
        tell(t('nj.transfers.completed', data.download?.name ?? ''), 'success');
    });
    source.addEventListener('message.received', (e) => {
        if (router.currentRoute.value.path !== '/messages') {
            const data = JSON.parse((e as MessageEvent<string>).data) as { nick?: string; address?: string };
            tell(t('nj.chat.from', data.nick ?? data.address ?? ''), 'info');
        }
    });
    source.addEventListener('preferences.changed', () => void readSound());
}
