// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// The home page. Its one motif is the part bar: an eD2K file is cut into parts of 9,728,000 bytes, and njMule draws a
// download as a strip of them, coloured as they arrive. The hero's strip is a real one for the link pasted in it.

import { type CSSProperties, type ReactNode, type RefObject, useEffect, useMemo, useRef, useState } from 'react';
import clsx from 'clsx';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import ThemedShot from '@site/src/components/ThemedShot';
import { useVersion } from '@site/src/components/Release';
import styles from './index.module.css';

const RELEASES = 'https://github.com/njMule/njMule/releases/latest';
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
            <div className={styles.heroFile}>
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
            </div>
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
        <section className={clsx(styles.faces, face === 'browser' && styles.facesBrowser)} data-reveal>
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

// ---------------------------------------------------------------- close-ups on a board

function Board(): ReactNode {
    return (
        <section className={styles.board} data-reveal aria-labelledby="board-title">
            <div className={clsx(styles.tile, styles.tileTitle)}>
                <h2 id="board-title" className={styles.sectionTitle}>
                    Made for today’s internet
                </h2>
            </div>
            <div className={clsx(styles.tile, styles.tileWide, styles.toneBrand)}>
                <div className={styles.closeUp} data-ui>
                    <span className={styles.statusDot} /> Connected <small>High ID · Kad: Open</small>
                </div>
                <h3>Reachable</h3>
                <p>UPnP, NAT-PMP and PCP open your ports; the port test says whether others can reach you.</p>
            </div>
            <div className={clsx(styles.tile, styles.tileTall, styles.toneSurface)}>
                <div className={clsx(styles.closeUp, styles.closeUpAddress)} data-ui>
                    2001:db8:7a1::4f2
                </div>
                <h3>IPv6 and NAT traversal</h3>
                <p>Clients reach each other over IPv6, and two firewalled ones through uTP.</p>
            </div>
            <div className={clsx(styles.tile, styles.toneSoft)}>
                <div className={clsx(styles.closeUp, styles.segments)} data-ui>
                    <span>Supported</span>
                    <span className={styles.segmentOn}>Required</span>
                </div>
                <h3>Obfuscated</h3>
                <p>Hides eD2K and Kad traffic from simple filters.</p>
            </div>
            <div className={clsx(styles.tile, styles.tileTall, styles.toneDeep)}>
                <div className={clsx(styles.closeUp, styles.toggleRow)} data-ui>
                    <span className={styles.toggle} aria-hidden="true" /> wg0
                </div>
                <h3>VPN kill switch</h3>
                <p>Bound to your VPN’s interface, njMule goes offline the moment it disappears.</p>
            </div>
            <div className={clsx(styles.tile, styles.tileWide, styles.toneSurface)}>
                <div className={clsx(styles.closeUp, styles.closeUpFigure)} data-ui>
                    SOCKS5 <small>UDP relayed</small>
                </div>
                <h3>Proxies, UDP included</h3>
                <p>SOCKS 4 and 5 or HTTP CONNECT; through SOCKS5, Kad and server searches travel too.</p>
            </div>
            <div className={clsx(styles.tile, styles.toneBrand)}>
                <div className={clsx(styles.closeUp, styles.keys)} data-ui>
                    <kbd>Ctrl</kbd>
                    <kbd>K</kbd>
                </div>
                <h3>Command palette</h3>
                <p>Places, commands, searches and links from one field.</p>
            </div>
            <div className={clsx(styles.tile, styles.toneSoft)}>
                <div className={clsx(styles.closeUp, styles.closeUpFigure)} data-ui>
                    36 <small>languages</small>
                </div>
                <h3>In your language</h3>
                <p>Right-to-left ones included, mirrored as they should be.</p>
            </div>
            <div className={clsx(styles.tile, styles.tileWide, styles.toneDeep)}>
                <div className={clsx(styles.closeUp, styles.closeUpFigure)} data-ui>
                    minisign <small>signature good</small>
                </div>
                <h3>Signed updates</h3>
                <p>New versions are checked against their signature before they install, and roll back if they fail.</p>
            </div>
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

// ---------------------------------------------------------------- install

const PLATFORMS: { name: string; page: string; link?: string; items: [string, string][] }[] = [
    {
        name: 'Windows',
        page: '/docs/getting-started/install-windows',
        items: [
            ['MSI installer', 'for you alone, no administrator'],
            ['MSI for every user', 'MSIINSTALLPERUSER=""'],
            ['Zip archive', 'unzip and run'],
            ['Windows service', 'njmule --service=install'],
            ['Arm64', 'the x64 build, in emulation'],
        ],
    },
    {
        name: 'macOS',
        page: '/docs/getting-started/install-macos',
        items: [
            ['Disk image', 'Apple silicon, drag to Applications'],
            ['First start', 'Open Anyway, in Privacy & Security'],
            ['Uninstall njMule', 'in the disk image'],
        ],
    },
    {
        name: 'Linux',
        page: '/docs/getting-started/install-linux',
        items: [
            ['deb and rpm', 'x64 and arm64, with apt or dnf'],
            ['tar.xz archive', 'with the desktop files to install by hand'],
            ['Static headless', 'no system libraries: old glibc, Alpine'],
            ['systemd user unit', 'systemctl --user enable --now njmule'],
        ],
    },
    {
        name: 'Docker',
        page: '/docs/getting-started/install-docker',
        items: [
            ['ghcr.io/njmule/njmule', 'amd64 and arm64'],
            ['Web interface', 'port 4711, password from a secret'],
            ['Peer ports', '4662/tcp and 4672/udp'],
        ],
    },
    {
        name: 'Synology DSM 7',
        page: '/docs/getting-started/install-synology',
        items: [
            ['Package for x86_64', 'Manual Install in Package Center'],
            ['Shared folder njmule', 'where completed downloads go'],
        ],
    },
    {
        name: 'From the sources',
        page: '/developer/building',
        link: 'Building njMule',
        items: [
            ['Java 25 and Gradle', './gradlew build'],
            ['Native executables', 'GraalVM Community 25'],
        ],
    },
];

function Install(): ReactNode {
    return (
        <section className={styles.install} data-reveal aria-labelledby="install-title">
            <h2 id="install-title" className={styles.giantWord}>
                Install
            </h2>
            <p className={styles.installLead}>
                Native executables everywhere: no Java to install. Every download comes with its checksums, a minisign
                signature and a software bill of materials.
            </p>
            {PLATFORMS.map((p, i) => (
                <details key={p.name} className={styles.platform} open={i === 0}>
                    <summary>{p.name}</summary>
                    <dl>
                        {p.items.map(([what, how]) => (
                            <div key={what}>
                                <dt>{what}</dt>
                                <dd>{how}</dd>
                            </div>
                        ))}
                    </dl>
                    <Link to={p.page}>{p.link ?? `Installing on ${p.name}`}</Link>
                </details>
            ))}
            <p className={styles.installLead}>
                Every version, with its checksums and signatures, is on the{' '}
                <Link href="https://github.com/njMule/njMule/releases">releases page</Link>; the guide shows how to{' '}
                <Link to="/docs/getting-started/verify-downloads">check a download</Link>.
            </p>
        </section>
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
            <div className={clsx(styles.actionSide, styles.actionPicture)}>
                <ThemedShot id="statistics" alt="The statistics of njMule" className={styles.actionImage} />
            </div>
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
            <div className={clsx(styles.actionSide, styles.actionFlat)} />
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
