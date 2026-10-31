// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { ref, shallowRef } from 'vue';
import { answer, api, ApiError, type Schemas } from './api/client';
import { t } from './i18n';
import { on } from './model/engine';
import type { Platform } from './platform';
import { guest } from './role';
import { message, offer, problem } from './toasts';

// New versions of njMule, as the engine tells: found, downloaded and checked, installed; the Updates dialog shows what
// is known, the status strip a download running or a version ready, and messages tell what the engine found by itself.

export type Update = Schemas['Update'];
export type NewVersion = Schemas['NewVersion'];

/** A text to show: its key, then the values of its placeholders. */
export type Text = [key: string, ...args: (string | number)[]];

/** What a button of the dialog does. */
export type UpdateAction = 'restart' | 'atExit' | 'folder' | 'download' | 'check' | 'changes' | 'skip' | 'close';

/** Where a button goes in the band: apart on the leading side, among the others, or as the main or closing answer. */
export type Role = 'left' | 'other' | 'ok' | 'close';

export interface UpdateButton {
    action: UpdateAction;
    role: Role;
}

/** What the Updates dialog shows of a status. */
export interface UpdateView {
    /** The headline: njMule installing, or the version ready or available. */
    title: Text | null;
    critical: boolean;
    /** When the version found came out. */
    published: string | null;
    /** What changed, as plain text; empty when the release says nothing. */
    notes: string;
    downloading: boolean;
    /** What the dialog says of installing it. */
    words: Text[];
    checking: boolean;
    failure: string | null;
    /** The version running, when the last check found none newer. */
    newest: string | null;
    lastCheck: string | null;
    /** The buttons the state offers, Close last. */
    buttons: UpdateButton[];
}

const DOCKER_IMAGE = 'ghcr.io/njmule/njmule';

/** What is known of new versions, as the engine last told; null until read, and for a guest, who is told nothing. */
export const update = shallowRef<Update | null>(null);

/** Whether the Updates dialog shows. */
export const updatesOpen = ref(false);

export function showUpdates(): void {
    updatesOpen.value = true;
}

/** Opens the Updates dialog and looks for a new version at once, as the menu and the options do. */
export function checkNow(): void {
    showUpdates();
    void checkForUpdate();
}

export async function readUpdate(): Promise<void> {
    if (guest) {
        return;
    }
    try {
        update.value = await answer(api().GET('/update'));
    } catch {
        // As it was: the dialog shows it, and the next event tells more.
    }
}

/** Looks for a new version now, whatever the options say; the status tells the outcome. */
export function checkForUpdate(): Promise<void> {
    return act(api().POST('/update/check'));
}

/** Stops telling of {@code version}, unless it fixes a security problem. */
export function skipUpdate(version: string): Promise<void> {
    return act(api().POST('/update/skip', { body: { version } }));
}

/** Downloads the new version and checks it; the status tells the progress and the outcome. */
export function downloadUpdate(): Promise<void> {
    return act(api().POST('/update/download'));
}

/** Installs the version downloaded: now, njMule stopping and starting again, or when it exits. */
export function installUpdate(restart: boolean): Promise<void> {
    return act(api().POST('/update/install', { body: { restart } }));
}

// A refusal shows as a problem; one to install where a package manager, the Package Center or Docker updates njMule
// says so in the user's language, as the dialog then tells how to install it by hand.
async function act(call: Promise<{ data?: Update; error?: unknown; response: Response }>): Promise<void> {
    try {
        update.value = await answer(call);
    } catch (e) {
        await readUpdate();
        const u = update.value;
        problem(
            e instanceof ApiError && e.status === 409 && u !== null && u.ready && !u.canInstall
                ? t('nj.update.cannotInstall')
                : (e as Error).message,
        );
    }
}

/** Whether the user should hear of the version available: one not skipped, or one that fixes a security problem. */
export function worthTelling(u: Update): boolean {
    const v = u.available;
    return v !== null && (v.critical || u.skipped !== v.version);
}

/** How the version available is installed by hand, as fits the way this copy was installed. */
export function howToInstall(u: Update): Text {
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

/** The download button's words: njMule installs what it downloads, or the user does. */
export function downloadLabel(u: Update): string {
    return u.installer === 'none' ? 'nj.update.download' : 'nj.update.downloadInstall';
}

/**
 * What the dialog shows: the version found with what changed, its download and its installation, a check running, this
 * version being the newest, or why something failed; with the buttons that state offers.
 */
export function updateView(u: Update): UpdateView {
    const view: UpdateView = {
        title: null,
        critical: false,
        published: null,
        notes: '',
        downloading: false,
        words: [],
        checking: u.checking,
        failure: null,
        newest: null,
        lastCheck: null,
        buttons: [],
    };
    const v = u.available;
    if (u.install === 'restart') {
        view.title = ['nj.update.installing'];
    } else if (v !== null) {
        describe(u, v, view);
    }
    if (!u.checking && u.failure) {
        view.failure = u.failure;
    } else if (!u.checking && v === null && u.lastCheck) {
        view.newest = u.current;
    }
    if ((v === null || u.failure) && u.lastCheck) {
        view.lastCheck = u.lastCheck;
    }
    if (!u.checking && v === null) {
        view.buttons.push({ action: 'check', role: 'ok' });
    }
    view.buttons.push({ action: 'close', role: 'close' });
    return view;
}

// The version found: what changed, then where its download and installation stand, with what can be done now.
function describe(u: Update, v: NewVersion, view: UpdateView): void {
    view.title = [u.ready ? 'nj.update.ready' : 'nj.update.available', v.version];
    view.critical = v.critical;
    view.published = v.published;
    view.notes = v.notes.trim() === '' ? '' : plain(v.notes);
    if (u.download !== null) {
        view.downloading = true;
    } else if (u.ready && u.canInstall) {
        view.words.push([u.install === 'at_exit' ? 'nj.update.willInstall' : 'nj.update.how.self']);
        view.buttons.push({ action: 'restart', role: 'ok' });
        if (u.install !== 'at_exit') {
            view.buttons.push({ action: 'atExit', role: 'other' });
        }
    } else if (u.ready) {
        view.words.push(['nj.update.how.file', u.readyFile ?? ''], howToInstall(u));
        view.buttons.push({ action: 'folder', role: 'ok' });
    } else {
        view.words.push(
            u.installer === 'none'
                ? howToInstall(u)
                : [u.installer === 'elevated' ? 'nj.update.how.elevated' : 'nj.update.how.self'],
        );
        // The Package Center installs a package from the browser's computer, not from the NAS.
        if (v.download !== null && u.installation !== 'synology') {
            view.buttons.push({ action: 'download', role: 'ok' });
        }
    }
    view.buttons.push({ action: 'changes', role: 'other' });
    if (!v.critical && u.skipped !== v.version) {
        view.buttons.push({ action: 'skip', role: 'left' });
    }
}

// The roles in the order the platforms' dialogs put them, '+' where the room left grows.
const ORDERS = { windows: 'L+U+OC', mac: 'L+U+CO', linux: 'L+UCO' };
const ROLES: Record<string, Role> = { L: 'left', U: 'other', O: 'ok', C: 'close' };

/** The buttons in the platform's order, null where the room left grows: one apart on the leading side, then the rest. */
export function bandOrder<B extends { role: Role }>(buttons: B[], platform: string = navigator.platform): (B | null)[] {
    const order = /^Win/.test(platform) ? ORDERS.windows : /^Mac/.test(platform) ? ORDERS.mac : ORDERS.linux;
    const band: (B | null)[] = [];
    let grows = false;
    for (const letter of order) {
        if (letter === '+') {
            grows = true;
            continue;
        }
        const placed = buttons.filter((b) => b.role === ROLES[letter]);
        if (placed.length > 0) {
            if (grows) {
                band.push(null);
                grows = false;
            }
            band.push(...placed);
        }
    }
    return band;
}

/** What the status strip says of a new version: installing, downloading, or ready; null when nothing. */
export function stripText(u: Update | null): Text | null {
    if (u === null) {
        return null;
    }
    const version = u.available?.version ?? '';
    if (u.install === 'restart') {
        return ['nj.update.installing'];
    }
    if (u.download !== null) {
        const fraction = u.download.size === 0 ? 1 : u.download.received / u.download.size;
        return ['nj.update.strip.downloading', version, Math.round(fraction * 100)];
    }
    if (u.ready && worthTelling(u)) {
        return [u.install === 'at_exit' ? 'nj.update.readyAtExit' : 'nj.update.strip.ready', version];
    }
    return null;
}

/** The notes are the Markdown of the changelog: its headings and list marks read better as plain text. */
export function plain(markdown: string): string {
    return markdown
        .split(/\r?\n/)
        .map((line) => (line.startsWith('#') ? line.replace(/^#+\s*/, '') : line.replace(/^(\s*)[-*] /, '$1• ')))
        .join('\n');
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

/**
 * Follows the engine's updates: what it found by itself and what it downloaded are told, the status kept current; in
 * the desktop app's window, a restart to install a new version ends njMule at once, as everything is saved.
 */
export function followUpdates(platform: Platform): void {
    if (guest) {
        return;
    }
    void readUpdate();
    on('update.status', (data) => {
        if (data.status) {
            update.value = data.status;
        }
    });
    on('lost', () => void readUpdate());
    on('update.available', (data) => {
        offer(t('nj.update.available', data.version?.version ?? ''), t('nj.update.details'), showUpdates);
    });
    on('update.ready', (data) => void ready(data.version?.version ?? ''));
    on('update.restart', () => {
        if (platform.desktop) {
            platform.quit();
        }
    });
}

// A new version downloaded: installed when njMule closes, or now if the user wants.
async function ready(version: string): Promise<void> {
    await readUpdate();
    const u = update.value;
    if (u?.install === 'at_exit') {
        message(t('nj.update.readyAtExit', version));
    } else if (u?.canInstall) {
        offer(t('nj.update.ready', version), t('nj.update.restartNow'), () => void installUpdate(true));
    } else {
        offer(t('nj.update.ready', version), t('nj.update.details'), showUpdates);
    }
}
