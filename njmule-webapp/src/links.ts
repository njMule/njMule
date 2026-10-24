// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

/** An eD2K file link found in a text. */
export interface FileLink {
    link: string;
    name: string;
    size: number;
    hash: string;
}

// ed2k://|file|name|size|hash|, then the link's other fields up to the next space, wherever it stands in the text.
const FILE_LINK = /ed2k:\/\/\|file\|([^|\r\n]+)\|(\d{1,20})\|([0-9a-fA-F]{32})\|[^\s"'<>]*/g;

/** The distinct file links of {@code text}, in their order, one for each file hash. */
export function fileLinks(text: string): FileLink[] {
    const found = new Map<string, FileLink>();
    for (const m of text.matchAll(FILE_LINK)) {
        const hash = (m[3] ?? '').toUpperCase();
        if (!found.has(hash)) {
            found.set(hash, { link: m[0], name: decoded(m[1] ?? ''), size: Number(m[2]), hash });
        }
    }
    return [...found.values()];
}

function decoded(name: string): string {
    try {
        return decodeURIComponent(name);
    } catch {
        return name;
    }
}

/** The links as HTML, one anchor named after its file per line. */
export function htmlLinks(links: { link: string; name: string }[]): string {
    return links.map((l) => `<a href="${escape(l.link)}">${escape(l.name)}</a>`).join('\n');
}

function escape(text: string): string {
    return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
