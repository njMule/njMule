// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { engineHere, type Platform } from './platform';

// A server.met or a nodes.dat added from a file: in the desktop window beside the engine, a file of the engine's
// computer chosen with the system's dialog and sent by its path; in a browser, a file of the browser's computer, sent
// whole.

/** What the API takes for a list file: the path of a file of the engine's computer, or the file's content in Base64. */
export type ListFile = { path: string } | { data: string };

export interface ChosenList {
    body: ListFile;
    /** The file's name, for the message that tells what it gave. */
    name: string;
}

// Short enough for the arguments of one call.
const CHUNK = 0x8000;

/** The bytes in Base64. */
export function base64(bytes: Uint8Array): string {
    let binary = '';
    for (let i = 0; i < bytes.length; i += CHUNK) {
        binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
    }
    return btoa(binary);
}

/** The name at the end of a path, whichever separator the engine's system uses. */
export function fileName(path: string): string {
    return path.split(/[\\/]/).pop() ?? path;
}

/** Whether a file is chosen on the engine's computer through the shell, rather than read in the browser. */
export function choosesOnEngine(platform: Platform | undefined, url: string): boolean {
    return platform?.desktop === true && engineHere(url);
}

/**
 * Lets the user choose a list file whose extension is {@code extension}; undefined when none is. In a browser the
 * chooser opens at once, so that it opens on the click that asked for it.
 */
export function chooseListFile(
    onEngine: boolean,
    platform: Platform | undefined,
    title: string,
    kind: string,
    extension: string,
): Promise<ChosenList | undefined> {
    if (onEngine) {
        return (platform?.chooseFile(title, kind, extension) ?? Promise.resolve(null)).then((path) =>
            path ? { body: { path }, name: fileName(path) } : undefined,
        );
    }
    return new Promise((done) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = `.${extension}`;
        input.addEventListener('cancel', () => done(undefined));
        input.addEventListener('change', () => {
            const file = input.files?.[0];
            if (file === undefined) {
                done(undefined);
                return;
            }
            void file.arrayBuffer().then(
                (content) => done({ body: { data: base64(new Uint8Array(content)) }, name: file.name }),
                () => done(undefined),
            );
        });
        input.click();
    });
}
