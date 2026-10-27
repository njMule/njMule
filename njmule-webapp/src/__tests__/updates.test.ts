// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { downloadLabel, howToInstall, offersDownload, type Installation, type Text, type Update } from '../updates';

describe('the update panel', () => {
    const release = 'https://github.com/njMule/njMule/releases/download/v0.2.0/';
    let update: Update;
    let how: Text;
    let offered: boolean;
    let label: string;

    it('should offer to download and install where njMule installs itself', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        whenTheOfferIsMade();
        thenItOffersTheDownload('nj.update.downloadInstall');
        thenItTells('nj.update.how.self');
    });

    it('should give the package manager command for a deb package', () => {
        givenAVersionFor('deb', 'none', 'njmule_0.2.0_amd64.deb');
        whenTheOfferIsMade();
        thenItOffersTheDownload('nj.update.download');
        thenItTells('nj.update.how.package', 'sudo apt install ./njmule_0.2.0_amd64.deb');
    });

    it('should tell a Docker container to pull the new image, with nothing to download', () => {
        givenAVersionFor('docker', 'none', null);
        whenTheOfferIsMade();
        thenItOffersNoDownload();
        thenItTells('nj.update.how.docker', 'docker pull ghcr.io/njmule/njmule');
    });

    it('should send a Synology NAS to the Package Center without downloading there', () => {
        givenAVersionFor('synology', 'none', 'njmule-0.2.0-synology-x86_64.spk');
        whenTheOfferIsMade();
        thenItOffersNoDownload();
        thenItTells('nj.update.how.synology');
    });

    it('should tell that the version ready installs when njMule exits', () => {
        givenAVersionFor('msi', 'self', 'njmule-0.2.0-windows-x64.msi');
        givenItIsReadyToInstallAtExit();
        whenTheOfferIsMade();
        thenItOffersNoDownload();
        thenItTells('nj.update.willInstall');
    });

    function givenAVersionFor(installation: Installation, installer: Update['installer'], file: string | null): void {
        update = {
            current: '0.1.0',
            installation,
            installer,
            canInstall: false,
            checking: false,
            available: {
                version: '0.2.0',
                published: '2026-11-02T10:00:00Z',
                critical: false,
                notes: '',
                page: 'https://github.com/njMule/njMule/releases/tag/v0.2.0',
                download: file === null ? null : { url: release + file, size: 1, sha256: '0'.repeat(64) },
            },
            download: null,
            ready: false,
            readyFile: null,
            install: 'asked',
        };
    }

    function givenItIsReadyToInstallAtExit(): void {
        update = { ...update, ready: true, canInstall: true, install: 'at_exit' };
    }

    function whenTheOfferIsMade(): void {
        how = howToInstall(update);
        offered = offersDownload(update);
        label = downloadLabel(update);
    }

    function thenItOffersTheDownload(expected: string): void {
        expect(offered).toBe(true);
        expect(label).toBe(expected);
    }

    function thenItOffersNoDownload(): void {
        expect(offered).toBe(false);
    }

    function thenItTells(...expected: Text): void {
        expect(how).toEqual(expected);
    }
});
