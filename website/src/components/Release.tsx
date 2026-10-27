// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactNode } from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

// The files of a release, as release.yml names them: the documentation links those of the version it documents.
export const REPOSITORY = 'https://github.com/njMule/njMule';
export const IMAGE = 'ghcr.io/njmule/njmule';
const PACKAGE_PAGE = `${REPOSITORY}/pkgs/container/njmule`;

type Platform = 'windows' | 'macos' | 'linux' | 'synology' | 'checks';

const FILES = {
    windows: {
        msi: [
            (v) => `njmule-${v}-windows-x64.msi`,
            'Installer (x64; Windows on Arm runs it through its x64 emulation)',
        ],
        zip: [(v) => `njmule-${v}-windows-x64.zip`, 'Archive to unzip and run, without installing'],
    },
    macos: { dmg: [(v) => `njmule-${v}-macos-arm64.dmg`, 'Disk image for Apple silicon, macOS 13 or later'] },
    linux: {
        'deb-x64': [(v) => `njmule_${v}_amd64.deb`, 'Debian, Ubuntu and derivatives, x64'],
        'deb-arm64': [(v) => `njmule_${v}_arm64.deb`, 'Debian, Ubuntu and derivatives, arm64'],
        'rpm-x64': [(v) => `njmule-${v}-1.x86_64.rpm`, 'Fedora, RHEL, openSUSE and derivatives, x64'],
        'rpm-arm64': [(v) => `njmule-${v}-1.aarch64.rpm`, 'Fedora, RHEL, openSUSE and derivatives, arm64'],
        'tar-x64': [(v) => `njmule-${v}-linux-x64.tar.xz`, 'Archive, x64'],
        'tar-arm64': [(v) => `njmule-${v}-linux-arm64.tar.xz`, 'Archive, arm64'],
        static: [
            (v) => `njmule-${v}-linux-x64-static.tar.xz`,
            'Static headless archive, x64 (no system libraries needed)',
        ],
    },
    synology: { spk: [(v) => `njmule-${v}-synology-x86_64.spk`, 'Package for DSM 7 on x86_64'] },
    checks: {
        sums: [() => 'SHA256SUMS', 'SHA-256 checksums of every file'],
        signature: [() => 'SHA256SUMS.minisig', 'minisign signature of the checksums'],
        sbom: [(v) => `njmule-${v}-sbom.cdx.json`, 'Software bill of materials (CycloneDX)'],
    },
} satisfies Record<Platform, Record<string, [(v: string) => string, string]>>;

/** One file of the release this documentation describes: its name and the link that downloads it. */
export function releaseFile<P extends Platform>(
    version: string,
    platform: P,
    kind: keyof (typeof FILES)[P],
): { name: string; url: string } {
    const [name] = FILES[platform][kind] as [(v: string) => string, string];
    return download(version, name(version));
}

function download(version: string, file: string): { name: string; url: string } {
    return { name: file, url: `${REPOSITORY}/releases/download/v${version}/${file}` };
}

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
                {Object.values<[(v: string) => string, string]>(FILES[platform]).map(([name, what]) => {
                    const file = download(version, name(version));
                    return (
                        <tr key={file.name}>
                            <td>
                                <a href={file.url}>
                                    <code>{file.name}</code>
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
