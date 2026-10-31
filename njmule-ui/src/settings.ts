// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { IconName } from './icons';

// The settings pages of the desktop app as rows bound to the keys of njmule.properties; the scheduler and the web
// interface are pages of their own.

export interface Option {
    value: string;
    label: string;
}

/** That another key holds one of these values. */
export interface Condition {
    key: string;
    values: string[];
}

export type Kind =
    | 'switch'
    | 'number'
    | 'text'
    | 'lines'
    | 'choice'
    | 'password'
    // The values of the options ticked, apart by commas.
    | 'flags'
    // A speed limit in KB/s or MB/s, or no limit (0); kept in KiB/s.
    | 'limit'
    // The speed of a line in Mbit/s, kept in KiB/s.
    | 'line'
    // The next download to start: a switch, and the categories it takes while on; NONE while off.
    | 'next'
    // A folder of the engine's computer, typed or chosen; empty for the default one.
    | 'folder'
    // A network interface of the engine's computer, by its name or an address; empty for any.
    | 'interface';

/** A check of the value typed, as the desktop app's Inputs: see screens/settings/inputs.ts. */
export type Check =
    | 'nick'
    | 'name'
    | 'host'
    | 'webhook'
    | 'nightHours'
    | 'soundFile'
    | 'folder'
    | 'commandLine'
    | 'whitelist'
    | 'lists';

/** One control of a row, bound to one key. */
export interface Field {
    key: string;
    kind: Kind;
    /** The least and the most of a number; the longest text of a text. */
    min?: number;
    max?: number;
    options?: Option[];
    check?: Check;
    /** Shown in the empty field: a translation key, or words the same in every language. */
    placeholder?: string;
    /** Read left to right in every language: paths, addresses and command lines. */
    ltr?: boolean;
    /** A word after the field, such as its unit. */
    unit?: string;
    /** The width in characters, as the desktop app's columns. */
    columns?: number;
    /** Takes the width the row leaves. */
    grow?: boolean;
    /** The lines a text area shows. */
    lines?: number;
    /** Usable only while each condition holds. */
    enabledWhen?: Condition[];
    /** Must not be empty while this holds. */
    requiredWhen?: Condition;
    /** The words a command line may hold, filled in when it runs. */
    placeholders?: string[];
}

/** A button of a row, which acts rather than sets. */
export type Action = 'checkUpdate' | 'serverLists' | 'updateIpFilter' | 'testNotifications';

export interface Row {
    /** A translation key, or a word the same in every language such as UDP; empty for none. */
    label: string;
    /** Words after the label, the same in every language. */
    suffix?: string;
    fields: Field[];
    hint?: string;
    /** The control under the name, the width of the page: for paths, lists and command lines. */
    wide?: boolean;
    /** Waits for "Apply and reconnect", as it takes effect when njMule connects again. */
    held?: boolean;
    /** The default in the reset link: the value itself, or a translation key; by the kind of control otherwise. */
    defaultWords?: 'value' | string;
    action?: Action;
    /** An option of this window rather than of the engine; of the desktop app's shell for the others. */
    local?: 'language' | 'theme' | 'startMinimized' | 'minimizeToTray' | 'confirmExit' | 'watchClipboard';
    /** The test of the ports, run on the ports as their fields give them. */
    portTest?: boolean;
}

export interface Section {
    title?: string;
    rows: Row[];
}

export interface Page {
    id: string;
    title: string;
    summary: string;
    icon: IconName;
    sections: Section[];
}

export interface EventKind {
    id: string;
    placeholders: string[];
}

// The events a program can be run on, with the words filled in.
export const EVENTS: EventKind[] = [
    { id: 'downloadCompleted', placeholders: ['%NAME', '%FILE', '%HASH', '%SIZE'] },
    { id: 'downloadFailed', placeholders: ['%NAME', '%HASH', '%SIZE', '%MESSAGE'] },
    { id: 'chatMessage', placeholders: ['%SENDER', '%MESSAGE'] },
    { id: 'diskSpaceShort', placeholders: ['%FILE'] },
];

const PORT = { kind: 'number', min: 1, max: 65535 } as const;
const FOLDER = {
    kind: 'folder',
    check: 'folder',
    ltr: true,
    grow: true,
    columns: 32,
    placeholder: 'nj.settings.defaultDir',
} as const;
const PROXY_ON: Condition = { key: 'proxy.type', values: ['SOCKS', 'HTTP'] };

function one(field: Field, row: Omit<Row, 'fields'>): Row {
    return { ...row, fields: [field] };
}

function toggle(key: string, label: string, more: Omit<Row, 'fields' | 'label'> = {}): Row {
    return one({ key, kind: 'switch' }, { label, ...more });
}

function number(key: string, label: string, min: number, max: number): Row {
    return one({ key, kind: 'number', min, max }, { label });
}

export const PAGES: Page[] = [
    {
        id: 'general',
        title: 'IDS_PW_GENERAL',
        summary: 'nj.settings.page.general',
        icon: 'OPTIONS',
        sections: [
            {
                rows: [
                    one(
                        { key: 'user.nick', kind: 'text', check: 'nick', max: 50, columns: 16 },
                        { label: 'IDS_PW_NICK', defaultWords: 'value' },
                    ),
                ],
            },
            {
                title: 'IDS_PW_DISPLAY',
                rows: [
                    { label: 'IDS_PW_LANG', fields: [], local: 'language' },
                    { label: 'nj.settings.theme', fields: [], local: 'theme' },
                ],
            },
            {
                title: 'IDS_STARTUP',
                rows: [
                    { label: 'IDS_PREF_STARTMIN', fields: [], local: 'startMinimized' },
                    { label: 'nj.settings.tray', fields: [], local: 'minimizeToTray' },
                    toggle('system.preventSleep', 'nj.settings.preventSleep'),
                    toggle('geo.countries', 'nj.settings.countries', { hint: 'nj.settings.countriesHint' }),
                ],
            },
            {
                title: 'nj.update.title',
                rows: [
                    one(
                        {
                            key: 'update.mode',
                            kind: 'choice',
                            options: [
                                { value: 'OFF', label: 'nj.update.mode.off' },
                                { value: 'NOTIFY', label: 'nj.update.mode.notify' },
                                { value: 'DOWNLOAD', label: 'nj.update.mode.download' },
                                { value: 'INSTALL', label: 'nj.update.mode.install' },
                            ],
                        },
                        { label: 'nj.update.mode', hint: 'nj.update.note' },
                    ),
                    one(
                        {
                            key: 'update.channel',
                            kind: 'choice',
                            options: [
                                { value: 'STABLE', label: 'nj.update.channel.stable' },
                                { value: 'PRERELEASE', label: 'nj.update.channel.prerelease' },
                            ],
                        },
                        { label: 'nj.update.channel' },
                    ),
                    // When to install matters only to what installs on its own; the night hours only to the night.
                    one(
                        {
                            key: 'update.installAt',
                            kind: 'choice',
                            options: [
                                { value: 'EXIT', label: 'nj.update.installAt.exit' },
                                { value: 'EXIT_OR_NIGHT', label: 'nj.update.installAt.exit_or_night' },
                            ],
                            enabledWhen: [{ key: 'update.mode', values: ['INSTALL'] }],
                        },
                        { label: 'nj.update.installAt' },
                    ),
                    one(
                        {
                            key: 'update.nightHours',
                            kind: 'text',
                            check: 'nightHours',
                            ltr: true,
                            columns: 9,
                            enabledWhen: [
                                { key: 'update.mode', values: ['INSTALL'] },
                                { key: 'update.installAt', values: ['EXIT_OR_NIGHT'] },
                            ],
                        },
                        { label: 'nj.update.nightHours', hint: 'nj.update.nightHours.hint', defaultWords: 'value' },
                    ),
                    { label: 'IDS_CHECK4UPDATE', fields: [], action: 'checkUpdate' },
                ],
            },
            {
                title: 'IDS_PW_MISC',
                rows: [
                    { label: 'IDS_PW_PROMPT', fields: [], local: 'confirmExit' },
                    { label: 'IDS_PF_WATCHCB', fields: [], local: 'watchClipboard' },
                ],
            },
            {
                title: 'IDS_PW_EKDEV_OPTIONS',
                rows: [
                    toggle('notify.sound', 'nj.settings.sound'),
                    one(
                        {
                            key: 'notify.soundFile',
                            kind: 'text',
                            check: 'soundFile',
                            ltr: true,
                            grow: true,
                            columns: 32,
                        },
                        { label: 'nj.settings.soundFile', wide: true },
                    ),
                ],
            },
        ],
    },
    {
        id: 'connection',
        title: 'nj.settings.connection',
        summary: 'nj.settings.page.connection',
        icon: 'LINK',
        sections: [
            {
                title: 'nj.network.ports',
                rows: [
                    one({ key: 'net.tcpPort', ...PORT }, { label: 'IDS_PW_CLIENTPORT', suffix: ' (TCP)', held: true }),
                    one({ key: 'net.udpPort', ...PORT }, { label: 'UDP', held: true }),
                    toggle('net.upnp', 'nj.settings.upnp', { held: true }),
                    // The test's button names it.
                    { label: '', fields: [], wide: true, portTest: true },
                ],
            },
            {
                title: 'IDS_NETWORK',
                rows: [
                    toggle('net.ed2k.enabled', 'nj.settings.ed2k'),
                    toggle('net.kad.enabled', 'nj.settings.kad'),
                    toggle('net.autoConnect', 'IDS_PW_AUTOCON'),
                    toggle('net.reconnect', 'IDS_PW_RECON'),
                    one(
                        {
                            key: 'security.obfuscation',
                            kind: 'choice',
                            options: [
                                { value: 'DISABLED', label: 'nj.obfuscation.disabled' },
                                { value: 'SUPPORTED', label: 'nj.obfuscation.supported' },
                                { value: 'REQUESTED', label: 'nj.obfuscation.requested' },
                                { value: 'REQUIRED', label: 'nj.obfuscation.required' },
                            ],
                        },
                        { label: 'nj.settings.obfuscation' },
                    ),
                    one(
                        { key: 'net.bindInterface', kind: 'interface' },
                        {
                            label: 'nj.settings.bindInterface',
                            hint: 'nj.settings.bindInterface.hint',
                            held: true,
                            defaultWords: 'nj.settings.bindInterface.any',
                        },
                    ),
                ],
            },
            {
                title: 'IDS_PW_CON_LIMITFRM',
                rows: [
                    number('net.maxConnections', 'IDS_PW_MAXC', 1, 10000),
                    number('net.newConnectionsPer5s', 'IDS_MAXCON5SECLABEL', 1, 1000),
                    number('download.maxSourcesPerFile', 'IDS_PW_MAXSOURCES', 1, 10000),
                    number('upload.queueSize', 'IDS_QUEUESIZE', 100, 100000),
                ],
            },
        ],
    },
    {
        id: 'bandwidth',
        title: 'nj.settings.bandwidth',
        summary: 'nj.settings.page.bandwidth',
        icon: 'SPEED',
        sections: [
            {
                title: 'IDS_PW_CON_CAPFRM',
                rows: [
                    one(
                        { key: 'limits.downloadCapacityKiBps', kind: 'line', min: 1 },
                        { label: 'nj.settings.downloadCapacity' },
                    ),
                    one(
                        { key: 'limits.uploadCapacityKiBps', kind: 'line', min: 1 },
                        { label: 'nj.settings.uploadCapacity' },
                    ),
                ],
            },
            {
                title: 'IDS_PW_CON_LIMITFRM',
                rows: [
                    one({ key: 'limits.downloadKiBps', kind: 'limit', min: 1 }, { label: 'nj.settings.download' }),
                    one({ key: 'limits.uploadKiBps', kind: 'limit', min: 1 }, { label: 'nj.settings.upload' }),
                ],
            },
        ],
    },
    {
        id: 'servers',
        title: 'IDS_PW_SERVER',
        summary: 'nj.settings.page.servers',
        icon: 'SERVER',
        sections: [
            {
                rows: [
                    one(
                        { key: 'server.deadRetries', kind: 'number', min: 1, max: 10, unit: 'IDS_PW_RETRIES' },
                        { label: 'IDS_PW_RDEAD' },
                    ),
                    toggle('server.addFromServer', 'IDS_PW_USC'),
                    toggle('server.addFromClients', 'IDS_PW_UCC'),
                    toggle('server.staticOnly', 'IDS_PW_AUTOCONNECTSTATICONLY'),
                    toggle('server.manualHighPriority', 'IDS_MANUALSERVERHIGHPRIO'),
                    toggle('server.updateAtStart', 'IDS_PW_USS', { action: 'serverLists' }),
                ],
            },
        ],
    },
    {
        id: 'proxy',
        title: 'IDS_PW_PROXY',
        summary: 'nj.settings.page.proxy',
        icon: 'SWAP',
        sections: [
            {
                rows: [
                    one(
                        {
                            key: 'proxy.type',
                            kind: 'choice',
                            options: [
                                { value: 'NONE', label: 'nj.proxy.none' },
                                { value: 'SOCKS', label: 'nj.proxy.socks' },
                                { value: 'HTTP', label: 'nj.proxy.http' },
                            ],
                        },
                        { label: 'nj.proxy.type', held: true },
                    ),
                    // Without a proxy there is nothing to fill in.
                    {
                        label: 'nj.proxy.host',
                        held: true,
                        fields: [
                            {
                                key: 'proxy.host',
                                kind: 'text',
                                check: 'host',
                                ltr: true,
                                enabledWhen: [PROXY_ON],
                                requiredWhen: PROXY_ON,
                            },
                            { key: 'proxy.port', ...PORT, enabledWhen: [PROXY_ON] },
                        ],
                    },
                    {
                        label: 'nj.proxy.user',
                        held: true,
                        fields: [
                            { key: 'proxy.user', kind: 'text', check: 'name', max: 255, enabledWhen: [PROXY_ON] },
                            { key: 'proxy.password', kind: 'password', enabledWhen: [PROXY_ON] },
                        ],
                    },
                ],
            },
        ],
    },
    {
        id: 'folders',
        title: 'IDS_PW_DIR',
        summary: 'nj.settings.page.folders',
        icon: 'FOLDER_OPEN',
        sections: [
            {
                rows: [
                    one(
                        { key: 'dirs.incoming', ...FOLDER },
                        { label: 'IDS_PW_INCOMING', wide: true, defaultWords: 'nj.settings.defaultDir' },
                    ),
                    one(
                        { key: 'dirs.temp', ...FOLDER },
                        {
                            label: 'IDS_PW_TEMP',
                            hint: 'nj.settings.nextStart',
                            wide: true,
                            defaultWords: 'nj.settings.defaultDir',
                        },
                    ),
                    number('download.minFreeSpaceMiB', 'IDS_MINFREEDISKSPACE', 0, 10000000),
                ],
            },
            {
                title: 'IDS_TW_DOWNLOADS',
                rows: [
                    toggle('download.addPaused', 'IDS_ADDNEWFILESPAUSED'),
                    one(
                        {
                            key: 'download.startNext',
                            kind: 'next',
                            options: [
                                { value: 'ANY', label: 'nj.settings.anyCategory' },
                                { value: 'SAME_CATEGORY_FIRST', label: 'IDS_PREF_STARTNEXTFILECAT' },
                                { value: 'SAME_CATEGORY', label: 'IDS_PREF_STARTNEXTFILECATONLY' },
                            ],
                        },
                        { label: 'IDS_STARTNEXTFILE' },
                    ),
                    toggle('files.cleanUpNewNames', 'IDS_AUTOCLEANUPFN'),
                    one(
                        { key: 'files.nameCleanups', kind: 'text', grow: true, columns: 30 },
                        { label: 'IDS_FILTERFILENAMEWORD', wide: true },
                    ),
                    toggle('files.rememberDownloaded', 'IDS_PW_REMEMBERDOWNLOADED'),
                    toggle('files.rememberCancelled', 'IDS_PW_REMEMBERCANCELLED'),
                    one(
                        { key: 'download.autoDeleteDays', kind: 'number', min: 0, max: 365 },
                        { label: 'nj.settings.autoDeleteDays', hint: 'nj.settings.autoDeleteHint' },
                    ),
                ],
            },
            {
                title: 'IDS_DL_PREVIEW',
                rows: [
                    toggle('download.previewFirst', 'IDS_DOWNLOADMOVIECHUNKS'),
                    {
                        label: 'IDS_PW_VIDEOPLAYER',
                        wide: true,
                        fields: [
                            { key: 'preview.player', kind: 'text', ltr: true, grow: true, columns: 26 },
                            {
                                key: 'preview.args',
                                kind: 'text',
                                ltr: true,
                                columns: 14,
                                placeholder: 'nj.preview.args',
                            },
                        ],
                    },
                ],
            },
            {
                title: 'IDS_SHAREDFILES',
                rows: [
                    one(
                        {
                            key: 'share.visibleTo',
                            kind: 'choice',
                            options: [
                                { value: 'EVERYBODY', label: 'IDS_PW_EVER' },
                                { value: 'FRIENDS', label: 'IDS_PW_FRIENDS' },
                                { value: 'NOBODY', label: 'IDS_PW_NOONE' },
                            ],
                        },
                        { label: 'IDS_PW_SHARE' },
                    ),
                ],
            },
        ],
    },
    {
        id: 'ipfilter',
        title: 'IDS_IPFILTER',
        summary: 'nj.settings.page.ipfilter',
        icon: 'BLOCK',
        sections: [
            {
                rows: [
                    one(
                        {
                            key: 'security.ipFilterLists',
                            kind: 'lines',
                            check: 'lists',
                            ltr: true,
                            grow: true,
                            lines: 2,
                            placeholder: 'https://…/ipfilter.zip\nhttps://…/level1.p2p.gz',
                        },
                        {
                            label: 'nj.settings.ipFilterLists',
                            hint: 'nj.settings.ipFilterListsHint',
                            wide: true,
                            action: 'updateIpFilter',
                        },
                    ),
                    number('security.ipFilterUpdateDays', 'nj.settings.ipFilterUpdateDays', 0, 365),
                    number('security.ipFilterLevel', 'IDS_FILTERLEVEL', 0, 255),
                    toggle('security.filterLan', 'IDS_PW_FILTER'),
                    one(
                        {
                            key: 'security.ipFilterWhitelist',
                            kind: 'lines',
                            check: 'whitelist',
                            ltr: true,
                            lines: 3,
                            columns: 32,
                            placeholder: '1.2.3.4\n10.0.0.0/8',
                        },
                        { label: 'nj.settings.whitelist', hint: 'nj.settings.whitelistHint', wide: true },
                    ),
                ],
            },
            { title: 'IDS_SPAM', rows: [toggle('search.spamFilter', 'IDS_SEARCHSPAMFILTER')] },
        ],
    },
    {
        id: 'messages',
        title: 'IDS_EM_MESSAGES',
        summary: 'nj.settings.page.messages',
        icon: 'MESSAGE',
        sections: [
            {
                rows: [
                    one(
                        { key: 'chat.filter', kind: 'text', max: 4096, grow: true, columns: 30 },
                        { label: 'IDS_FILTERLABEL', wide: true },
                    ),
                    toggle('chat.friendsOnly', 'IDS_MSGONLYFRIENDS'),
                    toggle('chat.spamFilter', 'IDS_ADVSPAMFILTER'),
                ],
            },
        ],
    },
    {
        id: 'scheduler',
        title: 'nj.scheduler.title',
        summary: 'nj.settings.page.scheduler',
        icon: 'CLOCK',
        sections: [],
    },
    {
        id: 'events',
        title: 'nj.settings.events',
        summary: 'nj.settings.page.events',
        icon: 'PLAY',
        sections: [
            {
                rows: EVENTS.map((e): Row => ({
                    label: `nj.events.${e.id}`,
                    wide: true,
                    fields: [
                        { key: `events.${e.id}.enabled`, kind: 'switch' },
                        {
                            key: `events.${e.id}.command`,
                            kind: 'text',
                            check: 'commandLine',
                            ltr: true,
                            grow: true,
                            columns: 30,
                            placeholder: e.placeholders.join(' '),
                            placeholders: e.placeholders,
                        },
                    ],
                })),
            },
        ],
    },
    {
        id: 'notify',
        title: 'IDS_PW_EKDEV_OPTIONS',
        summary: 'nj.notify.page',
        icon: 'SEND',
        sections: [
            {
                title: 'IDS_PW_EMAILNOTIFICATIONS',
                rows: [
                    toggle('notify.email', 'IDS_PW_ENABLEEMAIL'),
                    {
                        label: 'IDS_SMTPSERVER',
                        fields: [
                            {
                                key: 'notify.smtpHost',
                                kind: 'text',
                                check: 'host',
                                ltr: true,
                                requiredWhen: { key: 'notify.email', values: ['true'] },
                            },
                            { key: 'notify.smtpPort', ...PORT, columns: 12 },
                        ],
                    },
                    one(
                        {
                            key: 'notify.smtpSecurity',
                            kind: 'choice',
                            options: [
                                { value: 'STARTTLS', label: 'nj.notify.starttls' },
                                { value: 'TLS', label: 'nj.notify.tls' },
                            ],
                        },
                        { label: 'nj.notify.security' },
                    ),
                    {
                        label: 'nj.proxy.user',
                        fields: [
                            { key: 'notify.smtpUser', kind: 'text', check: 'name', max: 255 },
                            { key: 'notify.smtpPassword', kind: 'password' },
                        ],
                    },
                    one({ key: 'notify.mailFrom', kind: 'text', check: 'name', max: 255 }, { label: 'nj.notify.from' }),
                    one(
                        { key: 'notify.mailTo', kind: 'text', check: 'name', max: 255, grow: true },
                        { label: 'nj.notify.to', wide: true },
                    ),
                ],
            },
            {
                title: 'nj.notify.hook',
                rows: [
                    toggle('notify.webhook', 'nj.notify.webhook'),
                    one(
                        { key: 'notify.webhookUrl', kind: 'text', check: 'webhook', ltr: true, grow: true },
                        { label: 'nj.notify.webhookUrl', wide: true },
                    ),
                    one(
                        {
                            key: 'notify.webhookBody',
                            kind: 'choice',
                            options: [
                                { value: 'TEXT', label: 'nj.notify.bodyText' },
                                { value: 'JSON', label: 'nj.notify.bodyJson' },
                            ],
                        },
                        { label: 'nj.notify.webhookBody' },
                    ),
                ],
            },
            {
                rows: [
                    one(
                        {
                            key: 'notify.events',
                            kind: 'flags',
                            options: EVENTS.map((e) => ({ value: e.id, label: `nj.events.${e.id}` })),
                        },
                        { label: 'nj.notify.events', wide: true },
                    ),
                    { label: '', fields: [], action: 'testNotifications' },
                ],
            },
        ],
    },
    {
        id: 'web',
        title: 'IDS_PW_WS',
        summary: 'nj.webui.note',
        icon: 'NETWORK',
        sections: [],
    },
    {
        id: 'advanced',
        title: 'IDS_PW_TWEAK',
        summary: 'nj.settings.page.advanced',
        icon: 'TOOLS',
        sections: [
            {
                title: 'IDS_PW_MISC',
                rows: [
                    toggle('search.store', 'IDS_STORESEARCHES'),
                    number('backup.copies', 'nj.settings.backups', 0, 50),
                ],
            },
        ],
    },
];

// Each language by its own name, as its translation writes it: the window loads the texts of one language only.
export const LANGUAGE_NAMES: Record<string, string> = {
    en: 'English',
    ca: 'Català',
    cs: 'Čeština',
    da: 'Dansk',
    de: 'Deutsch',
    et: 'Eesti',
    es: 'Español',
    fr: 'Français',
    gl: 'Galego',
    it: 'Italiano',
    lv: 'Latviešu',
    lt: 'Lietuvių',
    hu: 'Magyar',
    nl: 'Nederlands',
    nb: 'Norsk bokmål',
    pl: 'Polski',
    'pt-BR': 'Português (Brasil)',
    'pt-PT': 'Português (Portugal)',
    ro: 'Română',
    sq: 'Shqip',
    sl: 'Slovenščina',
    fi: 'Suomi',
    sv: 'Svenska',
    vi: 'Tiếng Việt',
    tr: 'Türkçe',
    el: 'Ελληνικά',
    bg: 'Български',
    ru: 'Русский',
    uk: 'Українська',
    ar: 'العربية',
    fa: 'فارسی',
    he: 'עברית',
    'zh-CN': '简体中文',
    'zh-TW': '繁體中文',
    ja: '日本語',
    ko: '한국어',
};

type Translate = (key: string, ...args: unknown[]) => string;

/** A label without the colon some translations end it with, as the desktop app shows it. */
export function label(text: string): string {
    return text.replace(/\s*:\s*$/, '');
}

/** A translation key's text, or the words themselves when they are no key, such as UDP. */
export function words(keyOrText: string, t: Translate): string {
    return /^(IDS_|nj\.)/.test(keyOrText) ? t(keyOrText) : keyOrText;
}

/** A row's name, as its title shows it. */
export function rowTitle(row: Row, t: Translate): string {
    return row.label === '' ? '' : label(words(row.label, t)) + (row.suffix ?? '');
}

/** Whether every word typed is in {@code text}, whatever the case. */
export function holdsWords(text: string, typed: string): boolean {
    const all = text.toLowerCase();
    return typed
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '')
        .every((w) => all.includes(w));
}

/** Whether {@code row} holds every word typed, in its name, its line, its section or its page. */
export function matches(row: Row, section: Section, page: Page, typed: string, t: Translate): boolean {
    const text = [
        rowTitle(row, t),
        row.hint ? t(row.hint) : '',
        section.title ? t(section.title) : '',
        t(page.title),
    ].join(' ');
    return holdsWords(text, typed);
}

/** Whether each condition holds, by the values of the keys they name. */
export function holds(conditions: Condition[] | undefined, value: (key: string) => string): boolean {
    return (conditions ?? []).every((c) => c.values.includes(value(c.key)));
}

/**
 * The default of {@code row} in words, as the control shows it, for its reset link; empty for a plain "Reset". A speed
 * reads in its unit, {@code rate} writing bytes per second.
 */
export function defaultWords(
    row: Row,
    defaults: Record<string, string>,
    t: Translate,
    rate: (bytesPerSecond: number) => string,
): string {
    if (row.defaultWords !== undefined) {
        return row.defaultWords === 'value' ? (defaults[row.fields[0]?.key ?? ''] ?? '') : t(row.defaultWords);
    }
    const field = row.fields[0];
    if (row.fields.length !== 1 || field === undefined) {
        return '';
    }
    const d = defaults[field.key] ?? '';
    switch (field.kind) {
        case 'switch':
            return t(d === 'true' ? 'IDS_YES' : 'IDS_NO');
        case 'choice':
            return label(t(field.options?.find((o) => o.value === d)?.label ?? d));
        case 'number':
            return d;
        case 'limit':
        case 'line':
            return d === '0' && field.kind === 'limit' ? t('nj.rate.noLimit') : rate(Number(d) * 1024);
        default:
            return '';
    }
}

/** What waits, in sentences: reconnecting for the network, restarting for the language, and a wrong value first. */
export function pendingWords(network: boolean, restart: boolean, wrong: string | undefined, t: Translate): string {
    const sentences: string[] = [];
    if (network) {
        sentences.push(t('nj.settings.pendingReconnect'));
    }
    if (restart) {
        sentences.push(t('nj.settings.pendingRestart'));
    }
    if (wrong !== undefined) {
        sentences.push(t('nj.settings.fixFirst', wrong));
    }
    return sentences.join(' ');
}
