// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The settings pages of the desktop app, less what only a desktop has (window, tray, sounds, file choosers), as rows
// bound to the keys of njmule.properties.

export interface Option {
    value: string;
    label: string;
}

export interface Row {
    key: string;
    label: string;
    kind: 'switch' | 'number' | 'text' | 'lines' | 'choice' | 'password';
    hint?: string;
    min?: number;
    max?: number;
    options?: Option[];
    // Waits for "Apply and reconnect", as it takes effect when njMule connects again.
    held?: boolean;
    // Shown only while another key has one of these values.
    when?: { key: string; values: string[] };
}

export interface Section {
    title?: string;
    rows: Row[];
}

export interface Page {
    id: string;
    title: string;
    summary: string;
    sections: Section[];
}

const EVENTS: [string, string][] = [
    ['downloadCompleted', 'nj.events.downloadCompleted'],
    ['downloadFailed', 'nj.events.downloadFailed'],
    ['chatMessage', 'nj.events.chatMessage'],
    ['diskSpaceShort', 'nj.events.diskSpaceShort'],
];

export const PAGES: Page[] = [
    {
        id: 'general',
        title: 'IDS_PW_GENERAL',
        summary: 'nj.settings.page.general',
        sections: [
            { rows: [{ key: 'user.nick', label: 'IDS_PW_NICK', kind: 'text', max: 50 }] },
            {
                title: 'nj.update.title',
                rows: [
                    {
                        key: 'update.mode',
                        label: 'nj.update.mode',
                        kind: 'choice',
                        hint: 'nj.update.note',
                        options: [
                            { value: 'OFF', label: 'nj.update.mode.off' },
                            { value: 'NOTIFY', label: 'nj.update.mode.notify' },
                            { value: 'DOWNLOAD', label: 'nj.update.mode.download' },
                            { value: 'INSTALL', label: 'nj.update.mode.install' },
                        ],
                    },
                    {
                        key: 'update.channel',
                        label: 'nj.update.channel',
                        kind: 'choice',
                        options: [
                            { value: 'STABLE', label: 'nj.update.channel.stable' },
                            { value: 'PRERELEASE', label: 'nj.update.channel.prerelease' },
                        ],
                    },
                    {
                        key: 'update.installAt',
                        label: 'nj.update.installAt',
                        kind: 'choice',
                        options: [
                            { value: 'EXIT', label: 'nj.update.installAt.exit' },
                            { value: 'EXIT_OR_NIGHT', label: 'nj.update.installAt.exit_or_night' },
                        ],
                        when: { key: 'update.mode', values: ['INSTALL'] },
                    },
                    {
                        key: 'update.nightHours',
                        label: 'nj.update.nightHours',
                        kind: 'text',
                        hint: 'nj.update.nightHours.hint',
                        max: 13,
                        when: { key: 'update.installAt', values: ['EXIT_OR_NIGHT'] },
                    },
                ],
            },
        ],
    },
    {
        id: 'connection',
        title: 'nj.settings.connection',
        summary: 'nj.settings.page.connection',
        sections: [
            {
                title: 'nj.network.ports',
                rows: [
                    { key: 'net.tcpPort', label: 'IDS_PW_CLIENTPORT', kind: 'number', min: 1, max: 65535, held: true },
                    { key: 'net.udpPort', label: 'UDP', kind: 'number', min: 1, max: 65535, held: true },
                    { key: 'net.upnp', label: 'nj.settings.upnp', kind: 'switch', held: true },
                ],
            },
            {
                title: 'IDS_NETWORK',
                rows: [
                    { key: 'net.ed2k.enabled', label: 'nj.settings.ed2k', kind: 'switch' },
                    { key: 'net.kad.enabled', label: 'nj.settings.kad', kind: 'switch' },
                    { key: 'net.autoConnect', label: 'IDS_PW_AUTOCON', kind: 'switch' },
                    { key: 'net.reconnect', label: 'IDS_PW_RECON', kind: 'switch' },
                    {
                        key: 'security.obfuscation',
                        label: 'nj.settings.obfuscation',
                        kind: 'choice',
                        options: [
                            { value: 'DISABLED', label: 'nj.obfuscation.disabled' },
                            { value: 'SUPPORTED', label: 'nj.obfuscation.supported' },
                            { value: 'REQUESTED', label: 'nj.obfuscation.requested' },
                            { value: 'REQUIRED', label: 'nj.obfuscation.required' },
                        ],
                    },
                    {
                        key: 'net.bindInterface',
                        label: 'nj.settings.bindInterface',
                        kind: 'text',
                        hint: 'nj.settings.bindInterface.hint',
                        max: 256,
                        held: true,
                    },
                ],
            },
            {
                title: 'IDS_PW_CON_LIMITFRM',
                rows: [
                    { key: 'net.maxConnections', label: 'IDS_PW_MAXC', kind: 'number', min: 1, max: 10000 },
                    { key: 'net.newConnectionsPer5s', label: 'IDS_MAXCON5SECLABEL', kind: 'number', min: 1, max: 1000 },
                    {
                        key: 'download.maxSourcesPerFile',
                        label: 'IDS_PW_MAXSOURCES',
                        kind: 'number',
                        min: 1,
                        max: 10000,
                    },
                    { key: 'upload.queueSize', label: 'IDS_QUEUESIZE', kind: 'number', min: 100, max: 100000 },
                ],
            },
        ],
    },
    {
        id: 'bandwidth',
        title: 'nj.settings.bandwidth',
        summary: 'nj.settings.page.bandwidth',
        sections: [
            {
                title: 'IDS_PW_CON_CAPFRM',
                rows: [
                    {
                        key: 'limits.downloadCapacityKiBps',
                        label: 'nj.settings.downloadCapacity',
                        kind: 'number',
                        min: 1,
                        max: 10000000,
                        hint: 'KB/s',
                    },
                    {
                        key: 'limits.uploadCapacityKiBps',
                        label: 'nj.settings.uploadCapacity',
                        kind: 'number',
                        min: 1,
                        max: 10000000,
                        hint: 'KB/s',
                    },
                ],
            },
            {
                title: 'IDS_PW_CON_LIMITFRM',
                rows: [
                    {
                        key: 'limits.downloadKiBps',
                        label: 'nj.settings.download',
                        kind: 'number',
                        min: 0,
                        max: 10000000,
                        hint: 'nj.rate.noLimit',
                    },
                    {
                        key: 'limits.uploadKiBps',
                        label: 'nj.settings.upload',
                        kind: 'number',
                        min: 0,
                        max: 10000000,
                        hint: 'nj.rate.noLimit',
                    },
                ],
            },
        ],
    },
    {
        id: 'servers',
        title: 'IDS_PW_SERVER',
        summary: 'nj.settings.page.servers',
        sections: [
            {
                rows: [
                    {
                        key: 'server.deadRetries',
                        label: 'IDS_PW_RDEAD',
                        kind: 'number',
                        min: 1,
                        max: 10,
                        hint: 'IDS_PW_RETRIES',
                    },
                    { key: 'server.addFromServer', label: 'IDS_PW_USC', kind: 'switch' },
                    { key: 'server.addFromClients', label: 'IDS_PW_UCC', kind: 'switch' },
                    { key: 'server.staticOnly', label: 'IDS_PW_AUTOCONNECTSTATICONLY', kind: 'switch' },
                    { key: 'server.manualHighPriority', label: 'IDS_MANUALSERVERHIGHPRIO', kind: 'switch' },
                    { key: 'server.updateAtStart', label: 'IDS_PW_USS', kind: 'switch' },
                ],
            },
        ],
    },
    {
        id: 'proxy',
        title: 'IDS_PW_PROXY',
        summary: 'nj.settings.page.proxy',
        sections: [
            {
                rows: [
                    {
                        key: 'proxy.type',
                        label: 'nj.proxy.type',
                        kind: 'choice',
                        held: true,
                        options: [
                            { value: 'NONE', label: 'nj.proxy.none' },
                            { value: 'SOCKS', label: 'nj.proxy.socks' },
                            { value: 'HTTP', label: 'nj.proxy.http' },
                        ],
                    },
                    { key: 'proxy.host', label: 'nj.proxy.host', kind: 'text', max: 255, held: true },
                    { key: 'proxy.port', label: 'IDS_PW_CLIENTPORT', kind: 'number', min: 1, max: 65535, held: true },
                    { key: 'proxy.user', label: 'nj.proxy.user', kind: 'text', max: 255, held: true },
                    { key: 'proxy.password', label: 'nj.input.password', kind: 'password', held: true },
                ],
            },
        ],
    },
    {
        id: 'folders',
        title: 'IDS_PW_DIR',
        summary: 'nj.settings.page.folders',
        sections: [
            {
                rows: [
                    { key: 'dirs.incoming', label: 'IDS_PW_INCOMING', kind: 'text', hint: 'nj.settings.defaultDir' },
                    { key: 'dirs.temp', label: 'IDS_PW_TEMP', kind: 'text', hint: 'nj.settings.nextStart' },
                    {
                        key: 'download.minFreeSpaceMiB',
                        label: 'IDS_MINFREEDISKSPACE',
                        kind: 'number',
                        min: 0,
                        max: 10000000,
                    },
                ],
            },
            {
                title: 'IDS_TW_DOWNLOADS',
                rows: [
                    { key: 'download.addPaused', label: 'IDS_ADDNEWFILESPAUSED', kind: 'switch' },
                    {
                        key: 'download.startNext',
                        label: 'IDS_STARTNEXTFILE',
                        kind: 'choice',
                        options: [
                            { value: 'NONE', label: 'IDS_NO' },
                            { value: 'ANY', label: 'nj.settings.anyCategory' },
                            { value: 'SAME_CATEGORY_FIRST', label: 'IDS_PREF_STARTNEXTFILECAT' },
                            { value: 'SAME_CATEGORY', label: 'IDS_PREF_STARTNEXTFILECATONLY' },
                        ],
                    },
                    { key: 'files.cleanUpNewNames', label: 'IDS_AUTOCLEANUPFN', kind: 'switch' },
                    { key: 'files.nameCleanups', label: 'IDS_FILTERFILENAMEWORD', kind: 'text' },
                    { key: 'files.rememberDownloaded', label: 'IDS_PW_REMEMBERDOWNLOADED', kind: 'switch' },
                    { key: 'files.rememberCancelled', label: 'IDS_PW_REMEMBERCANCELLED', kind: 'switch' },
                    { key: 'download.previewFirst', label: 'IDS_DOWNLOADMOVIECHUNKS', kind: 'switch' },
                ],
            },
            {
                title: 'IDS_SHAREDFILES',
                rows: [
                    {
                        key: 'share.visibleTo',
                        label: 'IDS_PW_SHARE',
                        kind: 'choice',
                        options: [
                            { value: 'EVERYBODY', label: 'IDS_PW_EVER' },
                            { value: 'FRIENDS', label: 'IDS_PW_FRIENDS' },
                            { value: 'NOBODY', label: 'IDS_PW_NOONE' },
                        ],
                    },
                ],
            },
        ],
    },
    {
        id: 'ipfilter',
        title: 'IDS_IPFILTER',
        summary: 'nj.settings.page.ipfilter',
        sections: [
            {
                rows: [
                    {
                        key: 'security.ipFilterLists',
                        label: 'nj.settings.ipFilterLists',
                        kind: 'lines',
                        hint: 'nj.settings.ipFilterListsHint',
                    },
                    {
                        key: 'security.ipFilterUpdateDays',
                        label: 'nj.settings.ipFilterUpdateDays',
                        kind: 'number',
                        min: 0,
                        max: 365,
                    },
                    { key: 'security.ipFilterLevel', label: 'IDS_FILTERLEVEL', kind: 'number', min: 0, max: 255 },
                    { key: 'security.filterLan', label: 'IDS_PW_FILTER', kind: 'switch' },
                    {
                        key: 'security.ipFilterWhitelist',
                        label: 'nj.settings.whitelist',
                        kind: 'lines',
                        hint: 'nj.settings.whitelistHint',
                    },
                ],
            },
            { title: 'IDS_SPAM', rows: [{ key: 'search.spamFilter', label: 'IDS_SEARCHSPAMFILTER', kind: 'switch' }] },
        ],
    },
    {
        id: 'messages',
        title: 'IDS_EM_MESSAGES',
        summary: 'nj.settings.page.messages',
        sections: [
            {
                rows: [
                    { key: 'chat.filter', label: 'IDS_FILTERLABEL', kind: 'text', max: 4096 },
                    { key: 'chat.friendsOnly', label: 'IDS_MSGONLYFRIENDS', kind: 'switch' },
                    { key: 'chat.spamFilter', label: 'IDS_ADVSPAMFILTER', kind: 'switch' },
                ],
            },
        ],
    },
    {
        id: 'events',
        title: 'nj.settings.events',
        summary: 'nj.settings.page.events',
        sections: EVENTS.map(([id, title]) => ({
            title,
            rows: [
                { key: `events.${id}.enabled`, label: title, kind: 'switch' as const },
                { key: `events.${id}.command`, label: 'nj.settings.events', kind: 'text' as const, max: 4096 },
            ],
        })),
    },
    {
        id: 'advanced',
        title: 'IDS_PW_TWEAK',
        summary: 'nj.settings.page.advanced',
        sections: [
            {
                title: 'IDS_SECURITY',
                rows: [
                    { key: 'security.secureIdent', label: 'nj.settings.secureIdent', kind: 'switch' },
                    { key: 'upload.creditSystem', label: 'nj.settings.credits', kind: 'switch' },
                ],
            },
            {
                title: 'IDS_PW_MISC',
                rows: [
                    { key: 'search.store', label: 'IDS_STORESEARCHES', kind: 'switch' },
                    { key: 'backup.copies', label: 'nj.settings.backups', kind: 'number', min: 0, max: 50 },
                ],
            },
        ],
    },
];

/** Whether {@code row} matches the words looked for, in its label, its hint or its page. */
export function matches(row: Row, page: Page, words: string, t: (key: string) => string): boolean {
    if (words.trim() === '') {
        return true;
    }
    const text = [row.label, row.hint ?? '', page.title, page.summary]
        .map((k) => t(k))
        .join(' ')
        .toLowerCase();
    return words
        .toLowerCase()
        .split(/\s+/)
        .filter((w) => w !== '')
        .every((w) => text.includes(w));
}
