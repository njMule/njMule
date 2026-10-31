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

// What the name in a link keeps as it is: letters, digits and the printable ASCII marks but % and |, as the engine
// writes its links.
const KEPT = /^[A-Za-z0-9!"#$&'()*+,\-./:;<=>?@[\\\]^_`{}~]$/;
const UTF8 = new TextEncoder();
const LONE_SURROGATE = /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g;

/** {@code text} as the name in an eD2K link: spaces, %, |, controls and other letters percent-encoded in UTF-8. */
export function linkText(text: string): string {
    let out = '';
    // A lone surrogate has no UTF-8 form: it becomes '?', as in the engine's links.
    for (const c of text.replace(LONE_SURROGATE, '?')) {
        if (KEPT.test(c)) {
            out += c;
        } else {
            for (const b of UTF8.encode(c)) {
                out += `%${b.toString(16).toUpperCase().padStart(2, '0')}`;
            }
        }
    }
    return out;
}

/** The eD2K link of a file. */
export function fileLink(name: string, size: number, hash: string): string {
    return `ed2k://|file|${linkText(name)}|${size}|${hash}|/`;
}

/** The links as HTML, one anchor named after its file per line. */
export function htmlLinks(links: { link: string; name: string }[]): string {
    return links.map((l) => `<a href="${escape(l.link)}">${escape(l.name)}</a>`).join('\n');
}

const ENTITIES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

function escape(text: string): string {
    return text.replace(/[&<>"']/g, (c) => ENTITIES[c] ?? c);
}
