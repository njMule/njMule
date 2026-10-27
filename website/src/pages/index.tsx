// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The home page. Its one motif is the part bar: an eD2K file is cut into parts of 9,728,000 bytes, and njMule draws a
// download as a strip of them, coloured as they arrive. The hero's strip is a real one for the link pasted in it.

import {
    type CSSProperties,
    type KeyboardEvent,
    type ReactNode,
    type RefObject,
    useEffect,
    useMemo,
    useRef,
    useState,
} from 'react';
import clsx from 'clsx';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import CodeBlock from '@theme/CodeBlock';
import NjIcon, { type IconName } from '@site/src/components/NjIcon';
import ThemedShot from '@site/src/components/ThemedShot';
import { IMAGE, REPOSITORY, releaseFile, useVersion } from '@site/src/components/Release';
import styles from './index.module.css';

const RELEASES = `${REPOSITORY}/releases/latest`;
const PART = 9_728_000;
const EXAMPLE = 'ed2k://|file|Paper_Boats_2160p_60fps.mp4|712934400|5B1E0C9A7D24F38E61C0A2B94D7F5E13|/';

// ---------------------------------------------------------------- the part bar

interface Parsed {
    name: string;
    size: number;
    parts: number;
}

function parse(link: string): Parsed | undefined {
    const m = /^ed2k:\/\/\|file\|([^|]+)\|(\d+)\|([0-9a-fA-F]{32})\|/.exec(link.trim());
    if (!m) {
        return undefined;
    }
    const size = Number(m[2]);
    if (!Number.isSafeInteger(size) || size <= 0) {
        return undefined;
    }
    let name = m[1];
    try {
        name = decodeURIComponent(name);
    } catch {
        // The name as written.
    }
    return { name, size, parts: Math.ceil(size / PART) };
}

function amount(bytes: number): string {
    const units = ['bytes', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
    }
    return unit === 0 ? `${value} bytes` : `${value.toFixed(value < 10 ? 2 : 1)} ${units[unit]}`;
}

// What each part is: received, being received, or still to come from 0 (none) to 3 (many) sources.
type Part = 'done' | 'asked' | 0 | 1 | 2 | 3;

function sources(count: number, seed: number): Part[] {
    let x = seed || 1;
    const random = (): number => {
        x = (x * 1103515245 + 12345) & 0x7fffffff;
        return x / 0x7fffffff;
    };
    return Array.from({ length: count }, () => {
        const r = random();
        return (r < 0.08 ? 0 : r < 0.35 ? 1 : r < 0.7 ? 2 : 3) as Part;
    });
}

function useDownload(parts: number, seed: number): Part[] {
    const [state, setState] = useState<Part[]>(() => sources(parts, seed));
    useEffect(() => {
        const start = sources(parts, seed);
        const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (still) {
            // A download well under way, without the movement.
            setState(start.map((p, i) => (i % 5 < 3 && p !== 0 ? 'done' : p)));
            return undefined;
        }
        let current = start;
        setState(current);
        // Parts arrive in the order the sources can send them: the most common first, the rare ones last.
        const order = current
            .map((p, i) => ({ i, rank: typeof p === 'number' ? p : 0 }))
            .filter((p) => p.rank > 0)
            .sort((a, b) => b.rank - a.rank || a.i - b.i)
            .map((p) => p.i);
        const tick = Math.max(28, Math.min(160, 9000 / Math.max(1, order.length)));
        let at = 0;
        const timer = window.setInterval(() => {
            current = current.slice();
            if (at > 0 && at <= order.length) {
                current[order[at - 1]] = 'done';
            }
            if (at < order.length) {
                current[order[at]] = 'asked';
            }
            at++;
            if (at > order.length + 30) {
                current = sources(parts, seed);
                at = 0;
            }
            setState(current);
        }, tick);
        return () => window.clearInterval(timer);
    }, [parts, seed]);
    return state;
}

function PartBar({ parts, seed, label }: { parts: number; seed: number; label: string }): ReactNode {
    const state = useDownload(parts, seed);
    const done = state.filter((p) => p === 'done').length;
    // Up to a few hundred parts each keeps a gap; past that the strip is drawn edge to edge, as the app does.
    const tight = parts > 160;
    return (
        <div
            className={clsx(styles.bar, tight && styles.barTight)}
            role="img"
            aria-label={`${label}: ${done} of ${parts} parts received`}
        >
            {state.map((p, i) => (
                <span key={i} className={styles[`part-${p}`]} />
            ))}
        </div>
    );
}

function Hero(): ReactNode {
    const version = useVersion();
    const [link, setLink] = useState(EXAMPLE);
    const parsed = useMemo(() => parse(link), [link]);
    const [file, setFile] = useState<Parsed>(() => parse(EXAMPLE)!);
    useEffect(() => {
        if (parsed) {
            setFile(parsed);
        }
    }, [parsed]);
    const seed = useMemo(
        () => [...file.name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, file.size % 9973),
        [file],
    );
    return (
        <header className={styles.hero}>
            <div className={styles.heroText}>
                <h1 className={styles.heroTitle}>A new mule for eD2K and Kad</h1>
                <p className={styles.heroLead}>
                    njMule is a free client for the eMule network: native apps for Windows, macOS and Linux, a web
                    interface and a headless server, all on one engine.
                </p>
                <div className={styles.actions}>
                    <Link className={styles.primary} href={RELEASES}>
                        Download njMule
                    </Link>
                    <Link className={styles.secondary} to="/docs/intro">
                        Read the guide
                    </Link>
                </div>
                <p className={styles.release}>Version {version}: Windows, macOS, Linux, Docker and Synology DSM</p>
            </div>
            <div className={styles.fileCard}>
                <label className={styles.linkLabel} htmlFor="hero-link">
                    Paste an eD2K link to see how njMule cuts the file into parts
                </label>
                <input
                    id="hero-link"
                    className={styles.linkField}
                    value={link}
                    spellCheck={false}
                    autoComplete="off"
                    onChange={(e) => setLink(e.target.value)}
                    onFocus={(e) => e.target.select()}
                    aria-describedby="hero-file"
                />
                <p id="hero-file" className={styles.fileFacts} aria-live="polite">
                    {parsed || link.trim() === '' ? (
                        <>
                            <strong>{file.name}</strong> · {amount(file.size)} · {file.parts}{' '}
                            {file.parts === 1 ? 'part' : 'parts'} of 9.28 MB
                        </>
                    ) : (
                        <>
                            That is not a file link: one reads <strong>ed2k://|file|name|size|hash|/</strong>
                        </>
                    )}
                </p>
                <PartBar parts={file.parts} seed={seed} label={file.name} />
                <ul className={styles.legend}>
                    <li>
                        <span className={styles['part-done']} /> Received
                    </li>
                    <li>
                        <span className={styles['part-asked']} /> Being received
                    </li>
                    <li>
                        <span className={styles['part-3']} /> To come, brighter with more sources
                    </li>
                    <li>
                        <span className={styles['part-0']} /> No source yet
                    </li>
                </ul>
            </div>
        </header>
    );
}

// ---------------------------------------------------------------- one control, blown up

const FACES = {
    desktop: {
        word: 'on your desktop',
        text: 'A native app with every screen redrawn: transfers with their sources, search, shared files, messages, the network and statistics, in 36 languages.',
        shot: 'transfers-details',
        alt: 'The desktop app: the transfers, with the details of a download',
    },
    browser: {
        word: 'in your browser',
        text: 'The web interface does what the desktop app does, from any browser on your network or behind your own reverse proxy, with a read-only guest if you like.',
        shot: 'web-transfers-sources',
        alt: 'The web interface: the transfers, a download open on its sources',
    },
} as const;

function Faces(): ReactNode {
    const [face, setFace] = useState<keyof typeof FACES>('desktop');
    const shown = FACES[face];
    return (
        <section className={styles.faces} data-reveal>
            <div className={styles.facesHead}>
                <div className={styles.facesText}>
                    <h2 className={styles.sectionTitle}>Run it {shown.word}</h2>
                    <p>{shown.text}</p>
                </div>
                <div className={styles.switch} data-ui role="radiogroup" aria-label="Show njMule">
                    {(Object.keys(FACES) as (keyof typeof FACES)[]).map((key) => (
                        <button
                            key={key}
                            type="button"
                            role="radio"
                            aria-checked={face === key}
                            className={clsx(styles.switchItem, face === key && styles.switchOn)}
                            onClick={() => setFace(key)}
                        >
                            {key === 'desktop' ? 'Desktop app' : 'Web interface'}
                        </button>
                    ))}
                </div>
            </div>
            <div className={styles.facesShot}>
                {(Object.keys(FACES) as (keyof typeof FACES)[]).map((key) => (
                    <ThemedShot
                        key={key}
                        id={FACES[key].shot}
                        alt={FACES[key].alt}
                        className={clsx(styles.faceImage, face === key && styles.faceImageOn)}
                    />
                ))}
            </div>
        </section>
    );
}

// ---------------------------------------------------------------- what it does on the network

// Each card shows a small piece of the interface that tells the feature, whole and at reading size.
const FEATURES: { tone: 'brand' | 'surface' | 'soft' | 'deep'; title: string; text: string; sample: ReactNode }[] = [
    {
        tone: 'brand',
        title: 'Reachable',
        text: 'UPnP, NAT-PMP and PCP open your ports; the port test says whether others can reach you.',
        sample: (
            <>
                <span className={styles.statusDot} aria-hidden="true" /> Connected <small>High ID · Kad open</small>
            </>
        ),
    },
    {
        tone: 'surface',
        title: 'IPv6 and NAT traversal',
        text: 'Clients reach each other over IPv6, and two firewalled ones through uTP or QUIC.',
        sample: <code>2001:db8:7a1::4f2</code>,
    },
    {
        tone: 'soft',
        title: 'Obfuscated',
        text: 'Hides eD2K and Kad traffic from simple filters, or refuses clients that do not hide it.',
        sample: (
            <span className={styles.segments}>
                <span>Supported</span>
                <span className={styles.segmentOn}>Required</span>
            </span>
        ),
    },
    {
        tone: 'deep',
        title: 'VPN kill switch',
        text: 'Bound to your VPN’s network interface, njMule goes offline the moment the VPN drops.',
        sample: (
            <>
                <span className={styles.toggle} aria-hidden="true" /> Only through the VPN
            </>
        ),
    },
    {
        tone: 'surface',
        title: 'Proxies, UDP included',
        text: 'SOCKS 4 and 5 or HTTP CONNECT; through SOCKS 5, Kad and server searches travel too.',
        sample: (
            <>
                SOCKS 5 <small>UDP relayed</small>
            </>
        ),
    },
    {
        tone: 'brand',
        title: 'Command palette',
        text: 'Places, commands, searches and links, all from one field.',
        sample: (
            <span className={styles.keys}>
                <kbd>Ctrl</kbd> + <kbd>K</kbd>
            </span>
        ),
    },
    {
        tone: 'soft',
        title: 'In your language',
        text: 'Thirty-six of them, the right-to-left ones mirrored as they should be.',
        sample: (
            <>
                36 <small>languages</small>
            </>
        ),
    },
    {
        tone: 'deep',
        title: 'Signed updates',
        text: 'New versions are checked against their signature before they install, and roll back if they fail.',
        sample: (
            <>
                <span className={styles.check} aria-hidden="true" /> Signature verified
            </>
        ),
    },
];

function Board(): ReactNode {
    return (
        <section className={styles.board} data-reveal aria-labelledby="board-title">
            <div className={styles.boardHead}>
                <h2 id="board-title" className={styles.sectionTitle}>
                    Made for today’s internet
                </h2>
                <p>Behind a router, a firewall or a VPN, njMule finds its way to the network and keeps you private.</p>
            </div>
            <ul className={styles.cards}>
                {FEATURES.map((f) => (
                    <li key={f.title} className={clsx(styles.card, styles[`tone-${f.tone}`])}>
                        <div className={styles.sample} data-ui>
                            {f.sample}
                        </div>
                        <h3>{f.title}</h3>
                        <p>{f.text}</p>
                    </li>
                ))}
            </ul>
        </section>
    );
}

// ---------------------------------------------------------------- moving from eMule

const STAGES = [
    {
        name: 'Install',
        clause: 'First, it finds your eMule',
        text: 'At its first start njMule looks for an eMule profile on this computer and offers to bring it along.',
        shot: 'dialog-wizard-1',
        alt: 'The first-start wizard, offering to import the eMule found',
    },
    {
        name: 'Choose',
        clause: 'Then you point at the profile',
        text: 'Any eMule configuration folder will do. njMule only reads it, and never writes there.',
        shot: 'dialog-import',
        alt: 'Import from eMule, with the folder of the profile',
    },
    {
        name: 'Check',
        clause: 'Next, you check every item',
        text: 'Servers, Kad contacts, known and shared files, preferences, credits and downloads: each with what will happen to it.',
        shot: 'dialog-import-report',
        alt: 'Import from eMule after Check, with the outcome of each item',
    },
    {
        name: 'Carry on',
        clause: 'Last, your downloads resume',
        text: 'Your unfinished downloads, your identity if you want it, and your shared folders continue in njMule.',
        shot: 'transfers',
        alt: 'The transfers, the imported downloads under way',
    },
] as const;

function Stages(): ReactNode {
    const [at, setAt] = useState(0);
    const stage = STAGES[at];
    return (
        <section className={styles.stages} data-reveal aria-label="Moving from eMule">
            <div className={styles.stageClause}>
                <h2 className={styles.stageTitle} aria-live="polite">
                    {stage.clause}
                </h2>
                <p>{stage.text}</p>
                <Link to="/docs/getting-started/import-emule">How the import works</Link>
            </div>
            <div className={styles.stageShot}>
                {STAGES.map((s, i) => (
                    <ThemedShot
                        key={s.shot}
                        id={s.shot}
                        alt={s.alt}
                        className={clsx(styles.stageImage, i === at && styles.stageImageOn)}
                    />
                ))}
            </div>
            <div className={styles.stageRail}>
                <div className={styles.rangeWrap} style={{ '--at': at / (STAGES.length - 1) } as CSSProperties}>
                    <span className={styles.rangeTrack} aria-hidden="true" />
                    <input
                        type="range"
                        min={0}
                        max={STAGES.length - 1}
                        step={1}
                        value={at}
                        aria-label="Stage of the move from eMule"
                        aria-valuetext={stage.name}
                        onChange={(e) => setAt(Number(e.target.value))}
                        className={styles.range}
                    />
                </div>
                <ol className={styles.stageNames}>
                    {STAGES.map((s, i) => (
                        <li key={s.name}>
                            <button
                                type="button"
                                className={clsx(i === at && styles.stageNameOn)}
                                onClick={() => setAt(i)}
                            >
                                {s.name}
                            </button>
                        </li>
                    ))}
                </ol>
            </div>
        </section>
    );
}

// ---------------------------------------------------------------- install: pick a system, get its file and its steps

type System = 'windows' | 'macos' | 'linux' | 'docker' | 'synology' | 'sources';
type Package = 'deb' | 'rpm' | 'tar';
type Processor = 'x64' | 'arm64';

interface Offer {
    title: string;
    download?: { label: string; file: { name: string; url: string } };
    // Where there is no file to download, the command that starts.
    command?: string;
    others: { what: string; file: { name: string; url: string } }[];
    facts: string[];
    shell: 'bash' | 'powershell';
    steps: { text: string; command?: string }[];
}

const SYSTEMS: {
    key: System;
    name: string;
    short?: string;
    formats: string;
    icon: IconName;
    page: string;
    link: string;
    offer: (version: string, linux: [Package, Processor]) => Offer;
}[] = [
    {
        key: 'windows',
        name: 'Windows',
        formats: 'MSI · zip',
        icon: 'DESKTOP',
        page: '/docs/getting-started/install-windows',
        link: 'Installing on Windows',
        offer: (version) => {
            const msi = releaseFile(version, 'windows', 'msi');
            return {
                title: 'Installed with a double-click',
                download: { label: 'Download the installer', file: msi },
                others: [
                    { what: 'Zip archive, to run without installing', file: releaseFile(version, 'windows', 'zip') },
                ],
                facts: ['x64, Arm64 in emulation', 'No administrator rights', 'Also as a Windows service'],
                shell: 'powershell',
                steps: [
                    { text: 'Download the installer.' },
                    {
                        text: 'Double-click it (if SmartScreen stops it: More info, then Run anyway), or install it from a terminal:',
                        command: `msiexec /i ${msi.name}`,
                    },
                    {
                        text: 'Start njMule from the Start menu: the first-start wizard offers to bring your eMule along.',
                    },
                ],
            };
        },
    },
    {
        key: 'macos',
        name: 'macOS',
        formats: 'DMG · Apple silicon',
        icon: 'LAPTOP',
        page: '/docs/getting-started/install-macos',
        link: 'Installing on macOS',
        offer: (version) => ({
            title: 'Dragged to Applications',
            download: { label: 'Download the disk image', file: releaseFile(version, 'macos', 'dmg') },
            others: [],
            facts: ['Apple silicon', 'macOS 13 or later', 'Intel Macs: the Docker image'],
            shell: 'bash',
            steps: [
                { text: 'Open the disk image and drag njMule to Applications.' },
                {
                    text: 'At its first start macOS stops it, as it is not notarized: in System Settings → Privacy & Security, choose Open Anyway.',
                },
                { text: 'Keep the disk image: its Uninstall njMule removes the app, and your data if you ask.' },
            ],
        }),
    },
    {
        key: 'linux',
        name: 'Linux',
        formats: 'deb · rpm · tar.xz',
        icon: 'TERMINAL',
        page: '/docs/getting-started/install-linux',
        link: 'Installing on Linux',
        offer: (version, [kind, processor]) => {
            const file = releaseFile(version, 'linux', `${kind}-${processor}`);
            const folder = file.name.replace(/\.tar\.xz$/, '');
            return {
                title: 'Packages for apt and dnf',
                download: {
                    label: kind === 'tar' ? 'Download the archive' : `Download the .${kind}`,
                    file,
                },
                others: [
                    {
                        what: 'Static headless archive, for old systems and Alpine',
                        file: releaseFile(version, 'linux', 'static'),
                    },
                ],
                facts: ['x64 and arm64', 'Desktop app and headless', 'In your applications menu'],
                shell: 'bash',
                steps:
                    kind === 'tar'
                        ? [
                              { text: 'Download the archive for your processor.' },
                              {
                                  text: 'Unpack it into a folder you can write to:',
                                  command: `mkdir -p ~/Apps\ntar -C ~/Apps -xJf ${file.name}`,
                              },
                              { text: 'Start the desktop app from there:', command: `~/Apps/${folder}/njmule-gui` },
                          ]
                        : [
                              { text: 'Download the package for your distribution and processor.' },
                              {
                                  text: `Install it with ${kind === 'deb' ? 'apt' : 'dnf'}:`,
                                  command: `sudo ${kind === 'deb' ? 'apt' : 'dnf'} install ./${file.name}`,
                              },
                              {
                                  text: 'Start it from your applications menu, or keep it running headless as a service of your user:',
                                  command: 'systemctl --user enable --now njmule',
                              },
                          ],
            };
        },
    },
    {
        key: 'docker',
        name: 'Docker',
        formats: 'amd64 · arm64',
        icon: 'CONTAINER',
        page: '/docs/getting-started/install-docker',
        link: 'Installing with Docker',
        offer: (version) => ({
            title: 'One container, its web interface on port 4711',
            command: `docker pull ${IMAGE}:${version}`,
            others: [],
            facts: ['amd64 and arm64', 'Password from a secret', '4662/tcp · 4672/udp · 4711/tcp'],
            shell: 'bash',
            steps: [
                {
                    text: 'Make a folder for the completed downloads, and a file whose first line is the web interface password.',
                },
                {
                    text: 'Start the container:',
                    command: [
                        'docker run -d --name njmule --restart unless-stopped --stop-timeout 30 \\',
                        '    -p 4662:4662/tcp -p 4672:4672/udp -p 4711:4711/tcp \\',
                        '    -v njmule:/njmule -v ~/njmule/incoming:/njmule/incoming \\',
                        '    -v ~/njmule/webui_password:/run/secrets/njmule_webui_password:ro \\',
                        `    ${IMAGE}:${version}`,
                    ].join('\n'),
                },
                { text: 'Open port 4711 of the host in a browser and log in. The guide has a Compose file too.' },
            ],
        }),
    },
    {
        key: 'synology',
        name: 'Synology',
        formats: 'DSM 7 · x86_64',
        icon: 'NAS',
        page: '/docs/getting-started/install-synology',
        link: 'Installing on Synology',
        offer: (version) => ({
            title: 'A package for Package Center',
            download: { label: 'Download the package', file: releaseFile(version, 'synology', 'spk') },
            others: [],
            facts: ['DSM 7.0 or later', 'x86_64 models', 'Downloads in the shared folder njmule'],
            shell: 'bash',
            steps: [
                { text: 'In Package Center, choose Manual Install and pick the .spk file.' },
                {
                    text: 'Confirm the warning about a package Synology did not sign, then choose the web interface password.',
                },
                { text: 'Open njMule from Package Center, or port 4711 of the NAS in a browser.' },
            ],
        }),
    },
    {
        key: 'sources',
        name: 'From the sources',
        short: 'Sources',
        formats: 'JDK 25 · Gradle',
        icon: 'CODE',
        page: '/developer/building',
        link: 'Building njMule',
        offer: () => ({
            title: 'Built, changed and run by you',
            command: `git clone ${REPOSITORY}.git`,
            others: [],
            facts: ['JDK 25', 'Gradle Wrapper', 'GraalVM Community for native builds'],
            shell: 'bash',
            steps: [
                { text: 'Go into the folder the clone made:', command: 'cd njMule' },
                { text: 'Build it and run every check:', command: './gradlew build' },
                { text: 'Start the desktop app:', command: './gradlew :njmule-gui:run' },
            ],
        }),
    },
];

// The visitor's system, chosen for them once the page runs: nothing on a phone, where none of these installs.
function detectSystem(): [System, Processor] | undefined {
    const agent = navigator as Navigator & { userAgentData?: { platform: string } };
    const platform = agent.userAgentData?.platform || navigator.userAgent;
    const processor = /aarch64|arm64/i.test(navigator.userAgent) ? 'arm64' : 'x64';
    if (/Android|iPhone|iPad/i.test(navigator.userAgent)) {
        return undefined;
    }
    if (/Win/i.test(platform)) {
        return ['windows', processor];
    }
    if (/Mac/i.test(platform)) {
        return ['macos', processor];
    }
    if (/Linux|X11|CrOS/i.test(platform)) {
        return ['linux', processor];
    }
    return undefined;
}

function Install(): ReactNode {
    const version = useVersion();
    const [system, setSystem] = useState<System>('windows');
    const [yours, setYours] = useState<System>();
    const [linux, setLinux] = useState<[Package, Processor]>(['deb', 'x64']);
    const tabs = useRef<(HTMLButtonElement | null)[]>([]);
    useEffect(() => {
        const found = detectSystem();
        if (found) {
            setSystem(found[0]);
            setYours(found[0]);
            setLinux((l) => [l[0], found[1]]);
        }
    }, []);
    const chosen = SYSTEMS.find((s) => s.key === system)!;
    const offer = chosen.offer(version, linux);
    // Arrows, Home and End move between the systems as between tabs.
    const onKeyDown = (e: KeyboardEvent): void => {
        const at = SYSTEMS.indexOf(chosen);
        const to = { ArrowRight: at + 1, ArrowDown: at + 1, ArrowLeft: at - 1, ArrowUp: at - 1, Home: 0 }[e.key];
        const next = e.key === 'End' ? SYSTEMS.length - 1 : to;
        if (next === undefined) {
            return;
        }
        e.preventDefault();
        const index = (next + SYSTEMS.length) % SYSTEMS.length;
        setSystem(SYSTEMS[index].key);
        tabs.current[index]?.focus();
    };
    return (
        <section className={styles.install} data-reveal aria-labelledby="install-title">
            <div className={styles.installHead}>
                <h2 id="install-title" className={styles.sectionTitle}>
                    Install
                </h2>
                <p>Native executables everywhere: no Java to install. The system you are on is already chosen.</p>
            </div>
            <div className={styles.systems} role="tablist" aria-label="Your system" onKeyDown={onKeyDown}>
                {SYSTEMS.map((s, i) => (
                    <button
                        key={s.key}
                        ref={(el) => {
                            tabs.current[i] = el;
                        }}
                        type="button"
                        role="tab"
                        id={`install-tab-${s.key}`}
                        aria-selected={s.key === system}
                        aria-controls="install-panel"
                        tabIndex={s.key === system ? 0 : -1}
                        className={clsx(styles.system, styles[`system-${s.key}`])}
                        onClick={() => setSystem(s.key)}
                    >
                        {s.key === yours && <span className={styles.yours}>Your system</span>}
                        <span className={styles.systemIcon}>
                            <NjIcon name={s.icon} />
                        </span>
                        <span>
                            <span className={styles.systemName}>
                                {s.short ? (
                                    <>
                                        <span className={styles.long}>{s.name}</span>
                                        <span className={styles.short}>{s.short}</span>
                                    </>
                                ) : (
                                    s.name
                                )}
                            </span>
                            <span className={styles.systemFormats}>{s.formats}</span>
                        </span>
                    </button>
                ))}
            </div>
            <div
                key={system}
                id="install-panel"
                role="tabpanel"
                aria-labelledby={`install-tab-${system}`}
                className={styles.offer}
            >
                <div className={styles.offerMain}>
                    <span className={styles.offerFor}>
                        <NjIcon name={chosen.icon} size={18} /> njMule {version}{' '}
                        {system === 'sources' ? 'for developers' : `for ${chosen.name}`}
                    </span>
                    <h3>{offer.title}</h3>
                    {system === 'linux' && (
                        <div className={styles.choices}>
                            <Choice
                                label="Package"
                                options={[
                                    ['deb', 'deb'],
                                    ['rpm', 'rpm'],
                                    ['tar', 'tar.xz'],
                                ]}
                                value={linux[0]}
                                onChange={(v) => setLinux((l) => [v, l[1]])}
                            />
                            <Choice
                                label="Processor"
                                options={[
                                    ['x64', 'x64'],
                                    ['arm64', 'arm64'],
                                ]}
                                value={linux[1]}
                                onChange={(v) => setLinux((l) => [l[0], v])}
                            />
                        </div>
                    )}
                    {offer.download && (
                        <a className={styles.download} href={offer.download.file.url}>
                            <NjIcon name="DOWNLOAD" />
                            <strong>{offer.download.label}</strong>
                            <small>{offer.download.file.name}</small>
                        </a>
                    )}
                    {offer.command && (
                        <CodeBlock language="bash" className={styles.firstCommand}>
                            {offer.command}
                        </CodeBlock>
                    )}
                    {offer.others.length > 0 && (
                        <ul className={styles.others}>
                            {offer.others.map((o) => (
                                <li key={o.file.name}>
                                    <a href={o.file.url}>
                                        <NjIcon name="FILE_ARCHIVE" size={18} />
                                        {o.what}
                                    </a>
                                </li>
                            ))}
                        </ul>
                    )}
                    <ul className={styles.facts}>
                        {offer.facts.map((f) => (
                            <li key={f}>
                                <NjIcon name="CHECK" size={16} />
                                {f}
                            </li>
                        ))}
                    </ul>
                    <Link className={styles.offerGuide} to={chosen.page}>
                        {chosen.link} <NjIcon name="ARROW_FORWARD" size={18} />
                    </Link>
                </div>
                <div className={styles.offerSteps}>
                    <h4>In three steps</h4>
                    <ol>
                        {offer.steps.map((s) => (
                            <li key={s.text}>
                                <p>{s.text}</p>
                                {s.command && <CodeBlock language={offer.shell}>{s.command}</CodeBlock>}
                            </li>
                        ))}
                    </ol>
                </div>
            </div>
            <ul className={styles.proofs}>
                <li className={styles['proof-signed']}>
                    <span className={styles.proofIcon}>
                        <NjIcon name="SHIELD" />
                    </span>
                    <span>
                        <strong>Signed</strong> minisign signature of the checksums
                    </span>
                </li>
                <li className={styles['proof-sums']}>
                    <span className={styles.proofIcon}>
                        <NjIcon name="CHECK_CIRCLE" />
                    </span>
                    <span>
                        <strong>Checksums</strong> SHA-256 of every file
                    </span>
                </li>
                <li className={styles['proof-sbom']}>
                    <span className={styles.proofIcon}>
                        <NjIcon name="FILE_DOCUMENT" />
                    </span>
                    <span>
                        <strong>Bill of materials</strong> every library inside, in CycloneDX
                    </span>
                </li>
                <li className={styles.proofLinks}>
                    <Link to="/docs/getting-started/verify-downloads">
                        How to check a download <NjIcon name="ARROW_FORWARD" size={18} />
                    </Link>
                    <Link href={`${REPOSITORY}/releases`}>
                        Every version on GitHub <NjIcon name="OPEN_EXTERNAL" size={18} />
                    </Link>
                </li>
            </ul>
        </section>
    );
}

function Choice<T extends string>({
    label,
    options,
    value,
    onChange,
}: {
    label: string;
    options: [T, string][];
    value: T;
    onChange: (value: T) => void;
}): ReactNode {
    return (
        <span className={styles.choice} role="group" aria-label={label}>
            {options.map(([v, text]) => (
                <button key={v} type="button" aria-pressed={v === value} onClick={() => onChange(v)}>
                    {text}
                </button>
            ))}
        </span>
    );
}

// ---------------------------------------------------------------- find your way (index with preview)

const PLACES: { title: string; text: string; to: string; shot?: string; alt: string }[] = [
    {
        title: 'Getting started',
        text: 'Install, the first-start wizard, your first download.',
        to: '/docs/getting-started',
        shot: 'dialog-wizard-2-over',
        alt: 'The first-start wizard over the main window',
    },
    {
        title: 'Desktop app',
        text: 'Every screen, menu, shortcut and theme.',
        to: '/docs/desktop',
        shot: 'search-live',
        alt: 'The desktop app searching the network',
    },
    {
        title: 'Web interface',
        text: 'Turn it on, keep it safe, put it behind a proxy.',
        to: '/docs/web/overview',
        shot: 'web-network-kad',
        alt: 'The web interface showing Kad',
    },
    {
        title: 'Options',
        text: 'Every setting of every page, with its default.',
        to: '/docs/settings',
        shot: 'settings-page-02',
        alt: 'The connection options of the desktop app',
    },
    {
        title: 'Headless and servers',
        text: 'The command line, services, Docker and remote control.',
        to: '/docs/server/headless',
        shot: 'web-network-log',
        alt: 'The log of a headless njMule, in the web interface',
    },
    {
        title: 'Developers and API',
        text: 'Build njMule, change it, or drive it over HTTP.',
        to: '/developer',
        alt: 'A request to the remote-control API',
    },
];

function Places(): ReactNode {
    const [at, setAt] = useState(0);
    return (
        <section className={styles.places} data-reveal aria-labelledby="places-title">
            <h2 id="places-title" className={styles.sectionTitle}>
                Find your way
            </h2>
            <div className={styles.placesBody}>
                <ul className={styles.placeList} onMouseLeave={() => undefined}>
                    {PLACES.map((p, i) => (
                        <li key={p.title} className={clsx(i === at && styles.placeOn)}>
                            <Link to={p.to} onMouseEnter={() => setAt(i)} onFocus={() => setAt(i)}>
                                <span className={styles.placeTitle}>{p.title}</span>
                                <span className={styles.placeText}>{p.text}</span>
                            </Link>
                            <div className={styles.placeInline}>
                                <Preview place={p} />
                            </div>
                        </li>
                    ))}
                </ul>
                <div className={styles.placeFrame} aria-hidden="true">
                    {PLACES.map((p, i) => (
                        <div key={p.title} className={clsx(styles.placePreview, i === at && styles.placePreviewOn)}>
                            <Preview place={p} />
                        </div>
                    ))}
                </div>
            </div>
        </section>
    );
}

function Preview({ place }: { place: (typeof PLACES)[number] }): ReactNode {
    if (place.shot) {
        return <ThemedShot id={place.shot} alt={place.alt} className={styles.previewImage} />;
    }
    return (
        <pre className={styles.previewCode} data-ui>
            <code>
                {'curl -H "Authorization: Bearer $TOKEN" \\\n     http://127.0.0.1:4711/api/v1/downloads\n\n'}
                {
                    '[{"name": "Paper_Boats_2160p_60fps.mp4",\n  "size": 712934400,\n  "status": "downloading",\n  "sources": 9, …}]'
                }
            </code>
        </pre>
    );
}

// ---------------------------------------------------------------- the action tiles and the mark

function Action(): ReactNode {
    return (
        <section className={styles.action} data-reveal aria-labelledby="action-title">
            <div className={styles.actionMain}>
                <h2 id="action-title">Start sharing in minutes</h2>
                <p>
                    njMule is free software under the GNU General Public License: no account, no advertising, and every
                    line of it on GitHub.
                </p>
                <div className={styles.actions}>
                    <Link className={styles.primary} href={RELEASES}>
                        Download njMule
                    </Link>
                    <Link className={styles.secondary} to="/docs/getting-started/first-run">
                        Your first start
                    </Link>
                </div>
            </div>
            <div className={styles.actionPicture}>
                <ThemedShot id="statistics" alt="The statistics of njMule" className={styles.actionImage} />
            </div>
        </section>
    );
}

const MARK: [string, number][] = [
    ['n', 0.62],
    ['j', 0.86],
    ['M', 1],
    ['u', 0.7],
    ['l', 0.92],
    ['e', 0.78],
];

function Mark(): ReactNode {
    return (
        <section className={styles.mark} aria-label="njMule">
            <div className={styles.markBlocks} aria-hidden="true">
                {MARK.map(([letter, height], i) => (
                    <span key={i} style={{ '--h': height } as CSSProperties} className={styles[`block${i % 3}`]}>
                        {letter}
                    </span>
                ))}
            </div>
            <p className={styles.markNote}>
                njMule works with the eMule network, its clients and its servers. It is not affiliated with the eMule
                project, whose authors it thanks. Share only what you have the right to share.
            </p>
        </section>
    );
}

// Each section's parts rise into place once, as it comes into view; without script they are simply there.
function useReveal(root: RefObject<HTMLDivElement | null>): void {
    useEffect(() => {
        const page = root.current;
        if (!page || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return undefined;
        }
        const sections = [...page.querySelectorAll<HTMLElement>('[data-reveal]')];
        const observer = new IntersectionObserver(
            (entries) => {
                for (const e of entries) {
                    if (e.isIntersecting) {
                        e.target.setAttribute('data-reveal', 'shown');
                        observer.unobserve(e.target);
                    }
                }
            },
            { rootMargin: '0px 0px -12% 0px' },
        );
        for (const s of sections) {
            if (s.getBoundingClientRect().top > window.innerHeight) {
                s.setAttribute('data-reveal', 'waiting');
                observer.observe(s);
            }
        }
        return () => observer.disconnect();
    }, [root]);
}

export default function Home(): ReactNode {
    const page = useRef<HTMLDivElement>(null);
    useReveal(page);
    return (
        <Layout
            title="A modern eD2K and Kad client"
            description="njMule is a free, modern client for the eMule network: native apps for Windows, macOS and Linux, a web interface, a headless server and a JSON API."
        >
            <div ref={page} className={styles.page}>
                <Hero />
                <main>
                    <Faces />
                    <Board />
                    <Stages />
                    <Install />
                    <Places />
                    <Action />
                    <Mark />
                </main>
            </div>
        </Layout>
    );
}
