// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref } from 'vue';
import type { Schemas } from './api/client';

// The name, version, copyright and licence, in the same words in every front end: the tagline and the copyright stay
// in English.

export const TAGLINE = 'Next generation Java Mule';
export const COPYRIGHT = 'Copyright (C) 2026 Salvatore Coppola and the njMule contributors';
export const LICENSE_ID = 'GPL-3.0-or-later';

/** Whether About shows, opened from the More menu, the palette or the macOS application menu. */
export const aboutOpen = ref(false);

export function showAbout(): void {
    aboutOpen.value = true;
}

/** What a report of a problem needs: the engine's njMule, Java and system, those it tells. */
export function versions(info: Schemas['ApiInfo']): string {
    const parts = [`${info.name} ${info.version}`];
    if (info.java) {
        parts.push(`Java ${info.java}`);
    }
    if (info.system) {
        parts.push(`${info.system.name} ${info.system.version} ${info.system.arch}`);
    }
    return parts.join(' · ');
}
