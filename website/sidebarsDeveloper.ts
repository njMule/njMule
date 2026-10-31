// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from 'node:fs';
import { load } from 'js-yaml';
import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';
import generated from './developer/api/reference/sidebar';

const SPECIFICATION = '../njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml';
// The few fields of the generated sidebar's items this file reads.
type SidebarItemConfig = string | { type?: string; label?: string; id?: string; items?: SidebarItemConfig[] };

interface Tag {
    name: string;
    summary?: string;
}

// The groups of the reference take the short names of their tags (OpenAPI 3.2 summaries); each operation is named
// by its method and path in its own page (scripts/api-labels.mjs).
const tags = new Map(
    ((load(readFileSync(SPECIFICATION, 'utf8')) as { tags: Tag[] }).tags ?? []).map((t) => [
        t.name,
        t.summary ?? t.name,
    ]),
);

function relabel(item: SidebarItemConfig): SidebarItemConfig {
    if (typeof item !== 'object') {
        return item;
    }
    if (item.type === 'category' && item.label) {
        return { ...item, label: tags.get(item.label) ?? item.label, items: (item.items ?? []).map(relabel) };
    }
    return item;
}

// The developer guide: building, testing and changing njMule, and driving it through its API.
const sidebars: SidebarsConfig = {
    developer: [
        'index',
        'building',
        'dependencies',
        'testing',
        'native-images',
        'packaging',
        'code-style',
        'contributing',
        'interface',
        'translations',
        'documentation',
        'releases',
        'benchmarks',
        {
            type: 'category',
            label: 'Remote-control API',
            link: { type: 'doc', id: 'api/index' },
            items: (generated as unknown as SidebarItemConfig[]).map(relabel) as SidebarsConfig[string],
        },
    ],
};

export default sidebars;
