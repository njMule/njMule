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
// The same with its separators escaped, as browsers hand links over to the program they open with.
const ESCAPED_LINK = /ed2k:\/\/%7c[^\s"'<>|]*/gi;
// A magnet link, whose parameters name an eD2K file or not.
const MAGNET_LINK = /magnet:\?[^\s"'<>]+/gi;

/**
 * The distinct file links of {@code text}, in their order, one for each file hash: eD2K links, escaped or not, and the
 * magnet links of eD2K files.
 */
export function fileLinks(text: string): FileLink[] {
    const plain = text.replace(ESCAPED_LINK, (link) => link.replace(/%7c/gi, '|'));
    const links: { at: number; link: FileLink | null }[] = [
        ...[...plain.matchAll(FILE_LINK)].map((m) => ({
            at: m.index,
            link: { link: m[0], name: decoded(m[1] ?? ''), size: Number(m[2]), hash: (m[3] ?? '').toUpperCase() },
        })),
        ...[...plain.matchAll(MAGNET_LINK)].map((m) => ({ at: m.index, link: magnetLink(m[0]) })),
    ];
    const found = new Map<string, FileLink>();
    for (const { link } of links.sort((a, b) => a.at - b.at)) {
        if (link !== null && !found.has(link.hash)) {
            found.set(link.hash, link);
        }
    }
    return [...found.values()];
}

// The file a magnet link names by its eD2K hash (xt), name (dn) and size (xl); null for any other magnet link. An empty
// parameter ends the list.
function magnetLink(link: string): FileLink | null {
    let name = '';
    let size = '';
    let hash: string | undefined;
    for (const param of link.substring(link.indexOf('?') + 1).split('&')) {
        if (param === '') {
            break;
        }
        const value = param.substring(3);
        if (param.startsWith('dn=')) {
            name = decoded(value).trim();
        } else if (param.startsWith('xl=')) {
            size = value;
        } else if (param.startsWith('xt=urn:ed2k:') || param.startsWith('xt=urn:ed2khash:')) {
            hash = value.substring(value.lastIndexOf(':') + 1);
        }
    }
    if (hash === undefined || !/^[0-9a-fA-F]{32}$/.test(hash) || !/^\d{1,15}$/.test(size) || name === '') {
        return null;
    }
    return { link, name, size: Number(size), hash: hash.toUpperCase() };
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

/** The eD2K link of a file, with the root of its AICH tree when it is known. */
export function fileLink(name: string, size: number, hash: string, aich?: string | null): string {
    return `ed2k://|file|${linkText(name)}|${size}|${hash}|${aich ? `h=${aich}|` : ''}/`;
}

/** The links as HTML, one anchor named after its file per line. */
export function htmlLinks(links: { link: string; name: string }[]): string {
    return links.map((l) => `<a href="${escape(l.link)}">${escape(l.name)}</a>`).join('\n');
}

const ENTITIES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

function escape(text: string): string {
    return text.replace(/[&<>"']/g, (c) => ENTITIES[c] ?? c);
}
