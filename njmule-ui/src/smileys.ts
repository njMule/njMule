// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The text smileys of chat messages as emoji.
const EMOJI: Record<string, string> = {
    ':)': '🙂',
    ':-)': '🙂',
    ':(': '🙁',
    ':-(': '🙁',
    ';)': '😉',
    ';-)': '😉',
    ':D': '😃',
    ':-D': '😃',
    ':P': '😛',
    ':p': '😛',
    ':-P': '😛',
    ':-p': '😛',
    ':O': '😮',
    ':o': '😮',
    ':-O': '😮',
    ':-o': '😮',
    ":'(": '😢',
    ':|': '😐',
    ':-|': '😐',
    '8)': '😎',
    ':*': '😘',
    ':-*': '😘',
    '<3': '❤',
};

// Only a smiley standing alone between blanks: addresses and code keep their colons and brackets.
const SMILEY = /(?<=^|\s)(:-?[)(DPpOo|*]|;-?\)|:'\(|8\)|<3)(?=$|\s)/g;

/** The text with its smileys as emoji; the messages sent stay as typed. */
export function smileys(text: string): string {
    return text.replace(SMILEY, (s) => EMOJI[s] ?? s);
}
