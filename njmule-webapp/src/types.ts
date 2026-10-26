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
    overheadDownloaded: number;
    overheadUploaded: number;
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
    relatedSearch?: boolean;
    freeSpace?: { incoming?: number; temp?: number };
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
    autoSwap: boolean;
    link: string;
    kind?: FileKind;
}

export type FileKind = 'video' | 'audio' | 'image' | 'document' | 'archive' | 'disc_image' | 'program' | 'other';

/** The map of a download's parts: byte ranges [start, end) missing and asked for, and the sources of each part. */
export interface Parts {
    size: number;
    missing: [number, number][];
    requested: [number, number][];
    sources: number[];
}

export interface Upload {
    address: string;
    userHash: string;
    nick?: string;
    software: string;
    file: string;
    state: 'uploading' | 'queued';
    transferred: number;
    bytesPerSecond: number;
    waitingSeconds: number;
    country?: string | null;
}

export interface KnownClient {
    userHash: string;
    nick: string;
    software: string;
    firstSeen: number;
    lastSeen: number;
    country?: string | null;
    uploaded: number;
    downloaded: number;
}

export interface Ban {
    address: string;
    secondsLeft: number;
}

export interface Category {
    id: number;
    name: string;
    incoming?: string | null;
    comment: string;
    priority: DownloadPriority;
    color?: number;
    patterns?: string;
    regex?: boolean;
}

export type SourceState =
    'waiting' | 'connecting' | 'asking' | 'queued' | 'downloading' | 'a4af' | 'queue_full' | 'no_needed_parts';

export interface Source {
    file: string;
    fileName: string;
    address: string;
    nick?: string;
    software: string;
    state: SourceState;
    queueRank: number;
    received: number;
    parts: number;
    partCount: number;
    reportedName?: string;
    country?: string | null;
    askedFor?: string | null;
    askedForName?: string;
}
