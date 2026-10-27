// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

/** How this copy of njMule was installed, which tells how it is updated. */
export type Installation =
    'msi' | 'windows_zip' | 'dmg' | 'deb' | 'rpm' | 'tar' | 'static_tar' | 'synology' | 'docker' | 'unknown';

/** A version newer than the one running. */
export interface NewVersion {
    version: string;
    published: string;
    critical: boolean;
    notes: string;
    page: string;
    /** The package for this installation; null when the release has none, as for Docker. */
    download: { url: string; size: number; sha256: string } | null;
}

/** The state of updates, as GET /update tells it. */
export interface Update {
    current: string;
    installation: Installation;
    installer: 'none' | 'self' | 'elevated';
    canInstall: boolean;
    checking: boolean;
    lastCheck?: string | null;
    failure?: string | null;
    available: NewVersion | null;
    skipped?: string | null;
    download: { received: number; size: number } | null;
    ready: boolean;
    readyFile?: string | null;
    install: 'asked' | 'at_exit' | 'restart';
}

/** A text to show: its key, then the values of its placeholders. */
export type Text = [key: string, ...args: string[]];

const DOCKER_IMAGE = 'ghcr.io/njmule/njmule';

/** How the available version gets installed: by njMule, or by hand as fits this installation. */
export function howToInstall(u: Update): Text {
    if (u.ready && u.canInstall) {
        return [u.install === 'at_exit' ? 'nj.update.willInstall' : 'nj.update.how.self'];
    }
    if (!u.ready && u.installer !== 'none') {
        return [u.installer === 'elevated' ? 'nj.update.how.elevated' : 'nj.update.how.self'];
    }
    // A package manager's command stays as typed, in every language.
    const file = fileName(u.available?.download?.url ?? '');
    switch (u.installation) {
        case 'msi':
            return ['nj.update.how.installer'];
        case 'dmg':
            return ['nj.update.how.dmg'];
        case 'windows_zip':
        case 'tar':
        case 'static_tar':
            return ['nj.update.how.archive'];
        case 'deb':
            return ['nj.update.how.package', `sudo apt install ./${file}`];
        case 'rpm':
            return ['nj.update.how.package', `sudo dnf install ./${file}`];
        case 'docker':
            return ['nj.update.how.docker', `docker pull ${DOCKER_IMAGE}`];
        case 'synology':
            return ['nj.update.how.synology'];
        default:
            return ['nj.update.how.page'];
    }
}

/**
 * Whether njMule can download the available version: there is a package for this installation, and one put on a
 * Synology NAS would be of no use, as the Package Center installs from the browser's computer.
 */
export function offersDownload(u: Update): boolean {
    return !u.ready && u.download === null && u.available?.download != null && u.installation !== 'synology';
}

/** The download button: njMule installs what it downloads, or the user does. */
export function downloadLabel(u: Update): string {
    return u.installer === 'none' ? 'nj.update.download' : 'nj.update.downloadInstall';
}

function fileName(url: string): string {
    const path = url.split(/[?#]/)[0] ?? '';
    const name = path.substring(path.lastIndexOf('/') + 1);
    try {
        return decodeURIComponent(name);
    } catch {
        return name;
    }
}
