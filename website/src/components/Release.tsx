// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactNode } from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

// The files of a release, as release.yml names them: the documentation links those of the version it documents.
const REPOSITORY = 'https://github.com/njMule/njMule';
const IMAGE = 'ghcr.io/njmule/njmule';
const PACKAGE_PAGE = `${REPOSITORY}/pkgs/container/njmule`;

type Platform = 'windows' | 'macos' | 'linux' | 'synology' | 'checks';

const FILES: Record<Platform, [(v: string) => string, string][]> = {
    windows: [
        [(v) => `njmule-${v}-windows-x64.msi`, 'Installer (x64; Windows on Arm runs it through its x64 emulation)'],
        [(v) => `njmule-${v}-windows-x64.zip`, 'Archive to unzip and run, without installing'],
    ],
    macos: [[(v) => `njmule-${v}-macos-arm64.dmg`, 'Disk image for Apple silicon, macOS 13 or later']],
    linux: [
        [(v) => `njmule_${v}_amd64.deb`, 'Debian, Ubuntu and derivatives, x64'],
        [(v) => `njmule_${v}_arm64.deb`, 'Debian, Ubuntu and derivatives, arm64'],
        [(v) => `njmule-${v}-1.x86_64.rpm`, 'Fedora, RHEL, openSUSE and derivatives, x64'],
        [(v) => `njmule-${v}-1.aarch64.rpm`, 'Fedora, RHEL, openSUSE and derivatives, arm64'],
        [(v) => `njmule-${v}-linux-x64.tar.xz`, 'Archive, x64'],
        [(v) => `njmule-${v}-linux-arm64.tar.xz`, 'Archive, arm64'],
        [(v) => `njmule-${v}-linux-x64-static.tar.xz`, 'Static headless archive, x64 (no system libraries needed)'],
    ],
    synology: [[(v) => `njmule-${v}-synology-x86_64.spk`, 'Package for DSM 7 on x86_64']],
    checks: [
        [() => 'SHA256SUMS', 'SHA-256 checksums of every file'],
        [() => 'SHA256SUMS.minisig', 'minisign signature of the checksums'],
        [(v) => `njmule-${v}-sbom.cdx.json`, 'Software bill of materials (CycloneDX)'],
    ],
};

/** The version this documentation describes, from gradle.properties. */
export function useVersion(): string {
    return useDocusaurusContext().siteConfig.customFields?.version as string;
}

/** The version, in the text of a page. */
export function Version(): ReactNode {
    return <>{useVersion()}</>;
}

/** The files of a platform in the release this documentation describes, each a link to download it. */
export function Downloads({ platform }: { platform: Platform }): ReactNode {
    const version = useVersion();
    return (
        <table>
            <thead>
                <tr>
                    <th>File (njMule {version})</th>
                    <th>What it is</th>
                </tr>
            </thead>
            <tbody>
                {FILES[platform].map(([name, what]) => {
                    const file = name(version);
                    return (
                        <tr key={file}>
                            <td>
                                <a href={`${REPOSITORY}/releases/download/v${version}/${file}`}>
                                    <code>{file}</code>
                                </a>
                            </td>
                            <td>{what}</td>
                        </tr>
                    );
                })}
            </tbody>
        </table>
    );
}

/** The Docker image of this version, linked to its page on GitHub. */
export function DockerImage({ tag }: { tag?: string }): ReactNode {
    const version = useVersion();
    return (
        <a href={PACKAGE_PAGE}>
            <code>
                {IMAGE}:{tag ?? version}
            </code>
        </a>
    );
}
