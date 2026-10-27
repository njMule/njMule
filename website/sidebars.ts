// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

// The user guide: for people who use njMule, on the desktop, in a browser or on a server.
const sidebars: SidebarsConfig = {
    guide: [
        'intro',
        'features',
        {
            type: 'category',
            label: 'Getting started',
            link: { type: 'generated-index', slug: '/getting-started', description: 'Install njMule and start.' },
            items: [
                'getting-started/requirements',
                'getting-started/install-windows',
                'getting-started/install-macos',
                'getting-started/install-linux',
                'getting-started/install-docker',
                'getting-started/install-synology',
                'getting-started/verify-downloads',
                'getting-started/first-run',
                'getting-started/import-emule',
                'getting-started/first-download',
            ],
        },
        {
            type: 'category',
            label: 'Desktop app',
            link: { type: 'generated-index', slug: '/desktop', description: 'Every part of the desktop app.' },
            items: [
                'desktop/interface',
                'desktop/transfers',
                'desktop/search',
                'desktop/shared-files',
                'desktop/messages',
                'desktop/network',
                'desktop/statistics',
                'desktop/categories',
                'desktop/ed2k-links',
                'desktop/keyboard-shortcuts',
                'desktop/appearance',
                'desktop/tray',
                'desktop/remote',
            ],
        },
        {
            type: 'category',
            label: 'Options',
            link: { type: 'doc', id: 'settings/index' },
            items: [
                'settings/general',
                'settings/connection',
                'settings/bandwidth',
                'settings/server',
                'settings/proxy',
                'settings/directories',
                'settings/ip-filter',
                'settings/messages',
                'settings/scheduler',
                'settings/events',
                'settings/notifications',
                'settings/web-interface',
                'settings/extended',
            ],
        },
        {
            type: 'category',
            label: 'Web interface',
            link: { type: 'doc', id: 'web/overview' },
            items: ['web/security', 'web/reverse-proxy', 'web/guest'],
        },
        {
            type: 'category',
            label: 'Headless and servers',
            link: { type: 'doc', id: 'server/headless' },
            items: ['server/cli', 'server/remote-control', 'server/windows-service', 'server/linux-service'],
        },
        {
            type: 'category',
            label: 'Networking',
            link: { type: 'generated-index', slug: '/network', description: 'How njMule reaches the networks.' },
            items: [
                'network/ports',
                'network/servers-and-kad',
                'network/ipv6',
                'network/nat-traversal',
                'network/obfuscation',
                'network/proxy',
                'network/bind-interface',
                'network/ip-filter',
            ],
        },
        {
            type: 'category',
            label: 'Your data',
            link: { type: 'generated-index', slug: '/data', description: 'Where njMule keeps your data.' },
            items: [
                'data/locations',
                'data/files',
                'data/portable',
                'data/backups',
                'data/logs',
                'data/privacy',
                'data/remove-profile',
            ],
        },
        'updates',
        'uninstall',
        'troubleshooting',
        'faq',
        'glossary',
        {
            type: 'category',
            label: 'About',
            items: ['about/license', 'about/changelog', 'about/security'],
        },
    ],
};

export default sidebars;
