// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The widths of a list's columns as the desktop app's tables lay them out (JavaFX's constrained resize policy, flexing
// the last column): a change of the list's width is shared among all the columns, and the edge of a column dragged by
// the user moves the columns after it, the last first. Written here: the browser's tables have no such layout.

/** A column's width now, the one it prefers, and the least and most it may take. */
export interface ColumnSpan {
    size: number;
    pref: number;
    min: number;
    max: number;
}

/** A column with no limit of its own takes no more than this, as in the desktop app. */
export const MAX_WIDTH = 5000;

// A change up to this many pixels goes a pixel at a time to the column furthest from where it tends.
const SMALL_DELTA = 32;

const fixed = (c: ColumnSpan): boolean => c.min >= c.max;

/**
 * The widths of {@code columns} filling {@code target} pixels: a shrink first takes the columns towards their
 * preferred widths, then towards their least; a growth first brings them to their preferred widths, then shares the
 * rest. A large change is shared in proportion to what each column can still give or take.
 */
export function fitColumns(columns: readonly ColumnSpan[], target: number): number[] {
    const size = columns.map((c) => c.size);
    if (columns.every(fixed)) {
        return size;
    }
    const sum = (values: readonly number[]): number => values.reduce((s, v) => s + v, 0);
    const prefs = columns.map((c) => c.pref);
    const delta = target - sum(size);
    if (delta < 0) {
        distribute(columns, size, delta, target < sum(prefs) ? columns.map((c) => c.min) : prefs);
    } else if (delta > 0) {
        distribute(columns, size, delta, target > sum(prefs) ? columns.map((c) => c.max) : prefs);
    }
    return size;
}

/**
 * The widths after the user moved the end of column {@code index} to {@code wanted} pixels: the columns after it give
 * or take the difference, from the last one back, first those away from their preferred width; the last column has
 * no column after it and keeps its width.
 */
export function dragColumn(columns: readonly ColumnSpan[], index: number, wanted: number): number[] {
    const size = columns.map((c) => c.size);
    const column = columns[index];
    if (!column || fixed(column)) {
        return size;
    }
    const after = columns.map((c, i) => i > index && !fixed(c));
    if (!after.includes(true)) {
        return size;
    }
    const growing = wanted > column.size;
    const own = growing ? column.max - column.size : column.size - column.min;
    // What the columns after it can give, or take.
    const others = columns.reduce((s, c, i) => (after[i] ? s + (growing ? c.size - c.min : c.max - c.size) : s), 0);
    let delta = Math.round(Math.min(Math.abs(wanted - column.size), own, others));
    if (delta === 0) {
        return size;
    }
    delta = growing ? delta : -delta;
    const last = columns.length - 1;
    // The column before the last cannot grow once the last is at its least width.
    if (index === last - 1 && delta > 0 && size[last]! <= columns[last]!.min) {
        return size;
    }
    size[index]! += delta;
    let left = -delta;
    const resize = (i: number): void => {
        const c = columns[i]!;
        const w = size[i]! + left;
        const clamped = Math.min(c.max, Math.max(c.min, w));
        left = w - clamped;
        size[i] = clamped;
    };
    // First the columns away from their preferred width, then any, from the last one back.
    for (let i = last; i > index && left !== 0; i--) {
        if (after[i] && (left < 0 ? size[i]! > columns[i]!.pref : size[i]! < columns[i]!.pref)) {
            resize(i);
        }
    }
    for (let i = last; i > index && left !== 0; i--) {
        if (after[i]) {
            resize(i);
        }
    }
    // What the others could not take stays with the dragged column.
    size[index]! += left;
    return size;
}

// Shares delta (negative when shrinking) among the columns that are not fixed, each towards its desired width.
function distribute(columns: readonly ColumnSpan[], size: number[], delta: number, desired: readonly number[]): void {
    const open = columns.map((c) => !fixed(c));
    if (Math.abs(delta) > SMALL_DELTA) {
        // In proportion to what each column can still go towards its desired width, rounded so that the sum is kept.
        const grow = delta > 0;
        const room = size.map((s, i) => {
            if (!open[i]) {
                return 0;
            }
            const d = desired[i]! - s;
            return grow ? Math.max(0, d) : Math.min(0, d);
        });
        const total = room.reduce((s, r) => s + r, 0);
        if (total === 0) {
            return;
        }
        // No column goes past its desired width: what is left over makes the list scroll sideways.
        const shared = Math.abs(delta) > Math.abs(total) ? total : delta;
        let unsnapped = 0;
        let snapped = 0;
        for (let i = 0; i < size.length; i++) {
            if (open[i]) {
                const x = unsnapped + size[i]! + (shared * room[i]!) / total;
                const w = Math.round(x) - snapped;
                size[i] = w;
                unsnapped = x;
                snapped += w;
            }
        }
        return;
    }
    // A pixel at a time to the column furthest from its desired width.
    const step = delta > 0 ? 1 : -1;
    for (let left = Math.round(Math.abs(delta)); left > 0; left--) {
        let best = -1;
        let far = Number.NEGATIVE_INFINITY;
        for (let i = 0; i < size.length; i++) {
            const d = step > 0 ? desired[i]! - size[i]! : size[i]! - desired[i]!;
            if (open[i] && d > far) {
                far = d;
                best = i;
            }
        }
        if (best < 0 || far <= 0) {
            return;
        }
        size[best]! += step;
    }
}
