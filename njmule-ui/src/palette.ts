// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

/** Something the palette does, with the keys that also do it, if any; a disabled one shows but does nothing. */
export interface Command {
    title: string;
    keys: string;
    run: () => void;
    disabled?: boolean;
}

/** The commands whose title holds every word of {@code text}, then what the text is itself, such as a search. */
export function matching(commands: Command[], text: string, typed: (text: string) => Command[]): Command[] {
    const words = text
        .trim()
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '');
    const kept = commands.filter((c) => words.every((w) => c.title.toLowerCase().includes(w)));
    return text.trim() === '' ? kept : [...kept, ...typed(text.trim())];
}

/**
 * The command {@code by} rows from {@code at} that can run, going round past either end; {@code at} itself when none
 * can, -1 for an empty list. {@code by} 0 finds the first from {@code at} on.
 */
export function step(commands: Command[], at: number, by: number): number {
    const size = commands.length;
    if (size === 0) {
        return -1;
    }
    const way = by < 0 ? -1 : 1;
    let index = by === 0 ? at - 1 : at;
    for (let tried = 0; tried < size; tried++) {
        index = (((index + way) % size) + size) % size;
        if (!commands[index]!.disabled) {
            return index;
        }
    }
    return Math.min(Math.max(at, 0), size - 1);
}
