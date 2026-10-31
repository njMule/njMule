// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';
import { answer, api } from '../../api/client';

// The passwords of the preferences, which the engine never gives: a field shows dots while one is set, as the desktop
// app's masked field does, and what is typed over them replaces it, an emptied field clearing it.

const DOT = '•';

/** What a password field shows while its password is set. */
export const PASSWORD_MASK = DOT.repeat(8);

/** Whether each password is set, by its key. */
export const passwordsSet = shallowRef<Record<string, boolean>>({});

export async function readPasswords(): Promise<void> {
    try {
        passwordsSet.value = await answer(api().GET('/preferences/passwords'));
    } catch {
        // Empty fields then, as for passwords not set.
    }
}

/** The password typed over the mask: the characters typed, the mask's dots left out; empty when only deleted. */
export function overMask(text: string): string {
    return text.replaceAll(DOT, '');
}
