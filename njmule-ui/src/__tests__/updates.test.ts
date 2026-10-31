// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import {
    bandOrder,
    downloadLabel,
    plain,
    stripText,
    updateView,
    type Role,
    type Text,
    type Update,
    type UpdateAction,
    type UpdateView,
} from '../updates';

const RELEASE = 'https://github.com/njMule/njMule/releases/download/v0.2.0/';

describe('the updates dialog', () => {
    let update: Update;
    let view: UpdateView;
    let label: string;

    it('should offer to download and install where njMule installs itself', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        whenTheDialogShowsIt();
        thenTheTitleIs(['nj.update.available', '0.2.0']);
        thenItSays(['nj.update.how.self']);
        thenTheButtonsAre('download', 'changes', 'skip', 'close');
        thenTheDownloadIsCalled('nj.update.downloadInstall');
    });

    it('should give the package manager command for a deb package', () => {
        givenAVersionFor('deb', 'none', 'njmule_0.2.0_amd64.deb');
        whenTheDialogShowsIt();
        thenItSays(['nj.update.how.package', 'sudo apt install ./njmule_0.2.0_amd64.deb']);
        thenTheDownloadIsCalled('nj.update.download');
    });

    it('should tell a Docker container to pull the new image, with nothing to download', () => {
        givenAVersionFor('docker', 'none', null);
        whenTheDialogShowsIt();
        thenItSays(['nj.update.how.docker', 'docker pull ghcr.io/njmule/njmule']);
        thenTheButtonsAre('changes', 'skip', 'close');
    });

    it('should send a Synology NAS to the Package Center without downloading there', () => {
        givenAVersionFor('synology', 'none', 'njmule-0.2.0-synology-x86_64.spk');
        whenTheDialogShowsIt();
        thenItSays(['nj.update.how.synology']);
        thenTheButtonsAre('changes', 'skip', 'close');
    });

    it('should offer to restart now or at exit once the version is ready', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        givenItIsReady(true);
        whenTheDialogShowsIt();
        thenTheTitleIs(['nj.update.ready', '0.2.0']);
        thenItSays(['nj.update.how.self']);
        thenTheButtonsAre('restart', 'atExit', 'changes', 'skip', 'close');
    });

    it('should tell that the version ready installs when njMule exits', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        givenItIsReady(true);
        givenItInstallsAtExit();
        whenTheDialogShowsIt();
        thenItSays(['nj.update.willInstall']);
        thenTheButtonsAre('restart', 'changes', 'skip', 'close');
    });

    it('should show where the package is when njMule cannot install it', () => {
        givenAVersionFor('rpm', 'none', 'njmule-0.2.0.x86_64.rpm');
        givenItIsReady(false);
        whenTheDialogShowsIt();
        thenItSays(
            ['nj.update.how.file', '/cache/njmule-0.2.0.x86_64.rpm'],
            ['nj.update.how.package', 'sudo dnf install ./njmule-0.2.0.x86_64.rpm'],
        );
        thenTheButtonsAre('folder', 'changes', 'skip', 'close');
    });

    it('should never offer to skip a version that fixes a security problem', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        givenItIsCritical();
        whenTheDialogShowsIt();
        thenItIsCritical();
        thenTheButtonsAre('download', 'changes', 'close');
    });

    it('should show only the progress while the version downloads', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        givenItDownloads(10, 40);
        whenTheDialogShowsIt();
        thenItDownloads();
        thenItSays();
        thenTheButtonsAre('changes', 'skip', 'close');
    });

    it('should say only that njMule installs while it restarts', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        givenItRestarts();
        whenTheDialogShowsIt();
        thenTheTitleIs(['nj.update.installing']);
        thenTheButtonsAre('close');
    });

    it('should tell that this version is the newest, with a new check offered', () => {
        givenNoNewerVersion();
        whenTheDialogShowsIt();
        thenTheNewestIs('0.1.0');
        thenTheButtonsAre('check', 'close');
    });

    it('should offer no new check while one runs', () => {
        givenNoNewerVersion();
        givenACheckRuns();
        whenTheDialogShowsIt();
        thenTheNewestIs(null);
        thenTheButtonsAre('close');
    });

    function givenAVersionFor(
        installation: Update['installation'],
        installer: Update['installer'],
        file: string | null,
    ) {
        update = {
            state: 'available',
            current: '0.1.0',
            installation,
            installer,
            canInstall: false,
            checking: false,
            lastCheck: '2026-11-02T09:00:00Z',
            failure: null,
            available: {
                version: '0.2.0',
                published: '2026-11-02T10:00:00Z',
                critical: false,
                notes: '',
                page: 'https://github.com/njMule/njMule/releases/tag/v0.2.0',
                download: file === null ? null : { url: RELEASE + file, size: 1, sha256: '0'.repeat(64) },
            },
            skipped: null,
            download: null,
            ready: false,
            readyFile: null,
            install: 'asked',
        };
    }

    function givenNoNewerVersion(): void {
        givenAVersionFor('msi', 'self', null);
        update = { ...update, state: 'up_to_date', available: null };
    }

    function givenItIsReady(installable: boolean): void {
        const file = `/cache/${update.available?.download?.url.split('/').pop()}`;
        update = { ...update, state: 'ready', ready: true, readyFile: file, canInstall: installable };
    }

    function givenItInstallsAtExit(): void {
        update = { ...update, install: 'at_exit' };
    }

    function givenItIsCritical(): void {
        update = { ...update, available: { ...update.available!, critical: true }, skipped: '0.2.0' };
    }

    function givenItDownloads(received: number, size: number): void {
        update = { ...update, state: 'downloading', download: { received, size } };
    }

    function givenItRestarts(): void {
        update = { ...update, state: 'installing', install: 'restart' };
    }

    function givenACheckRuns(): void {
        update = { ...update, state: 'checking', checking: true };
    }

    function whenTheDialogShowsIt(): void {
        view = updateView(update);
        label = downloadLabel(update);
    }

    function thenTheTitleIs(title: Text): void {
        expect(view.title).toEqual(title);
    }

    function thenItSays(...words: Text[]): void {
        expect(view.words).toEqual(words);
    }

    function thenTheButtonsAre(...actions: UpdateAction[]): void {
        expect(view.buttons.map((b) => b.action)).toEqual(actions);
    }

    function thenTheDownloadIsCalled(key: string): void {
        expect(label).toBe(key);
    }

    function thenItIsCritical(): void {
        expect(view.critical).toBe(true);
    }

    function thenItDownloads(): void {
        expect(view.downloading).toBe(true);
    }

    function thenTheNewestIs(version: string | null): void {
        expect(view.newest).toBe(version);
    }
});

describe('the band of the updates dialog', () => {
    const buttons: { action: string; role: Role }[] = [
        { action: 'restart', role: 'ok' },
        { action: 'atExit', role: 'other' },
        { action: 'changes', role: 'other' },
        { action: 'skip', role: 'left' },
        { action: 'close', role: 'close' },
    ];
    let platform: string;
    let band: (string | null)[];

    it('should put the main answer before Close on Windows', () => {
        givenThePlatform('Win32');
        whenTheBandIsLaidOut(buttons);
        thenTheBandIs('skip', null, 'atExit', 'changes', null, 'restart', 'close');
    });

    it('should put Close before the main answer on macOS', () => {
        givenThePlatform('MacIntel');
        whenTheBandIsLaidOut(buttons);
        thenTheBandIs('skip', null, 'atExit', 'changes', null, 'close', 'restart');
    });

    it('should keep the others beside Close on Linux', () => {
        givenThePlatform('Linux x86_64');
        whenTheBandIsLaidOut(buttons);
        thenTheBandIs('skip', null, 'atExit', 'changes', 'close', 'restart');
    });

    it('should push the buttons to the end when none stands apart', () => {
        givenThePlatform('Win32');
        whenTheBandIsLaidOut([
            { action: 'check', role: 'ok' },
            { action: 'close', role: 'close' },
        ]);
        thenTheBandIs(null, 'check', 'close');
    });

    function givenThePlatform(name: string): void {
        platform = name;
    }

    function whenTheBandIsLaidOut(given: { action: string; role: Role }[]): void {
        band = bandOrder(given, platform).map((b) => b?.action ?? null);
    }

    function thenTheBandIs(...expected: (string | null)[]): void {
        expect(band).toEqual(expected);
    }
});

describe('the status strip and the notes of a new version', () => {
    let update: Update | null;
    let strip: Text | null;
    let notes: string;

    it('should tell how far the download is', () => {
        givenAnUpdate({ download: { received: 1, size: 4 } });
        whenTheStripTellsIt();
        thenTheStripSays(['nj.update.strip.downloading', '0.2.0', 25]);
    });

    it('should tell a version ready that installs at exit', () => {
        givenAnUpdate({ ready: true, install: 'at_exit' });
        whenTheStripTellsIt();
        thenTheStripSays(['nj.update.readyAtExit', '0.2.0']);
    });

    it('should say nothing of a version ready that was skipped', () => {
        givenAnUpdate({ ready: true, skipped: '0.2.0' });
        whenTheStripTellsIt();
        thenTheStripSays(null);
    });

    it('should read the Markdown of the changes as plain text', () => {
        whenTheNotesAreRead('## Added\n- Kad zones\n  * nested\nplain line');
        thenTheNotesAre('Added\n• Kad zones\n  • nested\nplain line');
    });

    function givenAnUpdate(change: Partial<Update>): void {
        update = {
            state: 'available',
            current: '0.1.0',
            installation: 'msi',
            installer: 'self',
            canInstall: false,
            checking: false,
            lastCheck: null,
            failure: null,
            available: {
                version: '0.2.0',
                published: '2026-11-02T10:00:00Z',
                critical: false,
                notes: '',
                page: 'https://github.com/njMule/njMule/releases/tag/v0.2.0',
                download: null,
            },
            skipped: null,
            download: null,
            ready: false,
            readyFile: null,
            install: 'asked',
            ...change,
        };
    }

    function whenTheStripTellsIt(): void {
        strip = stripText(update);
    }

    function whenTheNotesAreRead(markdown: string): void {
        notes = plain(markdown);
    }

    function thenTheStripSays(expected: Text | null): void {
        expect(strip).toEqual(expected);
    }

    function thenTheNotesAre(expected: string): void {
        expect(notes).toBe(expected);
    }
});
