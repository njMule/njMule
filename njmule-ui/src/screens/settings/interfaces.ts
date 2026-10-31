// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';
import { answer, api, type Schemas } from '../../api/client';

// The network interface njMule's connections are bound to, chosen among those of the engine's computer.

export type NetworkInterface = Schemas['NetworkInterface'];

type Translate = (key: string, ...args: unknown[]) => string;

/** The interfaces of the engine's computer worth choosing, as last read. */
export const interfaces = shallowRef<NetworkInterface[]>([]);

export async function readInterfaces(): Promise<void> {
    try {
        interfaces.value = await answer(api().GET('/interfaces'));
    } catch {
        // The choice kept shows alone.
    }
}

/** Any (empty), the interfaces up now, and the one chosen before even while it is not there. */
export function interfaceChoices(chosen: string, up: NetworkInterface[]): string[] {
    const choices = ['', ...up.map((i) => i.name)];
    if (chosen.trim() !== '' && !choices.includes(chosen)) {
        choices.push(chosen);
    }
    return choices;
}

/** A choice in words: any, an interface with its addresses, an address with its interface, or one not there. */
export function interfaceName(choice: string, up: NetworkInterface[], t: Translate): string {
    if (choice.trim() === '') {
        return t('nj.settings.bindInterface.any');
    }
    for (const i of up) {
        if (i.name === choice) {
            return `${i.displayName} (${i.addresses.join(', ')})`;
        }
        if (i.addresses.includes(choice)) {
            return `${choice} (${i.displayName})`;
        }
    }
    return t('nj.settings.bindInterface.missing', choice);
}
