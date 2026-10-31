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

/** What a file holds, as its extension tells it. */
export type FileKind = 'video' | 'audio' | 'image' | 'document' | 'archive' | 'disc_image' | 'program' | 'other';

// The engine's file types, the eD2K types' extensions; the API reports the kind of downloads only. The programs' type
// holds the archives and the disc images too, which get their own icons.
const EXTENSIONS: Record<Exclude<FileKind, 'other'>, string> = {
    audio: 'aac ac3 aiff ape au flac m4a mid midi mp2 mp3 mpc ogg opus ra wav wma',
    video: '3gp asf avi divx flv m2ts m4v mkv mov mp4 mpeg mpg ogm rm rmvb ts vob webm wmv xvid',
    image: 'bmp gif ico jpeg jpg png psd svg tif tiff webp',
    document: 'chm csv doc docx epub htm html nfo odt pdf ppt pptx rtf txt xls xlsx',
    archive: '7z ace bz2 cab gz rar tar tgz xz zip',
    disc_image: 'bin cue dmg img iso mdf mds nrg',
    program: 'apk bat com deb exe jar msi rpm',
};

/** What the file {@code name} holds, by its extension. */
export function fileKind(name: string): FileKind {
    const dot = name.lastIndexOf('.');
    const extension = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
    const found = Object.entries(EXTENSIONS).find(([, extensions]) => extensions.split(' ').includes(extension));
    return (found?.[0] as FileKind | undefined) ?? 'other';
}

/** What tells a place from the others, as long as the tree changes. */
export function placeKey(place: Pick<Place, 'kind' | 'path'>): string {
    return `${place.kind}:${place.path}`;
}

/** The places in sight, in order: each folder followed by its subfolders unless it is closed. */
export function shownPlaces(tree: Place[], closed: string[]): Place[] {
    return tree.flatMap((p) => (closed.includes(placeKey(p)) ? [p] : [p, ...p.children]));
}

/** What a key does in the tree: the place it chooses, or the folder it opens or closes. */
export interface TreeMove {
    choose?: Place;
    toggle?: Place;
}

/**
 * What {@code key} does with {@code at} chosen, as a tree view does: the arrows up and down, Home and End move the
 * choice; the arrow towards the end opens a folder, then goes into it; the other closes it, then goes to its parent.
 * Null for a key the tree leaves alone.
 */
export function treeMove(key: string, tree: Place[], closed: string[], at: Place, rtl: boolean): TreeMove | null {
    const shown = shownPlaces(tree, closed);
    const i = shown.findIndex((p) => placeKey(p) === placeKey(at));
    const open = at.children.length > 0 && !closed.includes(placeKey(at));
    switch (key) {
        case 'ArrowDown':
            return { choose: shown[Math.min(shown.length - 1, i + 1)] };
        case 'ArrowUp':
            return { choose: shown[Math.max(0, i - 1)] };
        case 'Home':
            return { choose: shown[0] };
        case 'End':
            return { choose: shown.at(-1) };
        case rtl ? 'ArrowLeft' : 'ArrowRight':
            return at.children.length === 0 ? {} : open ? { choose: at.children[0] } : { toggle: at };
        case rtl ? 'ArrowRight' : 'ArrowLeft':
            return open
                ? { toggle: at }
                : { choose: tree.find((p) => p.children.some((c) => placeKey(c) === placeKey(at))) };
        default:
            return null;
    }
}

// Where personal or system files are, as far as a path alone tells. The engine knows its computer's own folders and
// refuses the others it finds until asked again (a conflict); a folder known here is asked about at once.
const UNIX_SYSTEM = ['bin', 'boot', 'dev', 'etc', 'lib', 'lib64', 'proc', 'sbin', 'sys', 'usr', 'var'];
const MAC_SYSTEM = ['System', 'Library', 'Applications', 'private'];
const WINDOWS_SYSTEM = ['windows', 'program files', 'program files (x86)', 'programdata'];
const PERSONAL = ['documents', 'desktop', 'pictures', '.ssh', '.gnupg'];
// The folders beside the homes in the users' folder.
const NO_HOME = ['public', 'default', 'default user', 'all users', 'shared'];

/**
 * Whether sharing {@code path} deserves a second thought: a disk's root, a system folder, a home or the documents,
 * desktop, pictures or keys in it, or a folder holding one of them.
 */
export function mayHoldPersonalFiles(path: string): boolean {
    const windows = /^[A-Za-z]:/.test(path) || path.includes('\\');
    const parts = path.split(/[\\/]+/).filter((p) => p !== '' && p !== '.');
    if (windows) {
        // A network share's root is its first two names, a disk's its letter.
        const below = path.startsWith('\\\\') ? parts.slice(1) : parts;
        const [top, home, kept, under] = below.slice(1).map((p) => p.toLowerCase());
        if (top === undefined || WINDOWS_SYSTEM.includes(top)) {
            return true;
        }
        return top === 'users' && privateInHome(home, kept, under, true);
    }
    const [top, home, kept, under] = parts;
    if (top === undefined || UNIX_SYSTEM.includes(top) || MAC_SYSTEM.includes(top)) {
        return true;
    }
    if (top === 'root') {
        return home === undefined || PERSONAL.includes(home.toLowerCase());
    }
    return (top === 'home' || top === 'Users') && privateInHome(home, kept, under, false);
}

// Whether home/kept/under, in the users' folder, is a home, what it keeps private, or the folder holding that.
function privateInHome(
    home: string | undefined,
    kept: string | undefined,
    under: string | undefined,
    windows: boolean,
): boolean {
    if (home === undefined) {
        return true;
    }
    if (NO_HOME.includes(home.toLowerCase())) {
        return false;
    }
    if (kept === undefined) {
        return true;
    }
    const folder = kept.toLowerCase();
    if (PERSONAL.includes(folder)) {
        return true;
    }
    // Windows keeps the programs' settings in AppData; OneDrive may hold the documents, desktop and pictures.
    if (windows && folder === 'appdata') {
        return under === undefined || under === 'roaming' || under === 'local';
    }
    if (windows && folder.startsWith('onedrive')) {
        return under === undefined || PERSONAL.includes(under);
    }
    return false;
}
