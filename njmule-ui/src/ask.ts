// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { shallowRef } from 'vue';

// A question in one style, as the desktop app's Ask: answers that tell the outcome, the safe one the default, a risky
// one red at its outline; a field or a check box when the answer needs one.

export interface Question {
    title: string;
    text?: string;
    names?: string[];
    /** A text to give, with its label and the value it starts with. */
    field?: { label: string; value: string; hint?: string };
    /** A choice to tick, with the value it starts with. */
    check?: { label: string; value: boolean };
    /** A note under the text, such as a warning of what else may be shared. */
    note?: string;
    warning?: boolean;
    yes: string;
    no: string;
    /** Whether the answer that acts is the risky one; otherwise it is the default. */
    risky?: boolean;
}

export interface Answer {
    field: string;
    check: boolean;
}

export const question = shallowRef<(Question & { answer: (a: Answer | null) => void }) | null>(null);

/** Asks {@code q}; the answer when the user takes the one that acts, null when not. */
export function ask(q: Question): Promise<Answer | null> {
    return new Promise((resolve) => {
        question.value = {
            ...q,
            answer: (a) => {
                question.value = null;
                resolve(a);
            },
        };
    });
}

/** Asks before something that cannot be undone; true when the user takes the risky answer. */
export async function askRisky(
    title: string,
    text: string,
    names: string[],
    risky: string,
    safe: string,
): Promise<boolean> {
    return (await ask({ title, text, names, yes: risky, no: safe, risky: true })) !== null;
}
