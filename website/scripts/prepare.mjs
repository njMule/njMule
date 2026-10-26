// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Puts in place what the site takes from the rest of the repository, never committed under website/:
//  - the brand's images (branding/) and the screenshots the pages name, under build/generated/static/img;
//  - the repository's own pages (changelog, contributing, security, code of conduct) and the command line's help,
//    as partials under docs/_generated and developer/_generated.
// Usage: node scripts/prepare.mjs [--gui <docs-captures>] [--web <web-captures>] [--cli <help.txt>]
//        [--remote <remote.txt>] [--strict]
// --gui is njmule-gui's build/docs-captures, --web the folder of scripts/capture-web.mjs; without --strict a
// missing picture or input is a warning, for a preview of the pages alone.

import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const site = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = resolve(site, '..');
const repository = 'https://github.com/njMule/njMule/blob/main/';
const { values: options } = parseArgs({
    options: {
        gui: { type: 'string' },
        web: { type: 'string' },
        cli: { type: 'string' },
        remote: { type: 'string' },
        strict: { type: 'boolean', default: false },
    },
});

const problems = [];
const generated = join(site, 'build', 'generated', 'static', 'img');

function problem(text) {
    problems.push(text);
}

function files(dir, accept) {
    if (!existsSync(dir)) {
        return [];
    }
    return readdirSync(dir, { withFileTypes: true, recursive: true })
        .filter((e) => e.isFile() && accept(e.name))
        .map((e) => join(e.parentPath, e.name));
}

// ---- The brand's images

const brand = join(generated, 'brand');
rmSync(brand, { recursive: true, force: true });
mkdirSync(brand, { recursive: true });
copyFileSync(join(root, 'branding', 'njmule-icon.svg'), join(brand, 'njmule-icon.svg'));
for (const name of ['njmule-32.png', 'njmule-192.png', 'njmule-512.png', 'njmule-social-preview.png']) {
    const from = join(root, 'branding', 'generated', name);
    if (existsSync(from)) {
        copyFileSync(from, join(brand, name));
    }
}

// ---- The repository's pages, as partials

// A page of the repository without its licence comment and title (the page that shows it has its own), its links to
// other files of the repository made absolute.
function partial(source, target) {
    let text = readFileSync(join(root, source), 'utf8')
        .replace(/^<!--[\s\S]*?-->\s*/, '')
        .replace(/^# .*\n+/, '');
    const base = dirname(source);
    text = text.replace(/\]\((?!https?:|#|mailto:)([^)\s]+)\)/g, (_, link) => {
        const path = join(base, link).replaceAll('\\', '/');
        return `](${repository}${path})`;
    });
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, text);
}

partial('CHANGELOG.md', join(site, 'docs', '_generated', 'changelog.md'));
partial('SECURITY.md', join(site, 'docs', '_generated', 'security.md'));
partial('CONTRIBUTING.md', join(site, 'developer', '_generated', 'contributing.md'));
partial('CODE_OF_CONDUCT.md', join(site, 'developer', '_generated', 'code-of-conduct.md'));
partial('.github/pull_request_template.md', join(site, 'developer', '_generated', 'pull-request-template.md'));

// ---- The command line's help and its remote commands, as njmule --help and njmule --remote help print them

function printed(file, target, what) {
    if (file && existsSync(file)) {
        const text = readFileSync(file, 'utf8').replace(/\r\n/g, '\n').trimEnd();
        writeFileSync(target, '```text\n' + text + '\n```\n');
    } else {
        problem(`no ${what} (${file ?? 'not given'})`);
        writeFileSync(target, '```text\n' + what + ' (made by ./gradlew :website:site)\n```\n');
    }
}

printed(options.cli, join(site, 'docs', '_generated', 'cli-help.md'), 'njmule --help');
printed(options.remote, join(site, 'docs', '_generated', 'remote-commands.md'), 'njmule --remote help');

// ---- The screenshots the pages name

const sources = {
    light: [join(options.gui ?? '', 'en-light-docs'), join(options.web ?? '', 'light')],
    dark: [join(options.gui ?? '', 'en-dark-docs'), join(options.web ?? '', 'dark')],
    contrast: [join(options.gui ?? '', 'en-contrast-screens')],
};
const named = { light: new Set(), dark: new Set(), contrast: new Set() };
const tag = /<(?:Screenshot|ThemedShot)\b[^>]*>/g;
// Components name their pictures in their data too, as `shot: 'id'`.
const shot = /\bshot:\s*'([^']+)'/g;
for (const page of [
    ...files(join(site, 'docs'), (n) => /\.mdx?$/.test(n)),
    ...files(join(site, 'developer'), (n) => /\.mdx?$/.test(n)),
    ...files(join(site, 'src'), (n) => /\.tsx$/.test(n)),
]) {
    const text = readFileSync(page, 'utf8');
    if (page.endsWith('.tsx')) {
        for (const [, id] of text.matchAll(shot)) {
            named.light.add(id);
            named.dark.add(id);
        }
    }
    for (const [element] of text.matchAll(tag)) {
        const id = /\bid="([^"]+)"/.exec(element)?.[1];
        if (!id) {
            continue;
        }
        if (/\bset="contrast"/.test(element)) {
            named.contrast.add(id);
        } else {
            named.light.add(id);
            named.dark.add(id);
        }
    }
}
const screens = join(generated, 'screens');
rmSync(screens, { recursive: true, force: true });
for (const [set, ids] of Object.entries(named)) {
    mkdirSync(join(screens, set), { recursive: true });
    for (const id of ids) {
        const from = sources[set].map((dir) => join(dir, `${id}.png`)).find((f) => options.gui && existsSync(f));
        if (from) {
            copyFileSync(from, join(screens, set, `${id}.png`));
        } else {
            problem(`no ${set} picture ${id}`);
        }
    }
}

const count = Object.values(named).reduce((n, ids) => n + ids.size, 0);
console.log(`Prepared the site: ${count} screenshots named, ${problems.length} problems.`);
for (const p of problems) {
    console.warn(`  ${options.strict ? 'error' : 'warning'}: ${p}`);
}
if (options.strict && problems.length > 0) {
    process.exit(1);
}
console.log(`Generated files in ${relative(site, generated)} and the _generated folders.`);
