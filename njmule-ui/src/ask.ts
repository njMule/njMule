// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';

// A question in one style, as the desktop app's Ask: answers that tell the outcome, the safe one the default, a risky
// one red.

export interface Question {
    title: string;
    text: string;
    names: string[];
    risky: string;
    safe: string;
    answer: (yes: boolean) => void;
}

export const question = shallowRef<Question | null>(null);

/** Asks before something that cannot be undone; true when the user takes the risky answer. */
export function askRisky(title: string, text: string, names: string[], risky: string, safe: string): Promise<boolean> {
    return new Promise((resolve) => {
        question.value = {
            title,
            text,
            names,
            risky,
            safe,
            answer: (yes) => {
                question.value = null;
                resolve(yes);
            },
        };
    });
}
