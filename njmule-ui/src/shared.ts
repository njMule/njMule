// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The places of the shared files as the desktop app shows them: all of them, the incoming folder, then each folder
// shared with the subfolders that hold files. Paths are those of the computer njMule runs on, with its separator.

export type PlaceKind = 'all' | 'incoming' | 'folder' | 'subfolder';

export interface Place {
    kind: PlaceKind;
    path: string;
    subdirectories: boolean;
    children: Place[];
}

function separator(path: string): string {
    return path.includes('\\') ? '\\' : '/';
}

function trimmed(path: string): string {
    const sep = separator(path);
    return path.length > 1 && path.endsWith(sep) && !/^[A-Za-z]:\\$/.test(path) ? path.slice(0, -1) : path;
}

/** The folder {@code path} lies in. */
export function parentOf(path: string): string {
    const sep = separator(path);
    const at = trimmed(path).lastIndexOf(sep);
    return at <= 0 ? '' : path.substring(0, at);
}

/** The last name of {@code path}. */
export function nameOf(path: string): string {
    const p = trimmed(path);
    return p.substring(p.lastIndexOf(separator(p)) + 1);
}

/** Whether {@code path} is {@code folder} or lies below it; names compare as the computer of Windows paths does. */
export function isUnder(path: string, folder: string): boolean {
    if (folder === '') {
        return false;
    }
    const windows = separator(folder) === '\\';
    const p = windows ? path.toLowerCase() : path;
    const f = trimmed(windows ? folder.toLowerCase() : folder);
    const sep = separator(folder);
    return p === f || p.startsWith(f.endsWith(sep) ? f : f + sep);
}

/** Whether {@code file} lies in {@code place}. */
export function holds(place: Place, file: string): boolean {
    switch (place.kind) {
        case 'all':
            return true;
        case 'folder':
            return place.subdirectories ? isUnder(file, place.path) : sameFolder(parentOf(file), place.path);
        default:
            return isUnder(file, place.path);
    }
}

function sameFolder(a: string, b: string): boolean {
    return separator(b) === '\\' ? trimmed(a).toLowerCase() === trimmed(b).toLowerCase() : trimmed(a) === trimmed(b);
}

/** The folders right under {@code folder} that hold files, in order. */
export function subfoldersOf(folder: string, files: string[]): string[] {
    const sep = separator(folder);
    const base = trimmed(folder);
    const found = new Set<string>();
    for (const file of files) {
        if (isUnder(file, base) && !sameFolder(parentOf(file), base)) {
            const rest = file.substring(base.length + 1);
            found.add(base + sep + rest.split(sep)[0]);
        }
    }
    return [...found].sort();
}

/** The tree of the places: all files, the incoming folder, each shared folder with its subfolders. */
export function places(
    directories: { path: string; subdirectories: boolean }[],
    incoming: string,
    files: string[],
): Place[] {
    const top: Place[] = [
        { kind: 'all', path: '', subdirectories: true, children: [] },
        { kind: 'incoming', path: incoming, subdirectories: true, children: [] },
    ];
    for (const d of [...directories].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))) {
        if (incoming !== '' && sameFolder(d.path, incoming)) {
            continue;
        }
        top.push({
            kind: 'folder',
            path: d.path,
            subdirectories: d.subdirectories,
            children: d.subdirectories
                ? subfoldersOf(d.path, files).map((sub) => ({
                      kind: 'subfolder' as const,
                      path: sub,
                      subdirectories: true,
                      children: [],
                  }))
                : [],
        });
    }
    return top;
}
