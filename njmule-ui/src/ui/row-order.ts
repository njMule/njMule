// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The order rows are left in by dragging them, kept apart from the sort of the columns.

/** {@code ids} with the {@code moving} ones put together before or after {@code target}, the others in their order. */
export function movedRows(ids: string[], moving: string[], target: string, after: boolean): string[] {
    const away = new Set(moving);
    const kept = ids.filter((id) => !away.has(id));
    const at = kept.indexOf(target);
    if (away.has(target) || at < 0) {
        return ids;
    }
    kept.splice(at + (after ? 1 : 0), 0, ...ids.filter((id) => away.has(id)));
    return kept;
}

/**
 * {@code all} once the ones {@code shown} lists take its order, each in a place one of them held: those not shown keep
 * theirs.
 */
export function reordered(all: string[], shown: string[]): string[] {
    const showing = new Set(shown);
    const next = shown.filter((id) => all.includes(id))[Symbol.iterator]();
    return all.map((id) => (showing.has(id) ? (next.next().value ?? id) : id));
}

/** {@code list} in {@code order}: the items it names first, in its order, then the others as they come. */
export function inOrder<T>(list: T[], order: string[], key: (item: T) => string): T[] {
    const place = new Map(order.map((id, i) => [id, i]));
    return list
        .map((item, i) => ({ item, i, at: place.get(key(item)) ?? order.length + i }))
        .sort((a, b) => a.at - b.at)
        .map((e) => e.item);
}
