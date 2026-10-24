// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The shapes of the API's answers, as the schemas of openapi.yaml describe them.

export interface Server {
    address: string;
    name: string;
    description: string;
    users: number;
    files: number;
    maxUsers: number;
    lowIdUsers: number;
    softFiles: number;
    hardFiles: number;
    failed: number;
    priority: 'low' | 'normal' | 'high';
    ping: number;
    version: string;
    obfuscation: boolean;
    static: boolean;
}

export interface Kad {
    connected: boolean;
    firewalled: boolean;
    contacts: number;
    verifiedContacts: number;
    id: string;
    publicIp: string;
    udpPort: number;
    udpFirewalled: boolean;
    udpVerified: boolean;
    buddy: string;
}

export interface Status {
    server: Server | null;
    clientId: number;
    lowId: boolean;
    obfuscated: boolean;
    kad: Kad | null;
    downloadBytesPerSecond: number;
    uploadBytesPerSecond: number;
    downloaded: number;
    uploaded: number;
    downloads: number;
    uploads: number;
    queued: number;
    sharedFiles: number;
    totalDownloaded: number;
    totalUploaded: number;
    sessions: number;
    sessionSeconds: number;
    totalSeconds: number;
    publicIp: string;
    publicIpv6?: string;
    tcpPort: number;
    missingInterface?: string;
}

export type DownloadStatus = 'downloading' | 'paused' | 'stopped' | 'no_space' | 'error' | 'complete';
export type DownloadPriority = 'low' | 'normal' | 'high' | 'auto';

export interface Download {
    hash: string;
    name: string;
    size: number;
    completed: number;
    status: DownloadStatus;
    priority: DownloadPriority;
    category: number;
    sources: number;
    transferring: number;
    bytesPerSecond: number;
    previewFirst: boolean;
    link: string;
}
