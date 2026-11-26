// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Builds the interface's runtime assets for the web and desktop app:
//   translations -> build/translations/i18n/<tag>.json (only literal keys used in src/)
//   flags        -> build/flags/flags/*.png
//   site         -> build/site/ (uncompressed bundle + translations + flags for Tauri)
//   compress     -> build/shipped/ (gzipped text files + rest unchanged for njmule-engine)

import { cpSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { extname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { constants as zlibConstants, gzipSync } from 'node:zlib';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const KEY_PATTERN = /['"`]((?:IDS_[A-Z0-9_]+)|(?:nj\.[A-Za-z0-9_.]+[A-Za-z0-9]))['"`]/g;
const GZIP_EXTENSIONS = new Set(['.js', '.css', '.svg', '.json', '.md']);

function walkFiles(dir, out = []) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) {
            walkFiles(full, out);
        } else if (entry.isFile()) {
            out.push(full);
        }
    }
    return out;
}

function unescapeProperties(raw) {
    let out = '';
    for (let i = 0; i < raw.length; i++) {
        const ch = raw[i];
        if (ch !== '\\' || i + 1 >= raw.length) {
            out += ch;
            continue;
        }
        const next = raw[++i];
        if (next === 'n') {
            out += '\n';
        } else if (next === 'r') {
            out += '\r';
        } else if (next === 't') {
            out += '\t';
        } else if (next === 'f') {
            out += '\f';
        } else if (next === 'u' && i + 4 < raw.length) {
            const hex = raw.slice(i + 1, i + 5);
            if (/^[0-9a-fA-F]{4}$/.test(hex)) {
                out += String.fromCharCode(Number.parseInt(hex, 16));
                i += 4;
            } else {
                out += 'u';
            }
        } else {
            out += next;
        }
    }
    return out;
}

function parseProperties(text) {
    const map = new Map();
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
        let line = lines[i].replace(/^[ \t\f]+/, '');
        if (line === '' || line.startsWith('#') || line.startsWith('!')) {
            continue;
        }
        while (hasTrailingOddBackslashes(line) && i + 1 < lines.length) {
            line = line.slice(0, -1) + lines[++i].replace(/^[ \t\f]+/, '');
        }
        let sep = -1;
        for (let j = 0; j < line.length; j++) {
            const c = line[j];
            if (c === '\\') {
                j++;
                continue;
            }
            if (c === '=' || c === ':' || c === ' ' || c === '\t' || c === '\f') {
                sep = j;
                break;
            }
        }
        const rawKey = sep === -1 ? line : line.slice(0, sep);
        let rawValue = '';
        if (sep !== -1) {
            let v = sep;
            while (v < line.length && (line[v] === ' ' || line[v] === '\t' || line[v] === '\f')) {
                v++;
            }
            if (v < line.length && (line[v] === '=' || line[v] === ':')) {
                v++;
                while (v < line.length && (line[v] === ' ' || line[v] === '\t' || line[v] === '\f')) {
                    v++;
                }
            }
            rawValue = line.slice(v);
        }
        map.set(unescapeProperties(rawKey), unescapeProperties(rawValue));
    }
    return map;
}

function hasTrailingOddBackslashes(line) {
    let count = 0;
    for (let i = line.length - 1; i >= 0 && line[i] === '\\'; i--) {
        count++;
    }
    return count % 2 === 1;
}

function buildTranslations() {
    const used = new Set();
    for (const file of walkFiles(join(root, 'src'))) {
        if (!file.endsWith('.ts') && !file.endsWith('.vue')) {
            continue;
        }
        const content = readFileSync(file, 'utf8');
        for (const match of content.matchAll(KEY_PATTERN)) {
            used.add(match[1]);
        }
    }
    const i18nDir = join(root, 'i18n');
    const bundleNames = readdirSync(i18nDir).filter((name) => name.endsWith('.properties'));
    const bundleSet = new Set(bundleNames);
    const languages = bundleNames
        .map((name) => /^nj(?:_(.+))?\.properties$/.exec(name))
        .filter((m) => m !== null)
        .map((m) => m[1] ?? '');

    const outDir = join(root, 'build', 'translations', 'i18n');
    rmSync(outDir, { recursive: true, force: true });
    mkdirSync(outDir, { recursive: true });

    for (const language of languages) {
        const suffix = language === '' ? '' : `_${language}`;
        const texts = new Map();
        for (const name of [`emule${suffix}.properties`, `nj${suffix}.properties`]) {
            if (!bundleSet.has(name)) {
                continue;
            }
            const parsed = parseProperties(readFileSync(join(i18nDir, name), 'utf8'));
            for (const [k, v] of parsed) {
                if (used.has(k)) {
                    texts.set(k, v);
                }
            }
        }
        const tag = language === '' ? 'en' : language.replaceAll('_', '-');
        const entries = [...texts.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
        const body = entries.map(([k, v]) => `  ${JSON.stringify(k)}: ${JSON.stringify(v)}`).join(',\n');
        writeFileSync(join(outDir, `${tag}.json`), `{\n${body}\n}\n`, 'utf8');
    }
}

function buildFlags() {
    const outDir = join(root, 'build', 'flags', 'flags');
    rmSync(outDir, { recursive: true, force: true });
    mkdirSync(outDir, { recursive: true });
    const srcDir = join(root, 'flags');
    for (const name of readdirSync(srcDir)) {
        if (name.endsWith('.png')) {
            cpSync(join(srcDir, name), join(outDir, name));
        }
    }
}

function copyTree(src, dest) {
    if (!statSync(src, { throwIfNoEntry: false })?.isDirectory()) {
        throw new Error(`Missing build directory: ${src}`);
    }
    cpSync(src, dest, { recursive: true });
}

function buildSite() {
    const outDir = join(root, 'build', 'site');
    rmSync(outDir, { recursive: true, force: true });
    mkdirSync(outDir, { recursive: true });
    copyTree(join(root, 'build', 'dist'), outDir);
    copyTree(join(root, 'build', 'translations'), outDir);
    copyTree(join(root, 'build', 'flags'), outDir);
}

function buildCompress() {
    const outDir = join(root, 'build', 'shipped');
    rmSync(outDir, { recursive: true, force: true });
    mkdirSync(outDir, { recursive: true });
    const roots = [join(root, 'build', 'dist'), join(root, 'build', 'translations'), join(root, 'build', 'flags')];
    for (const srcRoot of roots) {
        if (!statSync(srcRoot, { throwIfNoEntry: false })?.isDirectory()) {
            throw new Error(`Missing build directory: ${srcRoot}`);
        }
        for (const file of walkFiles(srcRoot)) {
            const rel = relative(srcRoot, file);
            const ext = extname(file).toLowerCase();
            if (GZIP_EXTENSIONS.has(ext)) {
                const target = join(outDir, `${rel}.gz`);
                mkdirSync(resolve(target, '..'), { recursive: true });
                const compressed = gzipSync(readFileSync(file), { level: zlibConstants.Z_BEST_COMPRESSION });
                writeFileSync(target, compressed);
            } else {
                const target = join(outDir, rel);
                mkdirSync(resolve(target, '..'), { recursive: true });
                cpSync(file, target);
            }
        }
    }
}

const command = process.argv[2];
switch (command) {
    case 'translations':
        buildTranslations();
        break;
    case 'flags':
        buildFlags();
        break;
    case 'site':
        buildSite();
        break;
    case 'compress':
        buildCompress();
        break;
    default:
        console.error('Usage: node scripts/build-assets.mjs <translations|flags|site|compress>');
        process.exit(2);
}
