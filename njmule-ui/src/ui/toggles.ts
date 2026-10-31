// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

const STEPS: Record<string, number> = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };

/**
 * Moves the focus to the next or previous button of a group, as the arrows do in the desktop app's groups of toggles:
 * the focus moves, Space or Enter chooses. Right and left follow the direction of the text.
 */
export function moveAlong(e: KeyboardEvent): void {
    const step = STEPS[e.key];
    const group = e.currentTarget;
    if (step === undefined || !(group instanceof HTMLElement) || e.altKey || e.ctrlKey || e.metaKey) {
        return;
    }
    const buttons = [...group.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
    const at = buttons.findIndex((b) => b === document.activeElement);
    const sideways = e.key === 'ArrowRight' || e.key === 'ArrowLeft';
    const next = buttons[at + (sideways && getComputedStyle(group).direction === 'rtl' ? -step : step)];
    if (at >= 0 && next) {
        next.focus();
        e.preventDefault();
    }
}
