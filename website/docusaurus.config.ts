// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { Config } from '@docusaurus/types';
import type * as Preset from '@docusaurus/preset-classic';
import type { Options as DocsOptions } from '@docusaurus/plugin-content-docs';
import type * as OpenApiPlugin from 'docusaurus-plugin-openapi-docs';
import { readFileSync } from 'node:fs';
import { themes as prismThemes } from 'prism-react-renderer';

const repository = 'https://github.com/njMule/njMule';
// The version being documented: the project's, from gradle.properties, its single source.
const version =
    process.env.NJMULE_VERSION ??
    /^version=(.+)$/m.exec(readFileSync(new URL('../gradle.properties', import.meta.url), 'utf8'))?.[1]?.trim() ??
    'development';

const config: Config = {
    title: 'njMule',
    tagline: 'A modern eD2K and Kad client, compatible with the eMule network',
    favicon: 'img/brand/njmule-icon.svg',
    url: 'https://njmule.github.io',
    baseUrl: '/njMule/',
    organizationName: 'njMule',
    projectName: 'njMule',
    trailingSlash: false,
    future: {
        // Docusaurus 4's defaults, but CSS cascade layers: their fallback for older browsers lets the theme's rules
        // outrank the site's own.
        v4: {
            removeLegacyPostBuildHeadAttribute: true,
            useCssCascadeLayers: false,
            siteStorageNamespacing: true,
            fasterByDefault: true,
            mdx1CompatDisabledByDefault: true,
        },
        faster: true,
    },

    onBrokenLinks: 'throw',
    onBrokenAnchors: 'throw',
    markdown: {
        // .md files are CommonMark (the pages copied from the repository), .mdx files MDX.
        format: 'detect',
        mermaid: true,
        hooks: { onBrokenMarkdownLinks: 'throw', onBrokenMarkdownImages: 'throw' },
    },
    // The pictures made by the build (website/build.gradle.kts): screenshots and the brand's images.
    staticDirectories: ['static', 'build/generated/static'],
    customFields: { version, repository },

    i18n: { defaultLocale: 'en', locales: ['en'] },

    presets: [
        [
            'classic',
            {
                docs: {
                    path: 'docs',
                    routeBasePath: 'docs',
                    sidebarPath: './sidebars.ts',
                    editUrl: `${repository}/edit/main/website/`,
                },
                blog: false,
                theme: { customCss: './src/css/custom.css' },
            } satisfies Preset.Options,
        ],
    ],

    plugins: [
        [
            '@docusaurus/plugin-content-docs',
            {
                id: 'developer',
                path: 'developer',
                routeBasePath: 'developer',
                sidebarPath: './sidebarsDeveloper.ts',
                editUrl: `${repository}/edit/main/website/`,
                // The pages of the API reference are drawn by the OpenAPI theme.
                docItemComponent: '@theme/ApiItem',
            } satisfies DocsOptions,
        ],
        [
            'docusaurus-plugin-openapi-docs',
            {
                id: 'api',
                docsPluginId: 'developer',
                config: {
                    njmule: {
                        specPath: '../njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml',
                        outputDir: 'developer/api/reference',
                        downloadUrl: `${repository}/blob/main/njmule-core/src/main/resources/org/njmule/core/remote/openapi.yaml`,
                        hideSendButton: true,
                        sidebarOptions: { groupPathsBy: 'tag', categoryLinkSource: 'tag' },
                    } satisfies OpenApiPlugin.Options,
                },
            },
        ],
        'docusaurus-plugin-sass',
    ],

    themes: [
        '@docusaurus/theme-mermaid',
        'docusaurus-theme-openapi-docs',
        [
            '@easyops-cn/docusaurus-search-local',
            {
                hashed: true,
                indexBlog: false,
                docsRouteBasePath: ['docs', 'developer'],
                docsDir: ['docs', 'developer'],
                highlightSearchTermsOnTargetPage: true,
                explicitSearchResultPath: true,
            },
        ],
    ],

    themeConfig: {
        image: 'img/brand/njmule-social-preview.png',
        metadata: [{ name: 'keywords', content: 'njMule, eMule, eD2K, eDonkey, Kad, Kademlia, P2P, file sharing' }],
        colorMode: { defaultMode: 'light', respectPrefersColorScheme: true },
        docs: { sidebar: { hideable: true, autoCollapseCategories: true } },
        navbar: {
            title: 'njMule',
            logo: { alt: 'njMule logo', src: 'img/brand/njmule-icon.svg' },
            items: [
                { type: 'docSidebar', sidebarId: 'guide', position: 'left', label: 'User guide' },
                {
                    type: 'docSidebar',
                    sidebarId: 'developer',
                    docsPluginId: 'developer',
                    position: 'left',
                    label: 'Developers',
                },
                { to: '/developer/api', position: 'left', label: 'API' },
                {
                    href: `${repository}/releases/latest`,
                    position: 'right',
                    label: 'Download',
                    className: 'navbar-download',
                },
                {
                    href: repository,
                    position: 'right',
                    className: 'navbar-github',
                    'aria-label': 'njMule on GitHub',
                },
            ],
        },
        footer: {
            style: 'dark',
            links: [
                {
                    title: 'Documentation',
                    items: [
                        { label: 'Getting started', to: '/docs/getting-started/requirements' },
                        { label: 'Desktop app', to: '/docs/desktop/interface' },
                        { label: 'Web interface', to: '/docs/web/overview' },
                        { label: 'Developers', to: '/developer' },
                    ],
                },
                {
                    title: 'Project',
                    items: [
                        { label: 'GitHub', href: repository },
                        { label: 'Releases', href: `${repository}/releases` },
                        { label: 'Issues', href: `${repository}/issues` },
                        { label: 'Report a vulnerability', href: `${repository}/security/advisories/new` },
                    ],
                },
                {
                    title: 'More',
                    items: [
                        { label: 'Changelog', to: '/docs/about/changelog' },
                        { label: 'License and credits', to: '/docs/about/license' },
                        { label: 'Buy me a coffee', href: 'https://buymeacoffee.com/scoppolap' },
                    ],
                },
            ],
            copyright:
                `© 2026 Salvatore Coppola and the njMule contributors. njMule is free software under the ` +
                `GNU GPL v3 or later; this documentation is under CC BY-SA 4.0. ` +
                `njMule is not affiliated with the eMule project. Documentation of version ${version}.`,
        },
        prism: {
            theme: prismThemes.github,
            darkTheme: prismThemes.dracula,
            additionalLanguages: ['bash', 'powershell', 'ini', 'properties', 'yaml', 'json', 'nginx', 'java', 'kotlin'],
        },
        mermaid: { theme: { light: 'neutral', dark: 'dark' } },
        // The code samples of the API reference: the languages a script of njMule is most often written in.
        languageTabs: [
            { language: 'curl' },
            { language: 'python' },
            { language: 'javascript' },
            { language: 'java' },
            { language: 'powershell' },
            { language: 'go' },
        ],
    } satisfies Preset.ThemeConfig,
};

export default config;
