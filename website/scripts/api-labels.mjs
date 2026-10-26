// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Names each page of the generated API reference in the sidebar by its method and path (GET /status), as developers
// look for an operation, instead of its summary; a page's own sidebar_label is what the sidebar shows.
// Usage: node scripts/api-labels.mjs (after docusaurus gen-api-docs)

import { readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const reference = join(import.meta.dirname, '..', 'developer', 'api', 'reference');
let named = 0;
for (const file of readdirSync(reference).filter((f) => f.endsWith('.api.mdx'))) {
    const path = join(reference, file);
    const page = readFileSync(path, 'utf8');
    const method = /method=\{"([a-z]+)"\}/.exec(page)?.[1];
    const endpoint = /path=\{"([^"]+)"\}/.exec(page)?.[1];
    if (!method || !endpoint) {
        continue;
    }
    const label = `${method.toUpperCase()} ${endpoint}`;
    writeFileSync(path, page.replace(/^sidebar_label: .*$/m, `sidebar_label: ${JSON.stringify(label)}`));
    named++;
}
console.log(`Named ${named} pages of the API reference by their method and path.`);
